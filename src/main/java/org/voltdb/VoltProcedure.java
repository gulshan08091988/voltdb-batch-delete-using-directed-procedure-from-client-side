package org.voltdb;

/**
 * STUB CLASS FOR COMPILATION ONLY
 *
 * This is a minimal stub of VoltProcedure to allow compilation without
 * the full VoltDB server dependency. At runtime, VoltDB server provides
 * the actual implementation.
 *
 * DO NOT deploy this stub to VoltDB - only deploy the procedure classes.
 */
public class VoltProcedure {

    /**
     * Abort exception for procedures
     */
    public static class VoltAbortException extends RuntimeException {
        public VoltAbortException(String message) {
            super(message);
        }
    }

    /**
     * Queue a SQL statement for execution
     */
    protected void voltQueueSQL(SQLStmt stmt, Object... args) {
        // Stub - actual implementation provided by VoltDB at runtime
    }

    /**
     * Execute all queued SQL statements
     */
    protected VoltTable[] voltExecuteSQL() {
        // Stub - actual implementation provided by VoltDB at runtime
        return new VoltTable[0];
    }

    /**
     * Execute all queued SQL statements (final batch)
     */
    protected VoltTable[] voltExecuteSQL(boolean isFinal) {
        // Stub - actual implementation provided by VoltDB at runtime
        return new VoltTable[0];
    }
}
