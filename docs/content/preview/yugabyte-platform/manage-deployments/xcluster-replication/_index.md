---
title: Configure xCluster replication for a YugabyteDB Anywhere universe
headerTitle: xCluster Replication
linkTitle: xCluster Replication
description: Enable xCluster replication between universes
headContent: Active universe with standby using xCluster deployment
menu:
  preview_yugabyte-platform:
    parent: manage-deployments
    identifier: xcluster-replication
    weight: 90
aliases:
  - /preview/yugabyte-platform/create-deployments/async-replication-platform
type: indexpage
showRightNav: true
---

xCluster replication is an asynchronous replication feature in YugabyteDB that allows you to replicate data between independent YugabyteDB universes. You can set up unidirectional (master-follower) or bidirectional (multi-master) replication between two data centers:

- Source - contains the original data that is subject to replication.
- Target - recipient of the replicated data.

![xCluster asynchronous replication](/images/architecture/replication/active-standby-deployment-new.png)

You can use xCluster replication to implement disaster recovery for YugabyteDB. This is a good option where you have only two regions available, or the higher write latency of a [global database](../../../develop/build-global-apps/global-database/) is a problem. You do need to tolerate some small possibility of data loss due to asynchronous replication. For more details on using xCluster for disaster recovery, see [xCluster Disaster Recovery](../../back-up-restore-universes/disaster-recovery/).

xCluster replication can be used to move data from one YugabyteDB universe to another for purposes other than disaster recovery. For example, downstream YugabyteDB universes used for reporting or "green" deployments of blue-green deployments can be kept asynchronously up to date with the main YugabyteDB universe.

You can use YugabyteDB Anywhere to set up xCluster replication, monitor the status of replication, and manage changes to the replication when new databases or tables are added to the replication.

- For more information on how YugabyteDB xCluster replication works, see [xCluster replication: overview and architecture](../../../architecture/docdb-replication/async-replication/).
- For an example of unidirectional (master-follower) xCluster replication, see [Active-active single-master](../../../develop/build-global-apps/active-active-single-master/).
- For an example of bidirectional (multi-master) xCluster replication, see [Active-active multi-master](../../../develop/build-global-apps/active-active-multi-master/).

## xCluster configurations

xCluster Replication supports the following replication configurations:

- Transactional YSQL
- Non-transactional YCQL/YSQL
- Non-transactional bidirectional

