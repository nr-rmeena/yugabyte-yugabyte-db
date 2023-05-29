// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

#include "yb/master/ysql_transaction_ddl.h"

#include "yb/client/transaction_rpc.h"

#include "yb/qlexpr/ql_expr.h"
#include "yb/common/schema_pbutil.h"
#include "yb/common/wire_protocol.h"

#include "yb/gutil/casts.h"

#include "yb/master/sys_catalog.h"

#include "yb/tablet/tablet.h"
#include "yb/tablet/tablet_metadata.h"
#include "yb/tablet/tablet_peer.h"

#include "yb/tserver/tserver_service.pb.h"

#include "yb/util/flags.h"
#include "yb/util/logging.h"
#include "yb/util/monotime.h"
#include "yb/util/net/net_fwd.h"
#include "yb/util/status_log.h"

#include "yb/util/flags/flag_tags.h"

DEFINE_UNKNOWN_int32(ysql_transaction_bg_task_wait_ms, 200,
  "Amount of time the catalog manager background task thread waits "
  "between runs");

DEFINE_test_flag(bool, skip_transaction_verification, false,
    "Test only flag to keep the txn metadata in SysTablesEntryPB and skip"
    " transaction verification on the master");

using std::string;
using std::vector;

namespace yb {
namespace master {
namespace {

bool IsTableModifiedByTransaction(TableInfo* table,
                                  const TransactionMetadata& transaction) {
  auto l = table->LockForRead();
  const auto& txn = transaction.transaction_id;
  auto result = l->is_being_modified_by_ddl_transaction(txn);
  if (!result.ok()) {
    LOG(ERROR) << "Failed to parse transaction for table " << table->id()
                << " skipping transaction verification";
    return false;
  }
  if (!result.get()) {
    LOG(INFO) << "Verification of DDL transaction " << txn << " already completed for table "
              << table->id();
    return false;
  }
  return true;
}

} // namespace

YsqlTransactionDdl::~YsqlTransactionDdl() {
  // Shutdown any outstanding RPCs.
  rpcs_.Shutdown();
}

Result<bool> YsqlTransactionDdl::PgEntryExists(const TableId& pg_table_id,
                                               PgOid entry_oid,
                                               boost::optional<PgOid> relfilenode_oid) {
  auto read_data = VERIFY_RESULT(sys_catalog_->TableReadData(pg_table_id));

  auto oid_col = VERIFY_RESULT(read_data.ColumnByName("oid")).rep();
  ColumnIdRep relfilenode_col = kInvalidColumnId.rep();

  dockv::ReaderProjection projection;

  bool is_matview = relfilenode_oid.has_value();
  if (is_matview) {
    relfilenode_col = VERIFY_RESULT(read_data.ColumnByName("relfilenode"));
    projection.Init(read_data.schema(), {oid_col, relfilenode_col});
  } else {
    projection.Init(read_data.schema(), {oid_col});
  }

  auto iter = VERIFY_RESULT(GetPgCatalogTableScanIterator(read_data, entry_oid, projection));

  // If no rows found, the entry does not exist.
  qlexpr::QLTableRow row;
  if (!VERIFY_RESULT(iter->FetchNext(&row))) {
    return false;
  }

  // The entry exists. Expect only one row.
  SCHECK(!VERIFY_RESULT(iter->FetchNext(nullptr)), Corruption, "Too many rows found");
  if (is_matview) {
    const auto& relfilenode = row.GetValue(relfilenode_col);
    if (relfilenode->uint32_value() != *relfilenode_oid) {
      return false;
    }
  }
  return true;
}

Result<std::unique_ptr<docdb::YQLRowwiseIteratorIf>>
YsqlTransactionDdl::GetPgCatalogTableScanIterator(const PgTableReadData& read_data,
                                                  PgOid oid_value,
                                                  const dockv::ReaderProjection& projection) {
  // Use Scan to query the given table, filtering by lookup_oid_col.
  auto iter = VERIFY_RESULT(read_data.NewUninitializedIterator(projection));

  PgsqlConditionPB cond;
  cond.add_operands()->set_column_id(projection.columns.front().id.rep());
  cond.set_op(QL_OP_EQUAL);
  cond.add_operands()->mutable_value()->set_uint32_value(oid_value);
  const dockv::KeyEntryValues empty_key_components;
  docdb::DocPgsqlScanSpec spec(
      read_data.schema(), rocksdb::kDefaultQueryId, empty_key_components, empty_key_components,
      &cond, boost::none /* hash_code */, boost::none /* max_hash_code */);
  RETURN_NOT_OK(iter->Init(spec));
  return iter;
}

void YsqlTransactionDdl::VerifyTransaction(
    const TransactionMetadata& transaction_metadata,
    scoped_refptr<TableInfo> table,
    bool has_ysql_ddl_txn_state,
    std::function<Status(bool)> complete_callback) {
  if (FLAGS_TEST_skip_transaction_verification) {
    return;
  }

  SleepFor(MonoDelta::FromMilliseconds(FLAGS_ysql_transaction_bg_task_wait_ms));

  if (has_ysql_ddl_txn_state && !IsTableModifiedByTransaction(table.get(), transaction_metadata)) {
    // The table no longer has any ddl transaction verification state pertaining to
    // 'transaction_metadata'. It was parallelly completed in some other thread, so there is
    // nothing to do.
    return;
  }

  YB_LOG_EVERY_N_SECS(INFO, 1) << "Verifying Transaction " << transaction_metadata;

  tserver::GetTransactionStatusRequestPB req;
  req.set_tablet_id(transaction_metadata.status_tablet);
  req.add_transaction_id()->assign(
      pointer_cast<const char*>(transaction_metadata.transaction_id.data()),
      transaction_metadata.transaction_id.size());

  auto client = client_future_.get();
  if (!client) {
    LOG(WARNING) << "Shutting down. Cannot get GetTransactionStatus: " << transaction_metadata;
    return;
  }
  // Prepare the rpc after checking if it is shutting down in case it returns because of
  // client is null and leave the reserved rpc as uninitialized.
  auto rpc_handle = rpcs_.Prepare();
  if (rpc_handle == rpcs_.InvalidHandle()) {
    LOG(WARNING) << "Shutting down. Cannot send GetTransactionStatus: " << transaction_metadata;
    return;
  }
  // We need to query the TransactionCoordinator here.  Can't use TransactionStatusResolver in
  // TransactionParticipant since this TransactionMetadata may not have any actual data flushed yet.
  *rpc_handle = client::GetTransactionStatus(
    TransactionRpcDeadline(),
    nullptr /* tablet */,
    client,
    &req,
    [this, rpc_handle, transaction_metadata, table, has_ysql_ddl_txn_state, complete_callback]
        (Status status, const tserver::GetTransactionStatusResponsePB& resp) {
      auto retained = rpcs_.Unregister(rpc_handle);
      TransactionReceived(transaction_metadata, table, has_ysql_ddl_txn_state,
          complete_callback, std::move(status), resp);
    });
  (**rpc_handle).SendRpc();
}

void YsqlTransactionDdl::TransactionReceived(
    const TransactionMetadata& transaction,
    scoped_refptr<TableInfo> table,
    bool has_ysql_ddl_txn_state,
    std::function<Status(bool)> complete_callback,
    Status txn_status, const tserver::GetTransactionStatusResponsePB& resp) {
  if (has_ysql_ddl_txn_state && !IsTableModifiedByTransaction(table.get(), transaction)) {
    // The table no longer has any ddl transaction verification state pertaining to
    // 'transaction_metadata'. It was parallelly completed in some other thread, so there is
    // nothing to do.
    return;
  }

  if (!txn_status.ok()) {
    LOG(WARNING) << "Transaction Status attempt (" << transaction.ToString()
                 << ") failed with status " << txn_status;
    WARN_NOT_OK(thread_pool_->SubmitFunc([complete_callback] () {
      WARN_NOT_OK(complete_callback(false /* txn_rpc_success */), "Callback failure");
    }), "Failed to enqueue callback");
    return;
    // #5981: Improve failure handling to retry transient errors or recognize transaction complete.
  }
  if (resp.has_error()) {
    const Status s = StatusFromPB(resp.error().status());
    const tserver::TabletServerErrorPB::Code code = resp.error().code();
    LOG(WARNING) << "Transaction Status attempt (" << transaction.ToString()
                 << ") failed with error code " << tserver::TabletServerErrorPB::Code_Name(code)
                 << ": " << s;
    WARN_NOT_OK(thread_pool_->SubmitFunc([complete_callback] () {
      WARN_NOT_OK(complete_callback(false /* txn_rpc_success */), "Callback failure");
    }), "Failed to enqueue callback");
    // #5981: Maybe have the same heuristic as above?
    return;
  }
  YB_LOG_EVERY_N_SECS(INFO, 1) << "Got Response for " << transaction.ToString()
                               << ", resp: " << resp.ShortDebugString();
  bool is_pending = (resp.status_size() == 0);
  for (int i = 0; i < resp.status_size() && !is_pending; ++i) {
    // NOTE: COMMITTED state is also "pending" because we need APPLIED.
    is_pending = resp.status(i) == TransactionStatus::PENDING ||
                 resp.status(i) == TransactionStatus::COMMITTED;
  }
  if (is_pending) {
    // Re-enqueue if transaction is still pending.
    WARN_NOT_OK(thread_pool_->SubmitFunc(
        std::bind(&YsqlTransactionDdl::VerifyTransaction, this,
                  transaction, table, has_ysql_ddl_txn_state, complete_callback)),
        "Could not submit VerifyTransaction to thread pool");
    return;
  }
  // If this transaction isn't pending, then the transaction is in a terminal state.
  // Note: We ignore the resp.status() now, because it could be ABORT'd but actually a SUCCESS.
  // Determine whether the transaction was a success by comparing with the PG schema.
  WARN_NOT_OK(thread_pool_->SubmitFunc([complete_callback] () {
    WARN_NOT_OK(complete_callback(true /* txn_rpc_success */), "Callback failure");
  }), "Failed to enqueue callback");
}

Result<bool> YsqlTransactionDdl::PgSchemaChecker(const scoped_refptr<TableInfo>& table) {
  const PgOid database_oid = VERIFY_RESULT(GetPgsqlDatabaseOidByTableId(table->id()));
  auto read_data = VERIFY_RESULT(sys_catalog_->TableReadData(database_oid, kPgClassTableOid));

  PgOid oid = VERIFY_RESULT(GetPgsqlTableOid(table->id()));
  auto oid_col_id = VERIFY_RESULT(read_data.ColumnByName("oid")).rep();
  auto relname_col_id = VERIFY_RESULT(read_data.ColumnByName("relname")).rep();
  dockv::ReaderProjection projection(read_data.schema(), {oid_col_id, relname_col_id});
  auto iter = VERIFY_RESULT(GetPgCatalogTableScanIterator(read_data, oid, projection));

  auto l = table->LockForRead();
  if (!l->has_ysql_ddl_txn_verifier_state()) {
    // The table no longer has transaction verifier state on it, it was probably cleaned up
    // concurrently.
    return STATUS_FORMAT(Aborted, "Not performing transaction verification for table $0 as it no "
                         "longer has any transaction verification state", table->ToString());
  }
  // Table not found in pg_class. This can only happen in two cases: Table creation failed,
  // or a table deletion went through successfully.
  qlexpr::QLTableRow row;
  if (!VERIFY_RESULT(iter->FetchNext(&row))) {
    if (l->is_being_deleted_by_ysql_ddl_txn()) {
      return true;
    }
    CHECK(l->is_being_created_by_ysql_ddl_txn());
    return false;
  }

  // Table found in pg_class.
  if (l->is_being_deleted_by_ysql_ddl_txn()) {
    LOG(INFO) << "Ysql Drop transaction for " << table->ToString()
              << " detected to have failed as table found "
              << "in PG catalog";
    return false;
  }

  if (l->is_being_created_by_ysql_ddl_txn()) {
    return true;
  }

  // Table was being altered. Check whether its current DocDB schema matches
  // that of PG catalog.
  CHECK(l->ysql_ddl_txn_verifier_state().contains_alter_table_op());
  const auto& relname_col = row.GetValue(relname_col_id);
  const string& table_name = relname_col->string_value();

  const string fail_msg = "Alter transaction on " + table->ToString() + " failed.";
  if (table->name().compare(table_name) != 0) {
    // Table name does not match.
    LOG(INFO) << fail_msg << Format(" Expected table name: $0 Table name in PG: $1",
        table->name(), table_name);
    CHECK_EQ(table_name, l->ysql_ddl_txn_verifier_state().previous_table_name());
    return false;
  }

  vector<YsqlTransactionDdl::PgColumnFields> pg_cols = VERIFY_RESULT(ReadPgAttribute(table));
  // In DocDB schema, columns are sorted based on 'order'.
  sort(pg_cols.begin(), pg_cols.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.order < rhs.order;
  });

