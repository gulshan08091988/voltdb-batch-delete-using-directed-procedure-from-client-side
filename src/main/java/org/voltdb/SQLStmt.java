package org.voltdb;

/**
 * STUB CLASS FOR COMPILATION ONLY
 *
 * This is a minimal stub of SQLStmt to allow compilation without
 * the full VoltDB server dependency. At runtime, VoltDB server provides
 * the actual implementation.
 *
 * DO NOT deploy this stub to VoltDB - only deploy the procedure classes.
 */
public class SQLStmt {

    private final String sql;

    /**
     * Create a SQL statement
     */
    public SQLStmt(String sql) {
        this.sql = sql;
    }

    public String getText() {
        return sql;
    }
}
