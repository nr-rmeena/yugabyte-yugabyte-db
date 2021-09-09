---
title: Connect to clusters
linkTitle: Connect to clusters
description: Connect to Yugabyte Cloud clusters
headcontent:
image: /images/section_icons/deploy/enterprise.png
aliases:
  - /latest/deploy/yugabyte-cloud/connect-to-clusters/
  - /latest/yugabyte-cloud/connect-to-clusters/
menu:
  latest:
    identifier: connect-to-clusters
    parent: cloud-basics
    weight: 40
isTocNested: true
showAsideToc: true
---

You can connect to a cluster in the following ways:

- Cloud Shell - connect to and interact with your YugabyteDB database from your browser.
- YugabyteDB Client Shell - connect from your own computer using the YugabyteDB SQL ([ysqlsh](../../../admin/ycqlsh)) or CQL ([ycqlsh](../../../admin/ycqlsh)) shell.
- Running applications - connect applications to your databases.

{{< note title="Note" >}}

You must configure [Network Access](../../cloud-network/) before you can connect from a remote YugabyteDB client shell or an application.

{{< /note >}}

Related information:

- [ysqlsh](../../../admin/ysqlsh) — Overview of the command line interface (CLI), syntax, and commands.
- [YSQL API](../../../api/ysql) — Reference for supported YSQL statements, data types, functions, and operators.
- [ycqlsh](../../../admin/ycqlsh) — Overview of the command line interface (CLI), syntax, and commands.
- [YCQL API](../../../api/ycql) — Reference for supported YCQL statements, data types, functions, and operators.

## Connect via Cloud Shell

To connect to a cluster via Cloud Shell:

1. On the **Clusters** tab, select a cluster.

1. Click **Connect**.

1. Click **Launch Cloud Shell**. 

1. Enter the database name and user name.

1. Select the API to use (YSQL or YCQL) and click **Confirm**.

    The shell is displayed in a separate browser page (`ysqlsh` output, followed by `ycqlsh`).

    ```output
    Password for user admin: 
    ```

    ```output
    Warning: Cannot create directory at `/home/shell/.cassandra`. Command history will not be saved.

    Password: 
    ```

    Cloud shell can take up to 30 seconds to be ready.

1. Enter the password for the user you specified.

The `ysqlsh` or `ycqlsh` prompt appears and is ready to use.

```output
ysqlsh (11.2-YB-2.2.0.0-b0)
SSL connection (protocol: TLSv1.2, cipher: ECDHE-RSA-AES256-GCM-SHA384, bits: 256, compression: off)
Type "help" for help.

yugabyte=#
```

```output
Connected to local cluster at 3.69.145.48:9042.
[ycqlsh 5.0.1 | Cassandra 3.9-SNAPSHOT | CQL spec 3.4.2 | Native protocol v4]
Use HELP for help.
admin@ycqlsh:yugabyte> 
```

If you enter an incorrect password, the cloud shell session is terminated immediately and you must start a new session.

Once connected, one or more entries for the cloud shell session are added to the cluster IP allow list. After the session is closed, these are cleaned up automatically within five minutes.

## Connect via Client Shell

You can connect to your YugabyteDB cluster using the YugabyteDB [ysqlsh](../../../admin/ysqlsh) and [ycqlsh](../../../admin/ycqlsh) client shells installed on your computer.

You can download and install the YugabyteDB Client Shell and connect to your database by following the steps below for either YSQL or YCQL.

Before you can connect using a client shell, you need to have an IP allow list or VPC peer set up. Refer to [Assign IP Allow Lists](../add-connections/).

<ul class="nav nav-tabs nav-tabs-yb">
  <li >
    <a href="#ysqlsh" class="nav-link active" id="ysqlsh-tab" data-toggle="tab" role="tab" aria-controls="ysqlsh" aria-selected="true">
      <i class="icon-postgres" aria-hidden="true"></i>
      ysqlsh
    </a>
  </li>
  <li>
    <a href="#ycqlsh" class="nav-link" id="ycqlsh-tab" data-toggle="tab" role="tab" aria-controls="ycqlsh" aria-selected="false">
      <i class="icon-cassandra" aria-hidden="true"></i>
      ycqlsh
    </a>
  </li>
</ul>

<div class="tab-content">
  <div id="ysqlsh" class="tab-pane fade show active" role="tabpanel" aria-labelledby="ysqlsh-tab">
    {{% includeMarkdown "connect/ysql.md" /%}}
  </div>
  <div id="ycqlsh" class="tab-pane fade" role="tabpanel" aria-labelledby="ycqlsh-tab">
    {{% includeMarkdown "connect/ycql.md" /%}}
  </div>
