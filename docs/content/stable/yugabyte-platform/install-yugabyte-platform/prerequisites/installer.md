---
title: Prerequisites - YBA Installer
headerTitle: Prerequisites for YBA
linkTitle: YBA prerequisites
description: Prerequisites for installing YugabyteDB Anywhere using YBA Installer
beta: /preview/faq/general/#what-is-the-definition-of-the-beta-feature-tag
menu:
  stable_yugabyte-platform:
    identifier: prerequisites-installer
    parent: install-yugabyte-platform
    weight: 30
type: docs
---

There are three methods of installing YugabyteDB Anywhere (YBA):

| Method | Using | Use If |
| :--- | :--- | :--- |
| Replicated | Docker containers | You're able to use Docker containers. |
| Kubernetes | Helm chart | You're deploying in Kubernetes. |
| YBA Installer | yba-ctl CLI | You can't use Docker containers.<br/>(Note: in Early Access, contact {{% support-platform %}}) |

All installation methods support installing YBA with and without (airgapped) Internet connectivity.

Licensing (such as a license file in the case of Replicated, or appropriate repository access in the case of Kubernetes) may be required prior to installation.  Contact {{% support-platform %}} for assistance.

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li>
    <a href="../default/" class="nav-link">
      <i class="fa-solid fa-cloud"></i>Replicated</a>
  </li>

  <li>
    <a href="../kubernetes/" class="nav-link">
      <i class="fa-regular fa-dharmachakra" aria-hidden="true"></i>Kubernetes</a>
  </li>

  <li>
    <a href="../installer/" class="nav-link active">
      <i class="fa-solid fa-building" aria-hidden="true"></i>YBA Installer</a>
  </li>

</ul>

## Supported Linux distributions

You can install YugabyteDB Anywhere using YBA Installer on the following Linux distributions:

- CentOS (default)
- RHEL 7 and later
- Ubuntu 18 and 20

## Software requirements

- Python 3 must be installed.

## Hardware requirements

A node running YugabyteDB Anywhere is expected to meet the following requirements:

- 4 cores
- 8 GB memory
- 200 GB disk space

## Other

- Ensure that the following ports are available:
  - 443
  - 5432
  - 9080

These are configurable. If custom ports are used, those must be available instead.
