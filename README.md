VoltDB Batch Delete Demo

  This is a VoltDB training/demo project that demonstrates how to use DIRECTED stored procedures for efficient bulk deletion across all partitions in a VoltDB cluster.

  Purpose

  Shows the proper pattern for deleting large amounts of data from VoltDB by a non-partition-key column (like status) without causing cluster latency spikes.

  Key Concepts

  1. DIRECTED Procedures - Execute on ALL partitions simultaneously via callAllPartitionProcedure()
  2. Client-Side Batching - Delete in small batches (2,000 rows per partition) to keep transactions short
  3. Rate Limiting - Add delays between batches to allow other transactions to complete

  Usage

  # Build the project
  cd /Users/gulshansharma/.claude/skills/voltdb-batch-delete
  mvn clean package

  # Run commands (requires a running VoltDB cluster on localhost:21212)
  java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
    com.example.DirectedProcedureDemo <command>

  Available commands:
  ┌─────────────────────────────┬───────────────────────────────────────────────────────┐
  │           Command           │                      Description                      │
  ├─────────────────────────────┼───────────────────────────────────────────────────────┤
  │ load <count> [tps]          │ Load test data (e.g., load 50000000 for 50M rows)     │
  ├─────────────────────────────┼───────────────────────────────────────────────────────┤
  │ delete <status> [batchSize] │ Delete orders by status (e.g., delete DELIVERED 5000) │
  ├─────────────────────────────┼───────────────────────────────────────────────────────┤
  │ count <status>              │ Count orders by status using DIRECTED procedure       │
  ├─────────────────────────────┼───────────────────────────────────────────────────────┤
  │ stats                       │ Show order statistics grouped by status               │
  └─────────────────────────────┴───────────────────────────────────────────────────────┘
  Status values: PENDING, SHIPPED, DELIVERED

  Example Workflow

  # Load 1 million test orders
  java ... DirectedProcedureDemo load 1000000

  # Check statistics
  java ... DirectedProcedureDemo stats

  # Delete all DELIVERED orders in batches
  java ... DirectedProcedureDemo delete DELIVERED

# 1. LOAD DATA
  java -cp "target/classes:target/lib/*" com.example.LoadData <count> [tps]
  java -cp "target/classes:target/lib/*" com.example.LoadData 50000000          # 80K TPS default
  java -cp "target/classes:target/lib/*" com.example.LoadData 50000000 100000   # 100K TPS

  # 2. DELETE BY STATUS (DIRECTED + Client-Side Batching)
  java -cp "target/classes:target/lib/*" com.example.DeleteByStatus <status> [batchSize]
  java -cp "target/classes:target/lib/*" com.example.DeleteByStatus DELIVERED        # 2000 rows/partition
  java -cp "target/classes:target/lib/*" com.example.DeleteByStatus DELIVERED 5000   # 5000 rows/partition

  # 3. COUNT BY STATUS (DIRECTED + Async)
  java -cp "target/classes:target/lib/*" com.example.CountByStatus <status>
  java -cp "target/classes:target/lib/*" com.example.CountByStatus DELIVERED

  # 4. SHOW STATISTICS
  java -cp "target/classes:target/lib/*" com.example.ShowStats
