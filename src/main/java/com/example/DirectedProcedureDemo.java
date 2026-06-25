package com.example;

import org.voltdb.VoltTable;
import org.voltdb.client.*;
import org.voltdb.types.TimestampType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * =============================================================================
 * VOLTDB DIRECTED PROCEDURE DEMO
 * =============================================================================
 *
 * This demo shows how to use DIRECTED stored procedures to execute operations
 * across ALL partitions in a VoltDB cluster.
 *
 * WHAT IS A DIRECTED PROCEDURE?
 * -----------------------------
 * - A procedure marked with the DIRECTED keyword in DDL
 * - Executes on ALL partitions via callAllPartitionProcedure()
 * - Each partition processes its local data independently
 * - Results are returned as an array (one result per partition)
 *
 * USE CASES:
 * ----------
 * - Bulk delete by non-partition-key column (e.g., status, date)
 * - Aggregate counts across all partitions
 * - Data cleanup/archival operations
 *
 * USAGE:
 * ------
 *   java DirectedProcedureDemo load <count>           - Load test data
 *   java DirectedProcedureDemo delete <status>        - Delete by status
 *   java DirectedProcedureDemo count <status>         - Count by status
 *   java DirectedProcedureDemo stats                  - Show statistics
 */
public class DirectedProcedureDemo {

    private static final String[] STATUSES = {"PENDING", "SHIPPED", "DELIVERED"};
    private static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        // Connect to VoltDB with controlled concurrency
        Client2Config config = new Client2Config();
        config.outstandingTransactionLimit(500);  // Low limit to prevent backpressure/latency spikes
        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost", 21212);
        System.out.println("Connected to VoltDB (max outstanding: 500)\n");

