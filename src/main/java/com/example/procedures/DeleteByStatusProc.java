package com.example.procedures;

import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;

/**
 * =============================================================================
 * DIRECTED STORED PROCEDURE: DeleteByStatusProc (SINGLE BATCH)
 * =============================================================================
 *
 * This procedure deletes ONE BATCH of rows and returns immediately.
 * The CLIENT must call this procedure repeatedly until all rows are deleted.
 *
 * WHY SINGLE BATCH?
 * -----------------
 * In VoltDB, each procedure invocation = one transaction.
 * Looping inside the procedure doesn't help - it's still one long transaction.
 *
 * To keep cluster latency low:
 * 1. This procedure deletes a LIMITED number of rows (e.g., 10,000)
 * 2. Client calls this procedure repeatedly
 * 3. Between calls, other transactions can execute
 * 4. Cluster latency stays low (< 50ms per batch)
 *
 * USAGE:
 * ------
 *   // Client-side batched delete loop:
 *   while (true) {
 *       results = client.callAllPartitionProcedure("DeleteByStatusProc", "DELIVERED", 10000);
 *       // Sum deleted counts from all partitions
 *       // If total deleted == 0, we're done
 *   }
 */
public class DeleteByStatusProc extends VoltProcedure {

    // Delete with LIMIT for batching - each call deletes at most batchSize rows
    // ORDER BY with full primary key required by VoltDB for deterministic results
    public final SQLStmt deleteBatch = new SQLStmt(
        "DELETE FROM ORDERS WHERE status = ? ORDER BY order_id, customer_id LIMIT ?;"
    );

    /**
     * Delete ONE BATCH of rows matching the status.
     *
     * @param status    The order status to delete (e.g., "DELIVERED")
     * @param batchSize Maximum rows to delete in this call (e.g., 10000)
     * @return VoltTable with count of deleted rows in this batch
     */
    public VoltTable[] run(String status, int batchSize) throws VoltAbortException {

        // Validate input
        if (status == null || status.trim().isEmpty()) {
            throw new VoltAbortException("Status parameter cannot be null or empty");
        }

        if (batchSize <= 0 || batchSize > 100000) {
            batchSize = 10000; // Default/safe batch size
        }

        // Delete ONE batch only - returns immediately
        voltQueueSQL(deleteBatch, status, batchSize);

        // Execute and return - this commits the transaction
        return voltExecuteSQL(true);
    }
}
