---
title: Common error messages
linkTitle: Common error messages
headerTitle: Understanding common error messages
description: How to understand and recover from common error messages
menu:
  latest:
    parent: troubleshoot-nodes
    weight: 50
isTocNested: true
showAsideToc: true
---

## Skipping add replicas

When a new node has joined the cluster or an existing node has been removed, you may see errors messages similar to the following:

```output
W1001 10:23:00.969424 22338 cluster_balance.cc:232] Skipping add replicas for 21d0a966e9c048978e35fad3cee31698: 
Operation failed. Try again. Cannot add replicas. Currently have a total overreplication of 1, when max allowed is 1
```

This message is harmless and can be ignored. It means that the maximum number of concurrent tablets being remotely bootstrapped across the  cluster by the YB-Master load balancer has reached its limit. This limit is configured in `--load_balancer_max_concurrent_tablet_remote_bootstraps` in [yb-master config](../../../reference/configuration/yb-master#load-balancer-max-concurrent-tablet-remote-bootstraps).

## SST files limit exceeded

The following error is emitted when the number of SST files has exceeded its limit:

```output
Service unavailable (yb/tserver/tablet_service.cc:257): SST files limit exceeded 58 against (24, 48), score: 0.35422774182913203: 3.854s (tablet server delay 3.854s)
```

Usually, the client is running a high INSERT/UPDATE/DELETE workload and compactions are falling behind. 

To determine why this error is happening, you can check the disk bandwidth, network bandwidth, and find out if enough CPU is available in the server.

The limits are controlled by the following YB-TServer configuration flags: `--sst_files_hard_limit=48` and `--sst_files_soft_limit=24`.

## Catalog Version Mismatch: A DDL occurred while processing this query. Try Again.

When executing queries in the YSQL layer, the query may fail with the following error:

```output
org.postgresql.util.PSQLException: ERROR: Catalog Version Mismatch: A DDL occurred while processing this query. Try Again
```

A DML query in YSQL may touch multiple servers, and each server has a Catalog Version which is used to track schema changes. When a DDL statement runs in the middle of the DML query, the Catalog Version is changed and the query has a mismatch, causing it to fail.

In these cases, the database aborts the query and returns a `40001` PostgreSQL error code. Errors with this code can be safely retried from the client side. 

## ysqlsh: FATAL:  password authentication failed for user "yugabyte" after fresh installation

Sometimes users get the following error when trying to connect to YSQL using `ysqlsh` cli after creating a fresh cluster:

```shell
ysqlsh: FATAL:  password authentication failed for user "yugabyte"
```

By default, PostgreSQL listens on port `5432`. To not conflict with it, we've set the YSQL port to `5433`. But users have the ability to 
create multiple PostgreSQL clusters locally. Each one takes the next port available, starting from `5433`, conflicting with YSQL port. 
If you've created 2 PostgreSQL clusters before creating the YugabyteDB cluster, then the `ysqlsh` shell is trying to connect to PostgreSQL running on port `5433`
and failing to authenticate. To verify in this case, you can look which process is listening on port `5433`:

```shell
sudo lsof -i :5433
COMMAND   PID     USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
postgres 1263 postgres    7u  IPv4  35344      0t0  TCP localhost:postgresql (LISTEN)
```

You have to shut down this PostgreSQL cluster, or kill the process, and then restart YugabyteDB.
