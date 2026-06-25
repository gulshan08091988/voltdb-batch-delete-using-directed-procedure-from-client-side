package com.example;

import org.voltdb.client.*;

import java.io.File;

/**
 * Deploy the schema for Directed Procedure Demo.
 * Run this once before using the demo.
 *
 * This version deploys DeleteByStatusProc as a JAVA stored procedure.
 */
public class DeploySchema {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 21212;

        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(host, port);
        System.out.println("Connected to VoltDB\n");

        try {
            System.out.println("Deploying schema for Directed Procedure Demo...\n");

            // Drop existing demo procedures
            System.out.println("Dropping existing procedures...");
            execute(client, "DROP PROCEDURE DeleteByStatusProc IF EXISTS");
            execute(client, "DROP PROCEDURE DeleteByStatus IF EXISTS");
            execute(client, "DROP PROCEDURE CountByStatus IF EXISTS");
            execute(client, "DROP PROCEDURE InsertOrder IF EXISTS");

            // Drop and recreate ORDERS table for clean state
            System.out.println("Dropping ORDERS table if exists...");
            execute(client, "DROP TABLE ORDERS IF EXISTS");

            System.out.println("Creating ORDERS table...");
            client.callProcedureSync("@AdHoc",
                "CREATE TABLE ORDERS (" +
                "order_id BIGINT NOT NULL, " +
                "customer_id BIGINT NOT NULL, " +
                "amount DECIMAL NOT NULL, " +
                "status VARCHAR(20) NOT NULL, " +
                "order_date TIMESTAMP DEFAULT NOW, " +
                "PRIMARY KEY (order_id, customer_id))");

            client.callProcedureSync("@AdHoc",
                "PARTITION TABLE ORDERS ON COLUMN customer_id");

            // Create InsertOrder procedure
            System.out.println("Creating InsertOrder procedure...");
            client.callProcedureSync("@AdHoc",
                "CREATE PROCEDURE InsertOrder " +
                "PARTITION ON TABLE ORDERS COLUMN customer_id PARAMETER 1 " +
                "AS INSERT INTO ORDERS VALUES (?, ?, ?, ?, ?)");

            // =========================================================
            // DEPLOY JAVA STORED PROCEDURE (DIRECTED)
            // =========================================================
            System.out.println("\n--- Deploying Java Stored Procedure ---");

            // Find the procedures JAR
            String jarPath = findProceduresJar();
            if (jarPath == null) {
                System.out.println("ERROR: procedures JAR not found!");
                System.out.println("Please build the project first: mvn clean package");
                return;
            }

            System.out.println("Loading JAR: " + jarPath);

            // Load the JAR into VoltDB
            ClientResponse loadResp = client.callProcedureSync("@UpdateClasses",
                readFileBytes(jarPath), null);

            if (loadResp.getStatus() != ClientResponse.SUCCESS) {
                System.out.println("ERROR loading JAR: " + loadResp.getStatusString());
                return;
            }
            System.out.println("JAR loaded successfully");

            // Create the DIRECTED procedure from the Java class
            System.out.println("Creating DeleteByStatusProc procedure (DIRECTED, Java)...");
            client.callProcedureSync("@AdHoc",
                "CREATE PROCEDURE DIRECTED FROM CLASS com.example.procedures.DeleteByStatusProc");

            // Create DDL-based CountByStatus (for comparison)
            System.out.println("Creating CountByStatus procedure (DIRECTED, DDL)...");
            client.callProcedureSync("@AdHoc",
                "CREATE PROCEDURE CountByStatus DIRECTED " +
                "AS SELECT COUNT(*) AS cnt FROM ORDERS WHERE status = ?");

            System.out.println("\n=== SCHEMA DEPLOYED SUCCESSFULLY ===");
            System.out.println("\nProcedures deployed:");
            System.out.println("  - InsertOrder        (Single-Partition, DDL)");
            System.out.println("  - DeleteByStatusProc (DIRECTED, Java) <-- NEW!");
            System.out.println("  - CountByStatus      (DIRECTED, DDL)");
            System.out.println("\nRun the demo:");
            System.out.println("  java DirectedProcedureDemo load 1000000");
            System.out.println("  java DirectedProcedureDemo delete DELIVERED");

        } finally {
            client.close();
        }
    }

    static String findProceduresJar() {
        // Look for the procedures JAR in target directory
        // Prefer the -procedures JAR which contains only procedure classes
        String[] possiblePaths = {
            "target/directed-procedure-demo-1.0-SNAPSHOT-procedures.jar",
            "target/directed-procedure-demo-1.0-SNAPSHOT.jar"
        };

        for (String path : possiblePaths) {
            File f = new File(path);
            if (f.exists()) {
                System.out.println("Found JAR: " + path);
                return path;
            }
        }

        // Try to find any JAR in target
        File targetDir = new File("target");
        if (targetDir.exists() && targetDir.isDirectory()) {
            // First look for procedures JAR
            File[] procFiles = targetDir.listFiles((dir, name) ->
                name.contains("-procedures") && name.endsWith(".jar"));
            if (procFiles != null && procFiles.length > 0) {
                return procFiles[0].getPath();
            }

            // Fall back to main JAR
            File[] files = targetDir.listFiles((dir, name) ->
                name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc"));
            if (files != null && files.length > 0) {
                return files[0].getPath();
            }
        }

        return null;
    }

    static byte[] readFileBytes(String path) throws Exception {
        return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
    }

    static void execute(Client2 client, String sql) {
        try {
            client.callProcedureSync("@AdHoc", sql);
        } catch (Exception e) {
            // Ignore
        }
    }
}
