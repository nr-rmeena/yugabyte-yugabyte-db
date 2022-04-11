---
title: Connect to clusters in Yugabyte Cloud
linkTitle: Connect to clusters
description: Connect to clusters in Yugabyte Cloud.
headcontent: Connect to your cluster using Cloud Shell, a client shell, and from applications.
image: /images/section_icons/index/quick_start.png
section: YUGABYTE CLOUD
aliases:
  - /preview/deploy/yugabyte-cloud/connect-to-clusters/
  - /preview/yugabyte-cloud/connect-to-clusters/
  - /preview/yugabyte-cloud/cloud-basics/connect-to-clusters/
menu:
  preview:
    identifier: cloud-connect
    weight: 40
isTocNested: true
showAsideToc: true
---

Connect to clusters in Yugabyte Cloud in the following ways:

| From | How |
| :--- | :--- |
| **Browser** | Use Cloud Shell to connect to your database using any modern browser.<br>No need to set up an IP allow list, all you need is your database password.<br>Includes a built-in YSQL quick start guide. |
| **Desktop** | Install the ysqlsh or ycqlsh client shells to connect to your database from your desktop.<br>Yugabyte Cloud also supports psql and third-party tools such as pgAdmin.<br>Requires your computer to be added to the cluster IP allow list and an SSL connection. |
| **Applications** | Obtain the parameters needed to connect your application driver to your cluster database. |

<div class="row">

  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="connect-cloud-shell/">
      <div class="head">
        <img class="icon" src="/images/section_icons/explore/cloud_native.png" aria-hidden="true" />
        <div class="title">Cloud Shell</div>
      </div>
      <div class="body">
        Connect from your browser.
      </div>
    </a>
  </div>

  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="connect-client-shell/">
      <div class="head">
        <img class="icon" src="/images/section_icons/index/develop.png" aria-hidden="true" />
        <div class="title">Client shell</div>
      </div>
      <div class="body">
        Connect from your desktop using a client shell.
      </div>
    </a>
  </div>

  <div class="col-12 col-md-6 col-lg-12 col-xl-6">
    <a class="section-link icon-offset" href="connect-applications/">
      <div class="head">
        <img class="icon" src="/images/section_icons/develop/real-world-apps.png" aria-hidden="true" />
        <div class="title">Applications</div>
      </div>
      <div class="body">
        Connect applications to your Yugabyte Cloud clusters.
      </div>
    </a>
  </div>

</div>
