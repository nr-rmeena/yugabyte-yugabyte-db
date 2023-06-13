---
title: Throughput and latency metrics
headerTitle: Throughput and latency
linkTitle: Throughput+latency metrics
headcontent: Monitor query processing and database IOPS
description: Learn about YugabyteDB's throughput and latency metrics, and how to select and use the metrics.
menu:
  preview:
    identifier: throughput
    parent: metrics-overview
    weight: 100
type: docs
---

As referenced in the earlier section, YugabyteDB has latency metrics for Tables/Tablets in JSON format are available on`<node-ip>:9000/metrics` for Yb-Tserver and port `<node-ip>:7000/metrics` for Yb-Master. Additionally, YugabyteDB supports query processing and connection metrics on port `<node-ip>:13000/metrics` for YSQL and on port `<node-ip>:12000/metrics` for YCQL. The attributes within the latency metrics enable you to calculate throughput.

{{< note title="Note" >}}

Latency metrics at `<node-ip>:13000/metrics`for YSQL query processing and connections have different set of attributes as compared to YCQL query processing metrics and Table/Tablet metrics on both Yb-Master and Yb-Tserver. 

{{< /note >}}

For example, `handler_latency_yb_tserver_TabletServerService_Read` metric to perform READ operation at a tablet level available at `/metrics` endpoint in JSON format, will have this form:

```json
{
  "name": "handler_latency_yb_tserver_TabletServerService_Read",
  "total_count": 14390,
  "min": 104,
  "mean": 171.65,
  "percentile_75": 0,
  "percentile_95": 0,
  "percentile_99": 0,
  "percentile_99_9": 0,
  "percentile_99_99": 0,
  "max": 1655,
  "total_sum": 2470154
}
```

The list of attributes for latency metrics at `<node-ip>:7000/metrics`, `<node-ip>:9000/metrics` and `<node-ip>:12000/metrics` with their description follows:

| Attribute | Description |
| :--- | :--- |
| `total_count` | The number of times the latency of a metric has been measured.
| `min` | The minimum value of the latency across all measurements of this metric.
| `mean` | The average latency across all measurements of this metric.
| `Percentile_75` | The 75th percentile latency across all measurements of this metric
| `Percentile_95` | The 95th percentile latency across all measurements of this metric
| `Percentile_99` | The 99th percentile latency across all measurements of this metric
| `Percentile_99_9` | The 99.9th percentile latency across all measurements of this metric
| `Percentile_99_99` | The 99.99th percentile latency across all measurements of this metric
| `max` | The maximum value of latency across all measurements of this metric.
| `total_sum` | The aggregate latency across all measurements of this metric.

For example, if `SELECT * FROM table` is executed once and returns 8 rows in 10 microseconds, the `handler_latency_yb_ysqlserver_SQLProcessor_SelectStmt` metric would have the following attribute values: `total_count=1`, `total_sum=10`, `min=10`, `max=10`, and `mean=10`. If the same query is run again and returns in 6 microseconds, then the attributes would be as follows: `total_count=2`, `total_sum=16`, `min=6`, `max=10`, and `mean=8`.

Although these attributes are present in all latency metrics, they may not be calculated by YugabyteDB.

Query processing and connection latency metrics for YSQL at `/metrics` endpoint in JSON format, for example, `handler_latency_yb_ysqlserver_SQLProcessor_SelectStmt` - latency metrics to perform READ operation at a tablet level, will have this form:
```json
{
    "name": "handler_latency_yb_ysqlserver_SQLProcessor_SelectStmt",
    "count": 5804,
    "sum": 32777094,
    "rows": 11100
}
```
The list of attributes for latency metrics at `<node-ip>:13000/metrics` with their description follows:

| Attribute | Description |
| `count` | The number of times the latency of a metric has been measured.
| `sum` | The aggregate latency for the metrics across all measurements.
| `rows` | The total number of table rows impacted by the operation.


## YSQL query processing

YSQL query processing metrics represent the total inclusive time it takes YugabyteDB to process a YSQL statement after the query processing layer begins execution. These metrics include the time taken to parse and execute the SQL statement, replicate over the network, the time spent in the storage layer, and so on. The preceding metrics do not capture the time to deserialize the network bytes and parse the query.

The following are key metrics for evaluating YSQL query processing.

