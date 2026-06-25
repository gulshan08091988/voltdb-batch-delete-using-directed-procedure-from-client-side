package com.example;

import org.voltdb.client.*;

/**
 * =============================================================================
 * SHOW STATISTICS
 * =============================================================================
 *
 * Displays order statistics grouped by status.
 *
 * This is a simple utility that runs an ad-hoc query to show the current
 * distribution of orders across different statuses.
 *
 * USAGE:
 * ------
 *   java ShowStats
 *
 * OUTPUT:
 * -------
 *   Shows a table with status and count columns:
 *
 *   STATUS     | CNT
 *   -----------+----------
 *   DELIVERED  | 16,664,890
 *   PENDING    | 16,669,744
 *   SHIPPED    | 16,665,366
 */
public class ShowStats {

    public static void main(String[] args) throws Exception {
        // Connect to VoltDB
        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync("localhost", 21212);
        System.out.println("Connected to VoltDB\n");

        try {
            showStats(client);
        } finally {
            client.close();
        }
    }

    static void showStats(Client2 client) throws Exception {
        System.out.println("=== ORDER STATISTICS ===");
        System.out.println();

        // Get counts by status
        ClientResponse resp = client.callProcedureSync("@AdHoc",
            "SELECT * from ORDERSTATUS");
        System.out.println(resp.getResults()[0].toFormattedString());

        // Get total count
        ClientResponse totalResp = client.callProcedureSync("@AdHoc",
            "SELECT COUNT(*) AS total FROM ORDERS");
        long total = totalResp.getResults()[0].asScalarLong();
        System.out.println("TOTAL ORDERS: " + String.format("%,d", total));
    }
}