</div>

You are now ready to [Create and explore a database](../create-databases/).

## Connect an application

Applications connect to and interact with YugabyteDB using API client libraries, also known as a client drivers. Before you can connect an application, you will need to install the correct driver. For information on available drivers, refer to [Build an application](../../../quick-start/build-apps).

For examples of connecting applications to Yugabyte Cloud, refer to [Tutorials and examples](../../cloud-develop/).

Before you can connect, your application has to be able to reach your Yugabyte Cloud cluster. To add inbound network access from your application environment to a cluster, do one of the following:

- Add the public IP addresses to the [cluster IP access list](../add-connections).
- Use [VPC peering](../../cloud-network/vpc-peers) to add private IP addresses.

Clusters have SSL (encryption in-transit) enabled so make sure your driver details include SSL parameters.

To connect a cluster to an application:

1. On the **Clusters** tab, select the cluster.
1. Click **Connect**.
1. Click **Connect to your Application**.
1. Click **Download CA Cert** and install the certificate on the computer running the application.
1. Add the appropriate YSQL or YCQL connection string to your application.

### YSQL

Here's an example of the generated `ysqlsh` string:

```sh
postgresql://<DB USER>:<DB PASSWORD>@e53ea424-424a-4db5-aece-3bebe4242424.cloud.yugabyte.com:5433/yugabyte? \
ssl=true& \
sslmode=verify-full& \
sslrootcert=<ROOT_CERT_PATH>
```

Add the string to your application, replacing

- `<DB USER>` with your database username.
- `<DB PASSWORD>` with your database password.
- If you are connecting to a database other than the default (yugabyte), replace yugabyte with the database name.
- `<ROOT_CERT_PATH>` with the path to the location where you installed the certificate on your computer.

If you are connecting to a Hasura Cloud project, which does not use the CA certificate, select **Optimize for Hasura Cloud** to modify the string. Before using the string to connect in a Hasura project, be sure to encode any special characters. For an example of connecting a Hasura Cloud project to Yugabyte Cloud, refer to [Connect Hasura Cloud to Yugabyte Cloud](../../cloud-develop/hasura-cloud/).

### YCQL

Here's an example of the generated `ycqlsh` string:

```sh
cassandra://<DB USER>:<DB PASSWORD>@e53ea9b6-424a-4db5-aece-3bebe4242424.cloud.yugabyte.com:9042/yugabyte
```

Add the string to your application, replacing

- `<DB USER>` with your database username.
- `<DB PASSWORD>` with your database username.
- If you are connecting to a database other than the default (yugabyte), replace yugabyte with the database name.

For an example of building a Java application connected to Yugabyte Cloud using the Yugabyte Java Driver for YCQL v4.6, refer to [Connect a YCQL Java application](../../cloud-develop/connect-ycql-application/).

<!--
## Run the sample application

Yugabyte Cloud comes configured with a sample application that you can use to test your cluster.

Before you can connect from your computer, you must add the IP address of the computer to an IP allow list, and the IP allow list must be assigned to the cluster. Refer to [Assign IP Allow Lists](../add-connections/).

You will also need Docker installed on you computer.

To run the sample application:

1. On the **Clusters** tab, select a cluster.
1. Click **Connect**.
1. Click **Run a Sample Application**.
1. Copy the connect string for YSQL or YCQL.
1. Run the command in docker from your computer, replacing `<path to CA cert>`, `<db user>`, and `<db password>` with the path to the CA certificate for the cluster and your database credentials.
-->

## Connect using third party clients

Because YugabyteDB is PostgreSQL-compatible, you can use third party PostgreSQL clients to connect to your YugabyteDB clusters in Yugabyte Cloud. To create connections to your cluster in Yugabyte Cloud, follow the client's configuration steps for PostgreSQL, but use the values for host and port available on the **Settings** tab for your cluster, and the username and password of a user with permissions for the cluster.

For detailed steps for configuring popular third party tools, see [Third party tools](../../../tools/). In that section, configuration steps are included for the following tools:

- DBeaver
- DbSchema
- pgAdmin
- SQL Workbench/J
- TablePlus
- Visual Studio Workbench

## Next steps

- [Create a database](../create-databases)
- [Add database users](../add-users/)
- [Connect an application](../connect-application)