        try {
            String command = args[0];
            switch (command) {
                case "load":
                    int count = args.length > 1 ? Integer.parseInt(args[1]) : 1000000;
                    int tps = args.length > 2 ? Integer.parseInt(args[2]) : MAX_LOAD_TPS;
                    loadData(client, count, tps);
                    break;

                case "delete":
                    if (args.length < 2) {
                        System.out.println("Usage: delete <status> [batchSize]");
                        return;
                    }
                    int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : DELETE_BATCH_SIZE;
                    deleteByStatus(client, args[1], batchSize);
                    break;

                case "count":
                    if (args.length < 2) {
                        System.out.println("Usage: count <status>");
                        return;
                    }
                    countByStatus(client, args[1]);
                    break;

                case "stats":
                    showStats(client);
                    break;

                default:
                    printUsage();
            }
        } finally {
            client.close();
        }
    }

    static void printUsage() {
        System.out.println("VoltDB Directed Procedure Demo");
        System.out.println("==============================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  load <count> [tps]          Load test data");
        System.out.println("  delete <status> [batchSize] Delete orders by status using DIRECTED procedure");
        System.out.println("  count <status>              Count orders by status using DIRECTED procedure");
        System.out.println("  stats                       Show order statistics");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  load 50000000               Load 50M records at default 80K TPS");
        System.out.println("  load 50000000 100000        Load 50M records at 100K TPS");
        System.out.println("  delete DELIVERED            Delete DELIVERED orders (2000 rows/partition/batch)");
        System.out.println("  delete DELIVERED 5000       Delete DELIVERED orders (5000 rows/partition/batch)");
        System.out.println();
        System.out.println("Status values: PENDING, SHIPPED, DELIVERED");
    }

    // =========================================================================
    // LOAD DATA - Controlled concurrency with backpressure
    // =========================================================================
    private static final int MAX_LOAD_TPS = 80000;       // Target max TPS
    private static final int MAX_OUTSTANDING = 200;       // Max concurrent requests (low to prevent backpressure)
    private static final int BATCH_WAIT_SIZE = 100;       // Wait for batch after this many requests

    static void loadData(Client2 client, int count, int targetTps) throws Exception {
        System.out.println("=== LOADING DATA (CONTROLLED CONCURRENCY) ===");
        System.out.println("Orders to load: " + String.format("%,d", count));
        System.out.println("Target TPS: " + String.format("%,d", targetTps));
        System.out.println("Max outstanding: " + MAX_OUTSTANDING);
        System.out.println();

        // Clear existing data first
        System.out.println("Clearing existing data...");
        client.callProcedureSync("@AdHoc", "DELETE FROM ORDERS");

        AtomicLong inserted = new AtomicLong(0);
        AtomicLong failed = new AtomicLong(0);
        List<CompletableFuture<ClientResponse>> futures = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        long windowStart = startTime;
        int windowCount = 0;

        for (int i = 1; i <= count; i++) {
            long orderId = i;
            long customerId = random.nextInt(10000) + 1;
            double amount = 10 + random.nextDouble() * 500;
            String status = STATUSES[random.nextInt(STATUSES.length)];
            TimestampType orderDate = new TimestampType(System.currentTimeMillis() * 1000);

            // InsertOrder: order_id, customer_id, amount, status, order_date
            CompletableFuture<ClientResponse> future = client.callProcedureAsync(
                "InsertOrder", orderId, customerId, amount, status, orderDate);

            future.whenComplete((resp, err) -> {
                if (err == null && resp.getStatus() == ClientResponse.SUCCESS) {
                    inserted.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
            });
            futures.add(future);
            windowCount++;

            // FLOW CONTROL: Wait for batch to complete when we hit limit
            if (futures.size() >= MAX_OUTSTANDING) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                futures.clear();

                // RATE LIMITING: Sleep if going too fast
                long elapsed = System.currentTimeMillis() - windowStart;
                if (elapsed > 0) {
                    double currentRate = windowCount * 1000.0 / elapsed;
                    if (currentRate > targetTps) {
                        long targetTime = (long) (windowCount * 1000.0 / targetTps);
                        long sleepMs = targetTime - elapsed;
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    }
                }

                // Reset window every second
                if (System.currentTimeMillis() - windowStart >= 1000) {
                    windowStart = System.currentTimeMillis();
                    windowCount = 0;
                }
            }

            // Progress report
            if (i % 100000 == 0) {
                long totalElapsed = System.currentTimeMillis() - startTime;
                double rate = inserted.get() * 1000.0 / Math.max(1, totalElapsed);
                System.out.printf("Progress: %,d / %,d (%.1f%%) - %.0f TPS%n",
                    i, count, (double) i / count * 100, rate);
            }
        }

        // Wait for remaining
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println();
        System.out.println("=== LOAD COMPLETE ===");
        System.out.println("Inserted: " + String.format("%,d", inserted.get()));
        if (failed.get() > 0) {
            System.out.println("Failed: " + String.format("%,d", failed.get()));
        }
        System.out.println("Time: " + String.format("%.2f", elapsed / 1000.0) + " seconds");
        System.out.println("Rate: " + String.format("%,.0f", inserted.get() * 1000.0 / elapsed) + " rows/sec");

        showStats(client);
    }

    // =========================================================================
    // DELETE BY STATUS - Using DIRECTED Java Procedure with CLIENT-SIDE BATCHING
    // =========================================================================
    // BATCH_SIZE controls how many rows are deleted per procedure call per partition.
    // Smaller batch = lower latency but more round trips
    // Larger batch = higher latency but fewer round trips
    private static final int DELETE_BATCH_SIZE = 2000;  // Reduced from 10000 to keep latency < 100ms
    private static final int DELETE_DELAY_MS = 10;       // Delay between batches (ms)

    static void deleteByStatus(Client2 client, String status, int batchSize) throws Exception {
        System.out.println("=== DELETE BY STATUS: " + status + " (BATCHED + RATE LIMITED) ===");
        System.out.println();
        System.out.println("Using DIRECTED Java procedure 'DeleteByStatusProc' with CLIENT-SIDE BATCHING");
        System.out.println("Batch size: " + String.format("%,d", batchSize) + " rows per partition per call");
        System.out.println("Inter-batch delay: " + DELETE_DELAY_MS + " ms");
        System.out.println();
        System.out.println("WHY BATCHING + DELAY?");
        System.out.println("  - Each procedure call = one transaction");
        System.out.println("  - Small batches (2000) keep individual transaction latency low");
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

            // Progress update every 50 batches (more frequent with smaller batches)
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

    // =========================================================================
    // COUNT BY STATUS - Using DIRECTED Procedure (ASYNC)
    // =========================================================================
    static void countByStatus(Client2 client, String status) throws Exception {
        System.out.println("=== COUNT BY STATUS: " + status + " (ASYNC) ===");
        System.out.println();
        System.out.println("Using DIRECTED procedure 'CountByStatus' with ASYNC call");
        System.out.println("Each partition returns its local count.");
        System.out.println();

        // ASYNC call
        Client2CallOptions options = new Client2CallOptions();
        CompletableFuture<ClientResponseWithPartitionKey[]> future =
            client.callAllPartitionProcedureAsync(options, "CountByStatus", status);

        System.out.println("Async call submitted. Waiting for results...\n");

        // Wait for completion
        ClientResponseWithPartitionKey[] results = future.get();

        System.out.println("Counts from " + results.length + " partitions:");
        System.out.println("----------------------------------------");

        long total = 0;
        for (int i = 0; i < results.length; i++) {
            VoltTable table = results[i].response.getResults()[0];
            if (table.advanceRow()) {
                long count = table.getLong("cnt");
                total += count;
                System.out.println("  Partition " + i + ": " + String.format("%,d", count));
            }
        }

        System.out.println("----------------------------------------");
        System.out.println("  TOTAL: " + String.format("%,d", total));
    }

    // =========================================================================
    // SHOW STATISTICS
    // =========================================================================
    static void showStats(Client2 client) throws Exception {
        System.out.println("\n=== ORDER STATISTICS ===");
        ClientResponse resp = client.callProcedureSync("@AdHoc",
            "SELECT status, COUNT(*) AS cnt FROM ORDERS GROUP BY status ORDER BY status");
        System.out.println(resp.getResults()[0].toFormattedString());
    }

    static long getCountByStatus(Client2 client, String status) throws Exception {
        ClientResponse resp = client.callProcedureSync("@AdHoc",
            "SELECT COUNT(*) FROM ORDERS WHERE status = '" + status + "'");
        VoltTable table = resp.getResults()[0];
        table.advanceRow();
        return table.getLong(0);
    }
}
