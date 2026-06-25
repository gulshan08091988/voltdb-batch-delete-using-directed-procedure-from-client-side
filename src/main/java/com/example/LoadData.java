package com.example;

import org.voltdb.client.*;
import org.voltdb.types.TimestampType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * =============================================================================
 * VOLTDB DATA LOADER
 * =============================================================================
 *
 * Loads test data into the ORDERS table with controlled concurrency and rate limiting.
 *
 * FEATURES:
 * ---------
 * - Configurable record count and TPS limit
 * - Flow control to prevent backpressure
 * - Rate limiting to cap throughput
 * - Progress reporting every 100K records
 *
 * USAGE:
 * ------
 *   java LoadData <count> [tps]
 *
 * EXAMPLES:
 * ---------
 *   java LoadData 1000000           Load 1M records at default 80K TPS
 *   java LoadData 50000000 100000   Load 50M records at 100K TPS
 *   java LoadData 50000000 50000    Load 50M records at 50K TPS (gentler)
 */
public class LoadData {

    private static final String[] STATUSES = {"PENDING", "SHIPPED", "DELIVERED"};
    private static final Random random = new Random();

    // Default settings
    private static final int DEFAULT_TPS = 80000;
    private static final int MAX_OUTSTANDING = 200;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            printUsage();
            return;
        }

        int count = Integer.parseInt(args[0]);
        int targetTps = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_TPS;

        // Connect to VoltDB with controlled concurrency
        Client2Config config = new Client2Config();
        config.outstandingTransactionLimit(500);
        Client2 client = ClientFactory.createClient(config);
        client.connectSync("localhost", 21212);
        System.out.println("Connected to VoltDB\n");

        try {
            loadData(client, count, targetTps);
        } finally {
            client.close();
        }
    }

    static void printUsage() {
        System.out.println("VoltDB Data Loader");
        System.out.println("==================");
        System.out.println();
        System.out.println("Usage: java LoadData <count> [tps]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  count   Number of records to load (required)");
        System.out.println("  tps     Target transactions per second (default: 80000)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java LoadData 1000000           Load 1M records at 80K TPS");
        System.out.println("  java LoadData 50000000 100000   Load 50M records at 100K TPS");
        System.out.println("  java LoadData 50000000 50000    Load 50M records at 50K TPS");
    }

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

        // Show final stats
        showStats(client);
    }

    static void showStats(Client2 client) throws Exception {
        System.out.println("\n=== ORDER STATISTICS ===");
        ClientResponse resp = client.callProcedureSync("@AdHoc",
            "SELECT status, COUNT(*) AS cnt FROM ORDERS GROUP BY status ORDER BY status");
        System.out.println(resp.getResults()[0].toFormattedString());
    }
}