  Schema schema;
  RETURN_NOT_OK(table->GetSchema(&schema));
  if (MatchPgDocDBSchemaColumns(table, schema, pg_cols)) {
    // The PG catalog schema matches the current DocDB schema. The transaction was a success.
    return true;
  }

  Schema previous_schema;
  RETURN_NOT_OK(SchemaFromPB(l->ysql_ddl_txn_verifier_state().previous_schema(), &previous_schema));
  if (MatchPgDocDBSchemaColumns(table, previous_schema, pg_cols)) {
    // The PG catalog schema matches the DocDB schema of the table prior to this transaction. The
    // transaction must have aborted.
    return false;
  }

  // The PG catalog schema does not match either the current schema nor the previous schema. This
  // is an unexpected state, do nothing.
  return STATUS_FORMAT(IllegalState, "Failed to verify transaction for table $0",
                       table->ToString());
}

bool YsqlTransactionDdl::MatchPgDocDBSchemaColumns(
  const scoped_refptr<TableInfo>& table,
  const Schema& schema,
  const vector<YsqlTransactionDdl::PgColumnFields>& pg_cols) {

  const string& fail_msg = "Schema mismatch for table " + table->ToString();
  const std::vector<ColumnSchema>& columns = schema.columns();

  size_t i = 0;
  for (const auto& col : columns) {
    // 'ybrowid' is a column present only in DocDB. Skip it.
    if (col.name() == "ybrowid") {
      continue;
    }

    if (i >= pg_cols.size()) {
      LOG(INFO) << fail_msg << Format(" Expected num_columns: $0 num_columns in PG: $1",
          columns.size(), pg_cols.size());
      return false;
    }

    if (col.name().compare(pg_cols[i].attname) != 0) {
      LOG(INFO) << fail_msg << Format(" Expected column name with attnum: $0 is :$1"
          " but column name at PG is $2", pg_cols[i].order, col.name(), pg_cols[i].attname);
      return false;
    }

    // Verify whether attnum matches.
    if (col.order() != pg_cols[i].order) {
      LOG(INFO) << fail_msg << Format(" At index $0 expected attnum is $1 but actual attnum is $2",
          i, col.order(), pg_cols[i].order);
      return false;
    }
    i++;
  }

  return true;
}

Result<vector<YsqlTransactionDdl::PgColumnFields>>
YsqlTransactionDdl::ReadPgAttribute(scoped_refptr<TableInfo> table) {
  // Build schema using values read from pg_attribute.

  const PgOid database_oid = VERIFY_RESULT(GetPgsqlDatabaseOidByTableId(table->id()));
  const PgOid table_oid = VERIFY_RESULT(GetPgsqlTableOid(table->id()));
  auto read_data = VERIFY_RESULT(sys_catalog_->TableReadData(database_oid, kPgAttributeTableOid));
  const auto attrelid_col_id = VERIFY_RESULT(read_data.ColumnByName("attrelid")).rep();
  const auto attname_col_id = VERIFY_RESULT(read_data.ColumnByName("attname")).rep();
  const auto atttypid_col_id = VERIFY_RESULT(read_data.ColumnByName("atttypid")).rep();
  const auto attnum_col_id = VERIFY_RESULT(read_data.ColumnByName("attnum")).rep();

  dockv::ReaderProjection projection(
      read_data.schema(), { attrelid_col_id, attnum_col_id, attname_col_id, atttypid_col_id });
  PgOid oid = VERIFY_RESULT(GetPgsqlTableOid(table->id()));
  auto iter = VERIFY_RESULT(GetPgCatalogTableScanIterator(read_data, oid, projection));

  vector<PgColumnFields> pg_cols;
  qlexpr::QLTableRow row;
  while (VERIFY_RESULT(iter->FetchNext(&row))) {
    const auto& attname_col = row.GetValue(attname_col_id);
    const auto& atttypid_col = row.GetValue(atttypid_col_id);
    const auto& attnum_col = row.GetValue(attnum_col_id);
    if (!attname_col || !atttypid_col || !attnum_col) {
      std::string corrupted_col =
        !attname_col ? "attname" : !atttypid_col ? "atttypid" : "attnum";
      return STATUS_FORMAT(
          Corruption,
          "Could not read $0 column from pg_attribute for attrelid: $1 database_oid: $2",
          corrupted_col, table_oid, database_oid);
    }

    const int32_t attnum = attnum_col->int16_value();
    if (attnum < 0) {
      // Ignore system columns.
      VLOG(3) << "Ignoring system column (attnum = " << attnum_col->int16_value()
              << ") for attrelid:" << table_oid;
      continue;
    }
    string attname = attname_col->string_value();
    PgOid atttypid = atttypid_col->uint32_value();
    if (atttypid == 0) {
      // Ignore dropped columns.
      VLOG(3) << "Ignoring dropped column " << attname << " (atttypid = 0)"
              << " for attrelid:" << table_oid;
      continue;
    }
    VLOG(3) << "attrelid: " << table_oid << " attname: " << attname << " atttypid: " << atttypid;
    pg_cols.emplace_back(attnum, attname);
  }

  return pg_cols;
}

}  // namespace master
}  // namespace yb
