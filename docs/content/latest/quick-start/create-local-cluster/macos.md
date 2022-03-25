---
title: Create a local YugabyteDB cluster on macOS
headerTitle: 2. Create a local cluster
linkTitle: 2. Create a local cluster
description: Create a local cluster on macOS in less than five minutes.
aliases:
  - /quick-start/create-local-cluster/
  - /latest/quick-start/create-local-cluster/
menu:
  latest:
    parent: quick-start
    name: 2. Create a local cluster
    identifier: create-local-cluster-1-macos
    weight: 120
type: page
isTocNested: true
showAsideToc: true
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li >
    <a href="../macos/" class="nav-link active">
      <i class="fab fa-apple" aria-hidden="true"></i>
      macOS
    </a>
  </li>

  <li >
    <a href="../linux/" class="nav-link">
      <i class="fab fa-linux" aria-hidden="true"></i>
      Linux
    </a>
  </li>

  <li >
    <a href="../docker/" class="nav-link">
      <i class="fab fa-docker" aria-hidden="true"></i>
      Docker
    </a>
  </li>

  <li >
    <a href="../kubernetes/" class="nav-link">
      <i class="fas fa-cubes" aria-hidden="true"></i>
      Kubernetes
    </a>
  </li>

</ul>

## Create a local cluster

To create a single-node local cluster with a replication factor (RF) of 1, run the following command.

```sh
$ ./bin/yugabyted start
```

{{<note title="Note for macOS Monterey" >}}

macOS Monterey turns on AirPlay receiving by default, which listens on port 7000. This conflicts with YugabyteDB and causes `yugabyted start` to fail. The workaround is to turn AirPlay receiving off, then start YugabyteDB, and then (optionally) turn AirPlay receiving back on. Alternatively(recommended), you can change the default port number using the `--master_webserver_port` flag when you start the cluster as follows:

```sh
$ ./bin/yugabyted start --master_webserver_port=9999
```

{{< /note >}}

After the cluster is created, clients can connect to the YSQL and YCQL APIs at `localhost:5433` and `localhost:9042` respectively. You can also check `~/var/data` to see the data directory and `~/var/logs` to see the logs directory.

{{< tip title="Tip" >}}

If you have previously installed YugabyteDB (2.8 or later) and created a cluster on the same computer, you may need to [upgrade the YSQL system catalog](../../../manage/upgrade-deployment/#upgrade-the-ysql-system-catalog) to run the latest features.

{{< /tip >}}

## Check cluster status

```sh
$ ./bin/yugabyted status
```

```output
+--------------------------------------------------------------------------------------------------+
|                                            yugabyted                                             |
+--------------------------------------------------------------------------------------------------+
| Status              : Running. Leader Master is present                                          |
| Web console         : http://127.0.0.1:7000                                                      |
| JDBC                : jdbc:postgresql://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte  |
| YSQL                : bin/ysqlsh   -U yugabyte -d yugabyte                                       |
| YCQL                : bin/ycqlsh   -u cassandra                                                  |
| Data Dir            : /Users/myuser/var/data                                                     |
| Log Dir             : /Users/myuser/var/logs                                                     |
| Universe UUID       : fad6c687-e1dc-4dfd-af4b-380021e19be3                                       |
+--------------------------------------------------------------------------------------------------+
```

## Check cluster status with Admin UI

Under the hood, the cluster you have just created consists of two processes: [YB-Master](../../../architecture/concepts/yb-master/) which keeps track of various metadata (list of tables, users, roles, permissions, and so on), and [YB-TServer](../../../architecture/concepts/yb-tserver/) which is responsible for the actual end user requests for data updates and queries.

Each of the processes exposes its own Admin UI that can be used to check the status of the corresponding process, and perform certain administrative operations. The [YB-Master Admin UI](../../../reference/configuration/yb-master/#admin-ui) is available at [http://127.0.0.1:7000](http://127.0.0.1:7000) (replace the port number if you've started `yugabyted` with the `--master_webserver_port` flag), and the [YB-TServer Admin UI](../../../reference/configuration/yb-tserver/#admin-ui) is available at [http://127.0.0.1:9000](http://127.0.0.1:9000).

### Overview and YB-Master status

The YB-Master home page shows that you have a cluster (or universe) with a replication factor of 1, a single node, and no tables. The YugabyteDB version is also displayed.

![master-home](/images/admin/master-home-binary-rf1.png)

The **Masters** section highlights the 1 YB-Master along with its corresponding cloud, region, and zone placement.

### YB-TServer status

Click **See all nodes** to go to the **Tablet Servers** page, which lists the YB-TServer along with the time since it last connected to the YB-Master using regular heartbeats. Because there are no user tables, User Tablet-Peers / Leaders is 0. As tables are added, new tablets (aka shards) will be created automatically and distributed evenly across all the available tablet servers.

![master-home](/images/admin/master-tservers-list-binary-rf1.png)

## Next step

[Explore YSQL](../../explore/ysql/)
