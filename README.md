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
