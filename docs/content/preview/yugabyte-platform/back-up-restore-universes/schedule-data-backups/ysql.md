---
title: Schedule universe YSQL data backups
headerTitle: Schedule universe YSQL data backups
linkTitle: Schedule data backups
description: Use YugabyteDB Anywhere to create scheduled backups of universe YSQL data.
aliases:
  - /preview/manage/enterprise-edition/schedule-backups/
  - /preview/manage/enterprise-edition/schedule-data-backup/
  - /preview/yugabyte-platform/back-up-restore-universes/schedule-data-backups/
menu:
  preview:
    identifier: schedule-data-backups-1-ysql
    parent: back-up-restore-universes
    weight: 40
isTocNested: true
showAsideToc: true
---

<ul class="nav nav-tabs-alt nav-tabs-yb">

  <li >
    <a href="{{< relref "./ysql.md" >}}" class="nav-link active">
      <i class="icon-postgres" aria-hidden="true"></i>
      YSQL
    </a>
  </li>

  <li >
    <a href="{{< relref "./ycql.md" >}}" class="nav-link">
      <i class="icon-cassandra" aria-hidden="true"></i>
      YCQL
    </a>
  </li>

</ul>

You can use YugabyteDB Anywhere to perform regularly scheduled backups of YugabyteDB universe data for all YSQL tables in a namespace.

To back up your universe YSQL data immediately, see [Back up universe YSQL data](../../back-up-universe-data/ysql/).

## Schedule a backup

You can schedule a backup of your universe YSQL data as follows:

1. Navigate to **Universes**.

2. Select the name of the universe for which you want to schedule backups.

3. Click the **Tables** tab and verify that backups are enabled. If disabled, click **Enable Backup**.

4. Select the **Backups** tab and then click **Create Scheduled Backup** to open the **Create Backup** dialog shown in the following illustration:
    <br/>
    <br/>

    ![Create Backup form](/images/yp/scheduled-backup-ysql.png)

5. Enter the **Backup frequency** (interval in milliseconds) or a **Cron expression (UTC)***. For details on valid `cron` expression formats, hover over the question mark (?) icon.

6. Select the **YSQL** tab and enter values for the following fields:

    - **Storage**: Select the storage type: `GCS Storage`, `S3 Storage`, or `NFS Storage`.
    - **Namespace**: Select the namespace from the drop-down list of available namespaces.
    - **Parallel Threads**: Enter or select the number of threads. The default is `8`.
    - **Number of Days to Retain Backup**: Default is unspecified which means to retain indefinitely.

7. Click **OK**. <br>

    The initial backup will begin immediately.

Subsequent backups are created based on the value you specified for **Backup frequency** or **Cron expression**.

## Disable scheduled backups

You can temporarily disable all scheduled backups, as follows:

1. Go to the **Tables** tab in the universe.
2. Click **Disable Backups**.

## Delete a scheduled backup

You can permanently remove a scheduled backup, as follows:

1. Go to the **Backups** tab for the universe.
2. Find the scheduled backup and click **Options**.
3. Click **Delete schedule**.