For YSQL databases, transactional is recommended. This mode guarantees atomicity and consistency of transactions. The target universe is made read-only in this mode. If the target universe needs to support write operations, YSQL replication can be configured to use the non-transactional mode. However, this comes at the expense of SQL ACID guarantees. For more information on the inconsistencies that can arise with non-transactional YSQL, refer to [Inconsistencies affecting transactions](../../../architecture/docdb-replication/async-replication/#inconsistencies-affecting-transactions).

For YCQL databases, only non-transactional replication is supported.

Bidirectional replication refers to setting up xCluster replication between two YSQL databases or YCQL tables on different universes in both directions, so that writes on either database or table can be replicated to the other database or table. Certain xCluster management operations need special attention in this case. See [Bidirectional replication](bidirectional-replication/).

For more information about transactional and non-transactional modes, see [Asynchronous replication modes](../../../architecture/docdb-replication/async-replication/#asynchronous-replication-modes).

{{<lead link="https://www.yugabyte.com/blog/distributed-database-transactional-consistency-async-standby">}}
Blog: [Can Distributed Databases Achieve Transactional Consistency on Async Standbys? Yes, They Can](https://www.yugabyte.com/blog/distributed-database-transactional-consistency-async-standby/)
{{</lead>}}

{{<lead link="https://www.youtube.com/watch?v=lI6gw7ncBs8/">}}
Video: [YFTT - Transactional xCluster](https://www.youtube.com/watch?lI6gw7ncBs8)
{{</lead>}}

{{<index/block>}}

  {{<index/item
    title="Set up xCluster replication"
    body="Designate a universe to act as a source."
    href="xcluster-replication-setup/"
    icon="fa-light fa-copy">}}

  {{<index/item
    title="Manage tables and indexes"
    body="Perform DDL changes to databases in replication."
    href="xcluster-replication-ddl/"
    icon="fa-light fa-table">}}

  {{<index/item
    title="Bidirectional"
    body="Configure bidirectional replication."
    href="bidirectional-replication/"
    icon="fa-light fa-arrows-left-right">}}

{{</index/block>}}

## Limitations

- Currently, replication of DDL (SQL-level changes such as creating or dropping tables or indexes) is not supported. To make these changes requires first performing the DDL operation (for example, creating a table), and then adding the new object to replication in YugabyteDB Anywhere. Refer to [Manage tables and indexes](./xcluster-replication-ddl/).

- xCluster replication setup (and other operations that require making a full copy from source to target, such as adding tables with data to replication, resuming replication after an extended network outage, and so on) forcefully drop the tables on the target if they exist before performing the restore.

    If there are any open SQL connections to the database on the target, they will be interrupted and you should retry the connection.

- Setting up xCluster replication between a universe earlier than or upgraded to v2.20.x and a new v2.20.x universe is not supported. This is due to a limitation of xCluster deployments and packed rows. See [Packed row limitations](../../../architecture/docdb/packed-rows/#limitations).

- You can set up change data capture on a source universe in xCluster replication, but not a target.

## Upgrading universes in xCluster replication

When [upgrading universes](../../manage-deployments/upgrade-software-install/) in xCluster replication, you should upgrade and finalize the target before upgrading and finalizing the source.

## xCluster DR vs xCluster Replication

xCluster refers to all YugabyteDB deployments with two or more universes, and has two major flavors:

- _xCluster DR_. Provides turnkey workflow orchestration for applications using transactional SQL in an active-active single-master manner, with only unidirectional replication configured at any moment in time. xCluster DR uses xCluster replication under the hood, and adds workflow automation and orchestration, including switchover, failover, resynchronization to make another full copy, and so on.
- _xCluster Replication_. Moves the data from one universe to another. Can be used for CQL, non-transactional SQL, bi-directional replication, and other deployment models not supported by xCluster DR.

xCluster DR targets one specific and common xCluster deployment model: [active-active single-master](../../../develop/build-global-apps/active-active-single-master/), unidirectional replication configured at any moment in time, for transactional YSQL.

- Active-active means that both universes are active - the source universe for reads and writes, while the target can handle reads only.

- Single master means that the application writes to only one universe (the source) at any moment in time.

- Unidirectional replication means that at any moment in time, replication traffic flows in one direction, and is configured (and enforced) to flow only in one direction.

- Transactional SQL means that the application is using SQL (and not CQL), and write-ordering is guaranteed for reads on the target. Furthermore, transactions are guaranteed to be atomic.

xCluster DR adds higher-level orchestration workflows to this deployment to make the end-to-end setup, switchover, and failover of the DR primary to DR replica simple and turnkey. This orchestration includes the following:

- During setup, xCluster DR ensures that both universes have identical copies of the data (using backup and restore to synchronize), and configures the DR replica to be read-only.
- During switchover, xCluster DR waits for all remaining changes on the DR primary to be replicated to the DR replica before switching over.
- During both switchover and failover, xCluster DR promotes the DR replica from read only to read and write; during switchover, xCluster DR demotes (when possible) the original DR primary from read and write to read only.

For all deployment models _other than_ active-active single-master, unidirectional replication configured at any moment in time, for transactional YSQL, use xCluster replication directly instead of xCluster DR.

For example, use xCluster replication for the following:

- Multi-master (bidirectional) deployments, where you have two application instances, each one writing to a different universe.
- Active-active single-master deployments in which a single master application can freely write (without coordinating with YugabyteDB for failover or switchover) to either universe, because both accept writes.
- Non-transactional SQL. That is, SQL without write-order guarantees and without transactional atomicity guarantees.
- CQL deployments.

Note that a universe configured for xCluster DR cannot be used for xCluster Replication, and vice versa. Although xCluster DR uses xCluster replication under the hood, xCluster DR replication is managed exclusively from the **xCluster Disaster Recovery** tab, and not on the **xCluster Replication** tab.

(As an alternative to xCluster DR, you can perform setup, failover, and switchover manually. Refer to [Set up transactional xCluster replication](../../../deploy/multi-dc/async-replication/async-transactional-setup/).)

For more information on xCluster replication in YugabyteDB, see the following:

- [xCluster replication: overview and architecture](../../../architecture/docdb-replication/async-replication/)
- [xCluster replication between universes in YugabyteDB](../../../deploy/multi-dc/async-replication/)
