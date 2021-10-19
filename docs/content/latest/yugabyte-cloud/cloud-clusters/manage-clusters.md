---
title: Cluster nodes
linkTitle: Cluster nodes
description: View Yugabyte Cloud cluster nodes.
headcontent:
image: /images/section_icons/deploy/enterprise.png
menu:
  latest:
    identifier: manage-clusters
    parent: cloud-clusters
    weight: 300
isTocNested: true
showAsideToc: true
---

The **Nodes** tab lists the nodes in the cluster, including the name, cloud, RAM, SST size, and read and write operations per second, along with the node status.

![Cloud Cluster Nodes tab](/images/yb-cloud/cloud-clusters-nodes.png)

Yugabyte Cloud suppports horizontal scaling of paid clusters. If your workloads have increased, you can dynamically add nodes to a running cluster to improve latency, throughput, and memory. Likewise, if your cluster is over-scaled, you can reduce nodes to reduce costs. For information on scaling clusters, refer to [Configure clusters](../configure-clusters#infrastructure).
