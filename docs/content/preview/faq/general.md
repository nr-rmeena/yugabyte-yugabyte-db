---
title: YugabyteDB FAQS
headerTitle: General FAQ
linkTitle: General FAQ
description: YugabyteDB FAQ — How does YugabyteDB work? When is YugabyteDB database a good fit? What APIs does YugabyteDB support? And other frequently asked questions.
aliases:
  - /preview/faq/product/
  - /preview/introduction/overview/
  - /preview/introduction/benefits/
menu:
  preview_faq:
    identifier: faq-general
    parent: faq
    weight: 10
type: docs
unversioned: true
rightNav:
  hideH3: true
  hideH4: true
---

## YugabyteDB

### What is YugabyteDB

YugabyteDB is a high-performant, highly available and scalable distributed SQL database designed for powering global, internet-scale applications. It is fully compatabile with [PostgreSQL] and provides strong [ACID](/preview/architecture/key-concepts/#acid) guarantees for distributed transactions. It can be deployed in a single region, multi-region, and multi-cloud setups. YugabyteDB is developed and distributed as an [Apache 2.0 open source project](https://github.com/yugabyte/yugabyte-db/).

{{<lead link="/preview/explore/">}}
To learn more about the various functionalities of YugabyteDB, see [Explore YugabyteDB](/preview/explore/)
{{</lead>}}

### What makes YugabyteDB unique

YugabyteDB stands out as a unique database solution due to its combination of features that bring together the strengths of both traditional SQL databases and modern NoSQL systems. It is [horizontally scalable](/preview/explore/linear-scalability/), supports global geo-distribution, supports [SQL (YSQL)](/preview/explore/ysql-language-features/sql-feature-support/) and [NoSQL (YCQL)](/preview/explore/ycql-language/) APIs, provides strong transactional consistency, and is [highly performant](/preview/benchmark/).

{{<lead link="/preview/architecture/design-goals/">}}
To understand the ideas and thoughts that went into designed YugabyteDB, see [Design goals](/preview/architecture/design-goals/)
{{</lead>}}

### Is YugabyteDB open source?

Starting with [v1.3](https://www.yugabyte.com/blog/announcing-yugabyte-db-v1-3-with-enterprise-features-as-open-source/), YugabyteDB is 100% open source. It is licensed under Apache 2.0.

{{<lead link="https://github.com/yugabyte/yugabyte-db">}}
The source code is available on [Github:yugabyte-db](https://github.com/yugabyte/yugabyte-db)
{{</lead>}}

### How many major releases YugabyteDB has had so far?

YugabyteDB released its first beta, [v0.9 Beta](https://www.yugabyte.com/blog/yugabyte-has-arrived/) in November 2017. Since then several stable and preview versions have been been released. The current stable version is {{<release "stable">}} and the current preview version is {{<release "preview">}}.

{{<lead link="/preview/releases/ybdb-releases/">}}
For the full list of releases, see [YugabyteDB releases](/preview/releases/ybdb-releases/)
{{</lead>}}

### What are the upcoming features

The roadmap for upcoming releases and the list of recently released featured can be found on the [yugabyte-db repo on GitHub](https://github.com/yugabyte/yugabyte-db#whats-being-worked-on).

{{<lead link="https://github.com/yugabyte/yugabyte-db#whats-being-worked-on">}}
To explore the planned features, see [Current roadmap](https://github.com/yugabyte/yugabyte-db#whats-being-worked-on)
{{</lead>}}

### Which companies are currently using YugabyteDB in production?

Reference deployments are listed in [Success Stories](https://www.yugabyte.com/success-stories/).

### How do I report a security vulnerability?

Please follow the steps in the [vulnerability disclosure policy](../../secure/vulnerability-disclosure-policy) to report a vulnerability to our security team. The policy outlines our commitments to you when you disclose a potential vulnerability, the reporting process, and how we will respond.

### What are YugabyteDB Anywhere and YugabyteDB Aeon?

**[YugabyteDB](../../)** is the 100% open source core database. It is the best choice for the startup organizations with strong technical operations expertise looking to deploy to production with traditional DevOps tools.

**[YugabyteDB Anywhere](../../yugabyte-platform/)** is commercial software for running a self-managed YugabyteDB-as-a-Service. It has built-in cloud native operations, enterprise-grade deployment options, and world-class support.

**[YugabyteDB Aeon](../../yugabyte-cloud/)** is Yugabyte's fully-managed cloud service on Amazon Web Services (AWS), Microsoft Azure, and Google Cloud Platform (GCP). [Sign up](https://cloud.yugabyte.com/) to get started.

{{<lead link="https://www.yugabyte.com/compare-products/">}}
For a more detailed comparison between the above, see [Compare Deployment Options](https://www.yugabyte.com/compare-products/).
{{</lead>}}

### When is YugabyteDB a good fit?

YugabyteDB is a good fit for fast-growing, cloud native applications that need to serve business-critical data reliably, with zero data loss, high availability, and low latency. Common use cases include:

- Distributed Online Transaction Processing (OLTP) applications needing multi-region scalability without compromising strong consistency and low latency. For example, user identity, Retail product catalog, Financial data service.

- Hybrid Transactional/Analytical Processing (HTAP), also known as Translytical, applications needing real-time analytics on transactional data. For example, user personalization, fraud detection, machine learning.

- Streaming applications needing to efficiently ingest, analyze, and store ever-growing data. For example, IoT sensor analytics, time series metrics, real-time monitoring.

### When is YugabyteDB not a good fit?

YugabyteDB is not a good fit for traditional Online Analytical Processing (OLAP) use cases that need complete ad-hoc analytics. Use an OLAP store such as [Druid](http://druid.io/druid.html) or a data warehouse such as [Snowflake](https://www.snowflake.net/).

### What is a YugabyteDB universe

A YugabyteDB [universe](/preview/architecture/key-concepts/#universe) comprises one [primary cluster](/preview/architecture/key-concepts/#primary-cluster) and zero or more [read replica clusters](/preview/architecture/key-concepts/#read-replica-cluster) that collectively function as a resilient and scalable distributed database. It is common to have just a primary cluster and hence the terms cluster and universe are sometimes used interchangeably but it is worthwhile to note that they are different.

### Are there any performance benchmarks available?

YugabyteDB is benchmarked on a regular basis on a variety of standard benchmarks like [TPC-C](/preview/benchmark/tpcc/), [YCSB](/preview/benchmark/ycsb-ysql/) and [sysbench](/preview/benchmark/sysbench-ysql/).

{{<lead link="/preview/benchmark/">}}
To see the current benchmark results and  understand how to run various benchmarks yourself, see [Benchmark](/preview/benchmark/)
{{</lead>}}

### How is YugabyteDB tested for correctness

Apart from the rigorous failure testing, YugabyteDB passes most of the scenarios in [Jepsen](https://jepsen.io/) testing which is a methodology and toolset used to verify the correctness of distributed systems, particularly in the context of consistency models and fault tolerance. It was developed by Kyle Kingsbury (aka "aphyr") and has become a standard for stress-testing distributed databases, data stores, and other distributed systems.

{{<lead link="">}}
To see the details on our latest Jepsen test run, see [Jepsen testing](/preview/benchmark/resilience/jepsen-testing/)
{{</lead>}}

### How does YugabyteDB compare to other databases

We have published detailed comparison information against multiple SQL and NoSQL databases.
- **SQL**: [CockroachDB](../comparisons/cockroachdb/), [TiDB](../comparisons/tidb/), [Vitess](../comparisons/vitess/), [Amazon Aurora](../comparisons/amazon-aurora/), [Google Spanner](../comparisons/google-spanner/)
- **NOSQL**: [MongoDB](../comparisons/mongodb/), [FoundationDB](../comparisons/foundationdb/), [Cassandra](../comparisons/cassandra/), [DynamoDB](../comparisons/amazon-dynamodb/), [CosmosDB](../comparisons/azure-cosmos/)

{{<lead link="../comparisons/">}}
See [Compare YugabyteDB to other databases](../comparisons/) for more details.
{{</lead>}}

## PostgreSQL support

### How compatible is YugabyteDB with PostgreSQL

YugabyteDB is [wire-protocol, syntax, feature and runtime](https://www.yugabyte.com/postgresql/postgresql-compatibility/) compatible with PostgreSQL. But that said, supporting all PostgreSQL features in a distributed system is not always feasible.

{{<lead link="/preview/explore/ysql-language-features/postgresql-compatibility/#unsupported-postgresql-features">}}
For the list of PostgreSQL features not supported or currently being worked on, see [Unsupported PostgreSQL features](/preview/explore/ysql-language-features/postgresql-compatibility/#unsupported-postgresql-features)
{{</lead>}}

### Can I use my existing PostgreSQL tools and drivers with YugabyteDB

Yes. YuagbyteDB is [fully compatible](#how-compatible-is-yugabytedb-with-postgresql) with Postgresql. So it automatically works well with most of the PostgreSQL tools.

{{<lead link="">}}
To learn more about how to get standard tools work with YugabyteDB, see [Integrations](/preview/integrations/tools/)
{{</lead>}}

### Are PostgreSQL extensions supported

Extensions are very useful as they extend the functionality of the core database by providing new data types and functionalites. Given the distributed nature of YugabyteDB not all Postgresql extensions are supported by default, but YugabyteDB pre-bundles many of the popular extensions and these should be readily available on your cluster.

{{<lead link="/preview/explore/ysql-language-features/pg-extensions/">}}
For the list of extensions supported, see [PostgreSQL extensions](/preview/explore/ysql-language-features/pg-extensions/)
{{</lead>}}

### How can I migrate from PostgreSQL

YugabyteDB is fully compatible with Postgres and hence most PostgreSQL applications should work as is. But to address corner cases, we have published a [comprehensive guide](https://docs.yugabyte.com/stable/manage/data-migration/migrate-from-postgres/) to help you migrate from PostgreSQL.

{{<lead link="">}}
To understand how migrate to YugabyteDB, see [Migrate data](https://docs.yugabyte.com/stable/manage/data-migration/)
{{</lead>}}

## Architecture

### How does YugabyteDB distribute data

The table data is split into [tablets](/preview/architecture/key-concepts/#tablet) and the table rows are mapped to the tablets via [sharding](/preview/explore/linear-scalability/data-distribution/). The tablets themselves are distributed across the various nodes in the cluster.

{{<lead link="/preview/explore/linear-scalability/data-distribution/">}}
To understand how data is distributed in detail, see [Data distribution](/preview/explore/linear-scalability/data-distribution/)
{{</lead>}}

### How does YugabyteDB scale

YugabyteDB scales seamlessly when new nodes are added to the cluster without any service disruption. Table data is [stored distributed](#how-does-yugabytedb-distribute-data) in tablets. When new nodes are added, the rebalancer moves certain tablets to other nodes and keeps the no.of tablets on each node more or less the same. As data grows, these tablets also split into two and are moved to other nodes.

{{<lead link="/preview/explore/linear-scalability/">}}
To understand in detail how scaling works, see [Horizontal scalability](/preview/explore/linear-scalability/)
{{</lead>}}

### How does YugabyteDB provide high availability

YugabyteDB replicates [tablet](/preview/architecture/key-concepts/#tablet) data onto [followers](/preview/architecture/key-concepts/#tablet-follower) of the tablet via [RAFT](/preview/architecture/docdb-replication/raft/) consensus. This ensures that a consistent copy of the data is available in case of failures. On failures, one of the tablet followers is promoted to be the [leader](/preview/architecture/key-concepts/#tablet-leader).

{{<lead link="/preview/explore/fault-tolerance/">}}
To understand how YugabyteDB survives node, zone, rack and region failures, see [Resiliency and high availability](/preview/explore/fault-tolerance/)
{{</lead>}}

### How is data consistency maintained across multiple nodes

Every write (insert, update, delete) to the data is replicated via [RAFT](/preview/architecture/docdb-replication/raft/) consensus to [tablet followers](/preview/architecture/key-concepts/#tablet-follower) as per the [replication factor(RF)]((/stable/architecture/key-concepts/#replication-factor-rf) of the cluster. Before acknowledging the write operation back to the client, YugabyteDB ensures that the data is replicated to a quorum (RF/2 + 1) followers.

{{<lead link="(/stable/architecture/docdb-replication/replication/#replication-factor">}}
To understand how data is replicated, see [Synchronous replication](/stable/architecture/docdb-replication/replication/#replication-factor)
{{</lead>}}

### What is tablet splitting

Data is stored in [tablets](/preview/architecture/key-concepts/#tablet). As the tablet grows, the tablet splits into two. This enables some data to be moved to other nodes in the cluster.

{{<lead link="">}}
To understand how tablet splitting works in detail, see [Tablet splitting](/preview/architecture/docdb-sharding/tablet-splitting/)
{{</lead>}}

### How can YugabyteDB be both CP and ensure high availability at the same time?

In terms of the [CAP theorem](https://www.yugabyte.com/blog/a-for-apple-b-for-ball-c-for-cap-theorem-8e9b78600e6d), YugabyteDB is a consistent and partition-tolerant (CP) database. It ensures high availability (HA) for most practical situations even while remaining strongly consistent. While this may seem to be a violation of the CAP theorem, that is not the case. CAP treats availability as a binary option whereas YugabyteDB treats availability as a percentage that can be tuned to achieve high write availability (reads are always available as long as a single node is available).

{{<lead link="/preview/architecture/design-goals/#partition-tolerance-cap">}}
For details on the behavior during network partitions, see [Partition Tolerance - CAP](/preview/architecture/design-goals/#partition-tolerance-cap)
{{</lead>}}
