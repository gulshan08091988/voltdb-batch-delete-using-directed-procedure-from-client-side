package com.example;

import org.voltdb.VoltTable;
import org.voltdb.client.*;

import java.util.concurrent.CompletableFuture;

/**
 * =============================================================================
 * DELETE BY STATUS - DIRECTED Java Procedure with CLIENT-SIDE BATCHING
 * =============================================================================
 *
 * Deletes orders by status using a DIRECTED stored procedure that executes
 * on ALL partitions simultaneously.
 *
 * KEY CONCEPTS:
 * -------------
 * 1. DIRECTED Procedure: Executes on ALL partitions via callAllPartitionProcedure()
 * 2. Client-Side Batching: Procedure deletes ONE batch, client loops until done
 * 3. Rate Limiting: Inter-batch delay allows other transactions to complete
 *
 * WHY CLIENT-SIDE BATCHING?
 * -------------------------
 * In VoltDB, each procedure invocation = one transaction.
 * Looping inside the procedure doesn't help - it's still one long transaction.
 *
 * To keep cluster latency low:
 * 1. Procedure deletes a LIMITED number of rows (e.g., 2000 per partition)
 * 2. Client calls procedure repeatedly
 * 3. Between calls, other transactions can execute
 * 4. Cluster latency stays low (< 100ms per batch)
 *
 * USAGE:
 * ------
 *   java DeleteByStatus <status> [batchSize]
 *
 * EXAMPLES:
 * ---------
 *   java DeleteByStatus DELIVERED         Delete at 2000 rows/partition/batch
 *   java DeleteByStatus DELIVERED 5000    Delete at 5000 rows/partition/batch
 *   java DeleteByStatus PENDING 1000      Delete at 1000 rows/partition/batch (gentler)
 */
public class DeleteByStatus {

    // Default settings
    private static final int DEFAULT_BATCH_SIZE = 2000;  // Rows per partition per call
    private static final int DELETE_DELAY_MS = 10;        // Delay between batches (ms)

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String status = args[0];
        int batchSize = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_BATCH_SIZE;

        // Connect to VoltDB
        Client2Config config = new Client2Config();
        config.outstandingTransactionLimit(500);
        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost", 21212);
        System.out.println("Connected to VoltDB\n");

        try {
            deleteByStatus(client, status, batchSize);
        } finally {
            client.close();
        }
    }

    static void printUsage() {
        System.out.println("VoltDB Delete By Status (DIRECTED Procedure)");
        System.out.println("=============================================");
        System.out.println();
        System.out.println("Usage: java DeleteByStatus <status> [batchSize]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  status     Order status to delete: PENDING, SHIPPED, or DELIVERED");
        System.out.println("  batchSize  Rows to delete per partition per call (default: 2000)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java DeleteByStatus DELIVERED         Delete at 2000 rows/partition");
        System.out.println("  java DeleteByStatus DELIVERED 5000    Delete at 5000 rows/partition");
        System.out.println("  java DeleteByStatus PENDING 1000      Delete at 1000 rows/partition");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - With 4 partitions and batchSize=2000, each batch deletes ~8000 rows");
        System.out.println("  - Smaller batch = lower latency but more round trips");
        System.out.println("  - Larger batch = higher latency but fewer round trips");
    }

    static void deleteByStatus(Client2 client, String status, int batchSize) throws Exception {
        System.out.println("=== DELETE BY STATUS: " + status + " (BATCHED + RATE LIMITED) ===");
        System.out.println();
        System.out.println("Using DIRECTED Java procedure 'DeleteByStatusProc' with CLIENT-SIDE BATCHING");
        System.out.println("Batch size: " + String.format("%,d", batchSize) + " rows per partition per call");
        System.out.println("Inter-batch delay: " + DELETE_DELAY_MS + " ms");
        System.out.println();
        System.out.println("WHY BATCHING + DELAY?");
        System.out.println("  - Each procedure call = one transaction");
        System.out.println("  - Small batches keep individual transaction latency low");
        System.out.println("  - Inter-batch delay allows other transactions to complete");
        System.out.println("  - Goal: Keep cluster latency < 100ms");
        System.out.println();

        // Count before
        long beforeCount = getCountByStatus(client, status);
        System.out.println("Orders with status '" + status + "' BEFORE: " + String.format("%,d", beforeCount));

        if (beforeCount == 0) {
            System.out.println("No orders to delete.");
            return;
        }

        long startTime = System.currentTimeMillis();
        long totalDeleted = 0;
        int batchNum = 0;

        Client2CallOptions options = new Client2CallOptions();

        // CLIENT-SIDE BATCHED DELETE LOOP
        System.out.println("\nStarting batched delete...");
        System.out.println("----------------------------------------");

        while (true) {
            batchNum++;

            // Call DIRECTED procedure on ALL partitions with batch size
            CompletableFuture<ClientResponseWithPartitionKey[]> future =
                client.callAllPartitionProcedureAsync(options, "DeleteByStatusProc", status, batchSize);

            // Wait for this batch to complete
            ClientResponseWithPartitionKey[] results = future.get();

            // Sum deleted counts from all partitions
            long deletedThisBatch = 0;
            for (ClientResponseWithPartitionKey result : results) {
                if (result.response.getStatus() == ClientResponse.SUCCESS) {
                    VoltTable table = result.response.getResults()[0];
                    deletedThisBatch += table.asScalarLong();
                }
            }

            totalDeleted += deletedThisBatch;

            // Progress update every 50 batches
            if (batchNum % 50 == 0 || deletedThisBatch == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double rate = totalDeleted * 1000.0 / Math.max(1, elapsed);
                System.out.printf("  Batch %d: deleted %,d this batch, %,d total (%.0f rows/sec)%n",
                    batchNum, deletedThisBatch, totalDeleted, rate);
            }

            // If no rows deleted, we're done
            if (deletedThisBatch == 0) {
                break;
            }

            // RATE LIMITING: Add delay between batches to reduce cluster pressure
            if (DELETE_DELAY_MS > 0) {
                Thread.sleep(DELETE_DELAY_MS);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Count after to verify
        long afterCount = getCountByStatus(client, status);

        System.out.println("----------------------------------------");
        System.out.println();
        System.out.println("=== DELETE COMPLETE ===");
        System.out.println("Total deleted: " + String.format("%,d", totalDeleted) + " orders");
        System.out.println("Batches: " + batchNum);
        System.out.println("Time: " + String.format("%.2f", elapsed / 1000.0) + " seconds");
        System.out.println("Rate: " + String.format("%,.0f", totalDeleted * 1000.0 / elapsed) + " rows/sec");
        System.out.println("\nOrders with status '" + status + "' AFTER: " + String.format("%,d", afterCount));
    }

    static long getCountByStatus(Client2 client, String status) throws Exception {
        ClientResponse resp = client.callProcedureSync("@AdHoc",
            "SELECT COUNT(*) FROM ORDERS WHERE status = '" + status + "'");
        VoltTable table = resp.getResults()[0];
        table.advanceRow();
        return table.getLong(0);
    }
}
