package com.example;

import org.voltdb.VoltTable;
import org.voltdb.client.*;

import java.util.concurrent.CompletableFuture;

/**
 * =============================================================================
 * COUNT BY STATUS - DIRECTED Procedure (ASYNC)
 * =============================================================================
 *
 * Counts orders by status using a DIRECTED stored procedure that executes
 * on ALL partitions simultaneously and returns results asynchronously.
 *
 * KEY CONCEPTS:
 * -------------
 * 1. DIRECTED Procedure: Executes on ALL partitions via callAllPartitionProcedure()
 * 2. Async Execution: Uses CompletableFuture for non-blocking call
 * 3. Result Aggregation: Each partition returns its local count, client sums them
 *
 * HOW IT WORKS:
 * -------------
 * 1. Client calls callAllPartitionProcedureAsync("CountByStatus", status)
 * 2. VoltDB routes the call to ALL partitions in parallel
 * 3. Each partition executes: SELECT COUNT(*) FROM ORDERS WHERE status = ?
 * 4. Results are returned as an array (one per partition)
 * 5. Client aggregates the counts
 *
 * USAGE:
 * ------
 *   java CountByStatus <status>
 *
 * EXAMPLES:
 * ---------
 *   java CountByStatus DELIVERED
 *   java CountByStatus PENDING
 *   java CountByStatus SHIPPED
 */
public class CountByStatus {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String status = args[0];

        // Connect to VoltDB
        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync("localhost", 21212);
        System.out.println("Connected to VoltDB\n");

        try {
            countByStatus(client, status);
        } finally {
            client.close();
        }
    }

    static void printUsage() {
        System.out.println("VoltDB Count By Status (DIRECTED Procedure - ASYNC)");
        System.out.println("====================================================");
        System.out.println();
        System.out.println("Usage: java CountByStatus <status>");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  status   Order status to count: PENDING, SHIPPED, or DELIVERED");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java CountByStatus DELIVERED");
        System.out.println("  java CountByStatus PENDING");
        System.out.println("  java CountByStatus SHIPPED");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - Uses DIRECTED procedure that runs on ALL partitions");
        System.out.println("  - Each partition returns its local count");
        System.out.println("  - Client aggregates the results");
    }

    static void countByStatus(Client2 client, String status) throws Exception {
        System.out.println("=== COUNT BY STATUS: " + status + " (ASYNC) ===");
        System.out.println();
        System.out.println("Using DIRECTED procedure 'CountByStatus' with ASYNC call");
        System.out.println("Each partition returns its local count.");
        System.out.println();

        long startTime = System.currentTimeMillis();

        // ASYNC call to ALL partitions
        Client2CallOptions options = new Client2CallOptions();
        CompletableFuture<ClientResponseWithPartitionKey[]> future =
            client.callAllPartitionProcedureAsync(options, "CountByStatus", status);

        System.out.println("Async call submitted. Waiting for results...\n");

        // Wait for completion
        ClientResponseWithPartitionKey[] results = future.get();

        long elapsed = System.currentTimeMillis() - startTime;

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
        System.out.println();
        System.out.println("Time: " + elapsed + " ms");
    }
}
