# VoltDB Directed Procedure Demo

This demonstrating how to use **DIRECTED stored procedures** for efficient bulk operations across all partitions in a VoltDB cluster.

## Overview

This demo shows the proper pattern for deleting large amounts of data from VoltDB by a non-partition-key column (like `status`) without causing cluster latency spikes.

### What is a DIRECTED Procedure?

- A procedure marked with the `DIRECTED` keyword in DDL
- Executes on **ALL partitions** via `callAllPartitionProcedure()`
- Each partition processes its local data independently
- Results are returned as an array (one result per partition)

### Use Cases

- Bulk delete by non-partition-key column (e.g., status, date)
- Aggregate counts across all partitions
- Data cleanup/archival operations

## Key Concepts

### Client-Side Batching

Each VoltDB procedure invocation equals one transaction. To keep cluster latency low:

1. Delete a **limited number of rows** per call (e.g., 2,000 per partition)
2. Client calls the procedure **repeatedly** until all rows are deleted
3. Between calls, other transactions can execute
4. Cluster latency stays low (< 100ms per batch)

### Rate Limiting

Add delays between batches to reduce cluster pressure and allow other workloads to proceed.

## Prerequisites

- Java 17+
- Maven 3.6+
- Running VoltDB cluster (localhost:21212)

## Building

```bash
cd voltdb-batch-delete-using-directed-procedure-from-client-side
cd 
mvn clean package
```

This creates:
- `target/directed-procedure-demo-1.0-SNAPSHOT.jar` - Main application JAR
- `target/directed-procedure-demo-1.0-SNAPSHOT-procedures.jar` - Procedures JAR for VoltDB deployment

## Schema Setup

Before running, deploy the schema and procedures to VoltDB:

```sql
file -inlinebatch END_OF_BATCH
CREATE TABLE ORDERS (
   ORDER_ID bigint NOT NULL,
   CUSTOMER_ID bigint NOT NULL,
   AMOUNT decimal NOT NULL,
   STATUS varchar(20) NOT NULL,
   ORDER_DATE timestamp DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (ORDER_ID, CUSTOMER_ID)
);
PARTITION TABLE ORDERS ON COLUMN CUSTOMER_ID;
CREATE INDEX IDX_ORDERS_STATUS_ORDER ON ORDERS (STATUS, ORDER_ID, CUSTOMER_ID);

CREATE VIEW ORDERSTATUS (
   STATUS,
   CNT
)  AS SELECT STATUS,COUNT(*) FROM ORDERS GROUP BY STATUS;

CREATE DIRECTED PROCEDURE CountByStatus
   AS
BEGIN
   SELECT COUNT(*) AS cnt FROM ORDERS WHERE status = ?;
END;

CREATE DIRECTED PROCEDURE 
   FROM CLASS com.example.procedures.DeleteByStatusProc;

CREATE PROCEDURE InsertOrder
   PARTITION ON TABLE ORDERS COLUMN CUSTOMER_ID PARAMETER 1
   AS
BEGIN
   INSERT INTO ORDERS VALUES (?, ?, ?, ?, ?);
END;

END_OF_BATCH
```

Load the procedures JAR:
```bash
sqlcmd
> load classes target/directed-procedure-demo-1.0-SNAPSHOT-procedures.jar;
```

## Usage

```bash
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo <command> [options]
```

### Commands

| Command | Description |
|---------|-------------|
| `load <count> [tps]` | Load test data with optional TPS limit |
| `delete <status> [batchSize]` | Delete orders by status using DIRECTED procedure |
| `count <status>` | Count orders by status using DIRECTED procedure |
| `stats` | Show order statistics grouped by status |

### Status Values

- `PENDING`
- `SHIPPED`
- `DELIVERED`

## Examples

### Load Test Data

```bash
# Load 1 million orders at default 80K TPS
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo load 1000000

# Load 50 million orders at 100K TPS
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo load 50000000 100000
```

### Show Statistics

```bash
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo stats
```

### Count by Status

```bash
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo count DELIVERED
```

### Delete by Status

```bash
# Delete DELIVERED orders with default batch size (2000 rows/partition)
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo delete DELIVERED

# Delete with custom batch size (5000 rows/partition)
java -cp target/directed-procedure-demo-1.0-SNAPSHOT.jar:target/lib/* \
  com.example.DirectedProcedureDemo delete DELIVERED 5000
```

## Configuration

### Load Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `MAX_LOAD_TPS` | 80,000 | Target transactions per second |
| `MAX_OUTSTANDING` | 200 | Max concurrent requests |

### Delete Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `DELETE_BATCH_SIZE` | 2,000 | Rows deleted per partition per call |
| `DELETE_DELAY_MS` | 10 | Delay between batches (ms) |

## Project Structure

```
voltdb-batch-delete/
├── pom.xml
├── README.md
└── src/main/java/com/example/
    ├── DirectedProcedureDemo.java    # Main application
    └── procedures/
        └── DeleteByStatusProc.java   # DIRECTED stored procedure
```

## How It Works

### Delete Flow

1. Client calls `callAllPartitionProcedure("DeleteByStatusProc", status, batchSize)`
2. VoltDB executes the procedure on ALL partitions simultaneously
3. Each partition deletes up to `batchSize` rows matching the status
4. Client receives results from all partitions (deleted count per partition)
5. If any partition deleted rows, repeat from step 1
6. When all partitions return 0, deletion is complete

### Why Not Loop Inside the Procedure?

In VoltDB, each procedure invocation is one transaction. Looping inside the procedure:
- Creates one long-running transaction
- Blocks other transactions on affected partitions
- Can cause latency spikes cluster-wide

Client-side batching keeps each transaction short, allowing other workloads to interleave.