| Metric | Unit | Type | Description |
| :--- | :--- | :--- | :--- |
| `handler_latency_yb_ysqlserver_SQLProcessor_InsertStmt` | microseconds | counter | Time to parse and execute INSERT statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_SelectStmt` | microseconds | counter | Time to parse and execute SELECT statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_UpdateStmt` | microseconds | counter | Time to parse and execute UPDATE statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_BeginStmt` | microseconds | counter | Time to parse and execute transaction BEGIN statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_CommitStmt` | microseconds | counter | Time to parse and execute transaction COMMIT statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_RollbackStmt` | microseconds | counter | Time to parse and execute transaction ROLLBACK statement.
| `handler_latency_yb_ysqlserver_SQLProcessor_OtherStmts` | microseconds | counter | Time to parse and execute all other statements apart from the preceding ones listed in this table. Includes statements like PREPARE, RELEASE SAVEPOINT, and so on.
| `handler_latency_yb_ysqlserver_SQLProcessor_Transactions` | microseconds | counter | Time to execute any of the statements in this table.

The YSQL throughput can be viewed as an aggregate across the whole cluster, per table, and per node by applying the appropriate aggregations.

<!-- | Metrics | Unit | Type | Description |
| :------ | :--- | :--- | :---------- |
| `handler_latency_yb_ysqlserver_SQLProcessor_InsertStmt` | microseconds | counter | The time in microseconds to parse and execute INSERT statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_SelectStmt` | microseconds | counter | The time in microseconds to parse and execute SELECT statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_UpdateStmt` | microseconds | counter | The time in microseconds to parse and execute UPDATE statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_BeginStmt` | microseconds | counter | The time in microseconds to parse and execute transaction BEGIN statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_CommitStmt` | microseconds | counter | The time in microseconds to parse and execute transaction COMMIT statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_RollbackStmt` | microseconds | counter | The time in microseconds to parse and execute transaction ROLLBACK statement |
| `handler_latency_yb_ysqlserver_SQLProcessor_OtherStmts` | microseconds | counter | The time in microseconds to parse and execute all other statements apart from the preceding ones listed in this table. This includes statements like PREPARE, RELEASE SAVEPOINT, and so on. |
| `handler_latency_yb_ysqlserver_SQLProcessor_Transactions` | microseconds | counter | The time in microseconds to execute any of the statements in this table.| -->

## YCQL query processing

YCQL query processing metrics represent the total inclusive time it takes YugabyteDB to process a YCQL statement after the query processing layer begins execution. These metrics include the time taken to parse and execute the YCQL statement, replicate over the network (in case of write operations), the time spent in the storage layer, and so on. The preceding metrics do not capture the time to deserialize the network bytes and parse the query.

The following are key metrics for evaluating YCQL query processing.

| Metric | Unit | Type | Description |
| :--- | :--- | :--- | :--- |
| `handler_latency_yb_cqlserver_SQLProcessor_SelectStmt` | microseconds | counter | Time to parse and execute SELECT statement.
| `handler_latency_yb_cqlserver_SQLProcessor_InsertStmt` | microseconds | counter | Time to parse and execute INSERT statement.
| `handler_latency_yb_cqlserver_SQLProcessor_DeleteStmt` | microseconds | counter | Time to parse and execute DELETE statement.
| `handler_latency_yb_cqlserver_SQLProcessor_UpdateStmt` | microseconds | counter | Time to parse and execute UPDATE statement.
| `handler_latency_yb_cqlserver_SQLProcessor_OtherStmts` | microseconds | counter | Time to parse and execute all other statements apart from the preceding ones listed in this table.

## Database IOPS (reads and writes)

The [YB-TServer](../../../../architecture/concepts/yb-tserver/) is responsible for the actual I/O of client requests in a YugabyteDB cluster. Each node in the cluster has a YB-TServer, and each hosts one or more tablet peers.

The following are key metrics for evaluating database IOPS.

| Metric | Unit | Type | Description |
| :--- | :--- | :--- | :--- |
| `handler_latency_yb_tserver_TabletServerService_Read` | microseconds | counter | Time to perform READ operations at a tablet level.
| `handler_latency_yb_tserver_TabletServerService_Write` | microseconds | counter | Time to perform WRITE operations at a tablet level.

<!-- | Metrics | Unit | Type | Description |
| :------ | :--- | :--- | :---------- |
| `handler_latency_yb_tserver_TabletServerService_Read` | microseconds | counter | Time in microseconds to perform WRITE operations at a tablet level |
| `handler_latency_yb_tserver_TabletServerService_Write` | microseconds | counter | Time in microseconds to perform READ operations at a tablet level | -->

These metrics can be viewed as an aggregate across the whole cluster, per table, and per node by applying the appropriate aggregations.
