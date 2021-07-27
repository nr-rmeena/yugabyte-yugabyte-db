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

#include "yb/client/table.h"

#include "yb/client/client.h"
#include "yb/client/client-internal.h"
#include "yb/client/yb_op.h"

#include "yb/master/master.pb.h"
#include "yb/master/master.proxy.h"

#include "yb/util/unique_lock.h"
#include "yb/util/status.h"

DEFINE_int32(
    max_num_tablets_for_table, 5000,
    "Max number of tablets that can be specified in a CREATE TABLE statement");

namespace yb {
namespace client {

Status YBTable::PBToClientTableType(
    TableType table_type_from_pb,
    YBTableType* client_table_type) {
  switch (table_type_from_pb) {
    case TableType::YQL_TABLE_TYPE:
      *client_table_type = YBTableType::YQL_TABLE_TYPE;
      return Status::OK();
    case TableType::REDIS_TABLE_TYPE:
      *client_table_type = YBTableType::REDIS_TABLE_TYPE;
      return Status::OK();
    case TableType::PGSQL_TABLE_TYPE:
      *client_table_type = YBTableType::PGSQL_TABLE_TYPE;
      return Status::OK();
    case TableType::TRANSACTION_STATUS_TABLE_TYPE:
      *client_table_type = YBTableType::TRANSACTION_STATUS_TABLE_TYPE;
      return Status::OK();
  }

  *client_table_type = YBTableType::UNKNOWN_TABLE_TYPE;
  return STATUS(InvalidArgument, strings::Substitute(
    "Invalid table type from master response: $0", table_type_from_pb));
}

TableType YBTable::ClientToPBTableType(YBTableType table_type) {
  switch (table_type) {
    case YBTableType::YQL_TABLE_TYPE:
      return TableType::YQL_TABLE_TYPE;
    case YBTableType::REDIS_TABLE_TYPE:
      return TableType::REDIS_TABLE_TYPE;
    case YBTableType::PGSQL_TABLE_TYPE:
      return TableType::PGSQL_TABLE_TYPE;
    case YBTableType::TRANSACTION_STATUS_TABLE_TYPE:
      return TableType::TRANSACTION_STATUS_TABLE_TYPE;
    case YBTableType::UNKNOWN_TABLE_TYPE:
      break;
  }
  FATAL_INVALID_ENUM_VALUE(YBTableType, table_type);
  // Returns a dummy value to avoid compilation warning.
  return TableType::DEFAULT_TABLE_TYPE;
}

YBTable::YBTable(client::YBClient* client, const YBTableInfo& info)
    : client_(client),
      // The table type is set after the table is opened.
      table_type_(YBTableType::UNKNOWN_TABLE_TYPE),
      info_(info) {
}

YBTable::~YBTable() {
}

//--------------------------------------------------------------------------------------------------

const YBTableName& YBTable::name() const {
  return info_.table_name;
}

YBTableType YBTable::table_type() const {
  return table_type_;
}

const string& YBTable::id() const {
  return info_.table_id;
}

YBClient* YBTable::client() const {
  return client_;
}

const YBSchema& YBTable::schema() const {
  return info_.schema;
}

const Schema& YBTable::InternalSchema() const {
  return internal::GetSchema(info_.schema);
}

const IndexMap& YBTable::index_map() const {
  return info_.index_map;
}

bool YBTable::IsIndex() const {
  return info_.index_info != boost::none;
}

bool YBTable::IsUniqueIndex() const {
  return info_.index_info.is_initialized() && info_.index_info->is_unique();
}

const IndexInfo& YBTable::index_info() const {
  static IndexInfo kEmptyIndexInfo;
  if (info_.index_info) {
    return *info_.index_info;
  }
  return kEmptyIndexInfo;
}

const bool YBTable::colocated() const {
  return info_.colocated;
}

const boost::optional<master::ReplicationInfoPB>& YBTable::replication_info() const {
  return info_.replication_info;
}

std::string YBTable::ToString() const {
  return strings::Substitute(
      "$0 $1 IndexInfo: $2 IndexMap $3", (IsIndex() ? "Index Table" : "Normal Table"), id(),
      yb::ToString(index_info()), yb::ToString(index_map()));
}

const PartitionSchema& YBTable::partition_schema() const {
  return info_.partition_schema;
}

bool YBTable::IsHashPartitioned() const {
  // TODO(neil) After fixing github #5832, "partition_schema" must be used here.
  // return info_.partition_schema.IsHashPartitioning();
  return info_.schema.num_hash_key_columns() > 0;
}

bool YBTable::IsRangePartitioned() const {
  // TODO(neil) After fixing github #5832, "partition_schema" must be used here.
  // return info_.partition_schema.IsRangePartitioning();
  return info_.schema.num_hash_key_columns() == 0;
}

std::shared_ptr<const TablePartitionList> YBTable::GetPartitionsShared() const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  return std::shared_ptr<const TablePartitionList>(partitions_, &partitions_->keys);
}

VersionedTablePartitionListPtr YBTable::GetVersionedPartitions() const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  return partitions_;
}

TablePartitionList YBTable::GetPartitionsCopy() const {
  TablePartitionList result;

  SharedLock<decltype(mutex_)> lock(mutex_);
  result.reserve(partitions_->keys.size());
  for (const auto& key : partitions_->keys) {
    result.push_back(key);
  }
  return result;
}

int32_t YBTable::GetPartitionCount() const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  return partitions_->keys.size();
}

int32_t YBTable::GetPartitionListVersion() const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  return partitions_->version;
}

//--------------------------------------------------------------------------------------------------

std::unique_ptr<YBqlWriteOp> YBTable::NewQLWrite() {
  return std::unique_ptr<YBqlWriteOp>(new YBqlWriteOp(shared_from_this()));
}

std::unique_ptr<YBqlWriteOp> YBTable::NewQLInsert() {
  return YBqlWriteOp::NewInsert(shared_from_this());
}

std::unique_ptr<YBqlWriteOp> YBTable::NewQLUpdate() {
  return YBqlWriteOp::NewUpdate(shared_from_this());
}

std::unique_ptr<YBqlWriteOp> YBTable::NewQLDelete() {
  return YBqlWriteOp::NewDelete(shared_from_this());
}

std::unique_ptr<YBqlReadOp> YBTable::NewQLSelect() {
  return YBqlReadOp::NewSelect(shared_from_this());
}

std::unique_ptr<YBqlReadOp> YBTable::NewQLRead() {
  return std::unique_ptr<YBqlReadOp>(new YBqlReadOp(shared_from_this()));
}

size_t YBTable::FindPartitionStartIndex(const PartitionKey& partition_key, size_t group_by) const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  return client::FindPartitionStartIndex(partitions_->keys, partition_key, group_by);
}

PartitionKeyPtr YBTable::FindPartitionStart(
    const PartitionKey& partition_key, size_t group_by) const {
  SharedLock<decltype(mutex_)> lock(mutex_);
  size_t idx = FindPartitionStartIndex(partition_key, group_by);
  return std::shared_ptr<const std::string>(partitions_, &partitions_->keys[idx]);
}

void YBTable::InvokeRefreshPartitionsCallbacks(const Status& status) {
  std::vector<StdStatusCallback> callbacks;
  {
    UniqueLock<decltype(refresh_partitions_callbacks_mutex_)> lock(
        refresh_partitions_callbacks_mutex_);
    refresh_partitions_callbacks_.swap(callbacks);
  }
  for (auto& callback : callbacks) {
    callback(status);
  }
}

void YBTable::RefreshPartitions(StdStatusCallback callback) {
  UniqueLock<decltype(refresh_partitions_callbacks_mutex_)> lock(
      refresh_partitions_callbacks_mutex_);
  bool was_empty = refresh_partitions_callbacks_.empty();
  refresh_partitions_callbacks_.emplace_back(std::move(callback));
  if (!was_empty) {
    VLOG_WITH_FUNC(2) << Format(
        "FetchPartitions is in progress for table $0 ($1), added callback", info_.table_name,
        info_.table_id);
    return;
  }

  VLOG_WITH_FUNC(2) << Format(
      "Calling FetchPartitions for table $0 ($1)", info_.table_name, info_.table_id);
  FetchPartitions([this](const FetchPartitionsResult& result) {
    if (!result.ok()) {
      InvokeRefreshPartitionsCallbacks(result.status());
      return;
    }
    const auto& partitions = result->first;
    {
      std::lock_guard<rw_spinlock> partitions_lock(mutex_);
      if (partitions->version < partitions_->version) {
        // This might happen if another split happens after we had fetched partition in the current
        // thread from master leader and partition list has been concurrently updated to version
        // newer than version we got in current thread.
        // In this case we can safely skip outdated partition list.
        LOG(INFO) << Format(
            "Received table $0 partition list version: $1, ours is newer: $2", id(),
            partitions->version, partitions_->version);
        return;
      }
      partitions_ = result->first;
      partitions_are_stale_ = false;
    }
    InvokeRefreshPartitionsCallbacks(Status::OK());
  });
}

void YBTable::MarkPartitionsAsStale() {
  partitions_are_stale_ = true;
}

bool YBTable::ArePartitionsStale() const {
  return partitions_are_stale_;
}

void YBTable::FetchPartitions(FetchPartitionsCallback callback) {
  // TODO: fetch the schema from the master here once catalog is available.
  // TODO(tsplit): consider optimizing this to not wait for all tablets to be running in case
  // of some tablet has been split and post-split tablets are not yet running.
  client_->GetTableLocations(
      info_.table_id, /* max_tablets = */ std::numeric_limits<int32_t>::max(),
      RequireTabletsRunning::kTrue,
      [this,
       callback = std::move(callback)](const Result<master::GetTableLocationsResponsePB*>& result) {
        if (!result.ok()) {
          callback(result.status());
          return;
        }
        const auto& resp = **result;

        VLOG_WITH_FUNC(2) << Format(
            "Fetched partitions for table $0 ($1), found $2 tablets", info_.table_name,
            info_.table_id, resp.tablet_locations_size());

        YBTableType table_type;
        auto s = PBToClientTableType(resp.table_type(), &table_type);
        if (!s.ok()) {
          callback(s.CloneAndPrepend(Format(
              "Invalid table type $0 for table $1 ($2)", resp.table_type(), info_.table_name,
              info_.table_id)));
          return;
        }

        auto partitions = std::make_shared<VersionedTablePartitionList>();
        partitions->version = resp.partition_list_version();
        partitions->keys.reserve(resp.tablet_locations().size());
        for (const auto& tablet_location : resp.tablet_locations()) {
          partitions->keys.push_back(tablet_location.partition().partition_key_start());
        }
        std::sort(partitions->keys.begin(), partitions->keys.end());

        callback(std::make_pair(partitions, table_type));
      });
}

Status YBTable::Open() {
  Synchronizer synchronizer;

  FetchPartitions([this, callback = synchronizer.AsStdStatusCallback()](
                      const FetchPartitionsResult& result) {
    Status status;
    if (result.ok()) {
      std::lock_guard<rw_spinlock> partitions_lock(mutex_);
      partitions_ = result->first;
      table_type_ = result->second;
      partitions_are_stale_ = false;
    } else {
      status = result.status();
    }
    callback(status);
  });
  return synchronizer.Wait();
}

//--------------------------------------------------------------------------------------------------

std::unique_ptr<YBPgsqlWriteOp> YBTable::NewPgsqlWrite() {
  return std::unique_ptr<YBPgsqlWriteOp>(new YBPgsqlWriteOp(shared_from_this()));
}

std::unique_ptr<YBPgsqlWriteOp> YBTable::NewPgsqlInsert() {
  return YBPgsqlWriteOp::NewInsert(shared_from_this());
}

std::unique_ptr<YBPgsqlWriteOp> YBTable::NewPgsqlUpdate() {
  return YBPgsqlWriteOp::NewUpdate(shared_from_this());
}

std::unique_ptr<YBPgsqlWriteOp> YBTable::NewPgsqlDelete() {
  return YBPgsqlWriteOp::NewDelete(shared_from_this());
}

std::unique_ptr<YBPgsqlWriteOp> YBTable::NewPgsqlTruncateColocated() {
  return YBPgsqlWriteOp::NewTruncateColocated(shared_from_this());
}

std::unique_ptr<YBPgsqlReadOp> YBTable::NewPgsqlSelect() {
  return YBPgsqlReadOp::NewSelect(shared_from_this());
}

std::unique_ptr<YBPgsqlReadOp> YBTable::NewPgsqlRead() {
  return std::unique_ptr<YBPgsqlReadOp>(new YBPgsqlReadOp(shared_from_this()));
}

size_t FindPartitionStartIndex(const TablePartitionList& partitions,
                               const PartitionKey& partition_key,
                               size_t group_by) {
  auto it = std::lower_bound(partitions.begin(), partitions.end(), partition_key);
  if (it == partitions.end() || *it > partition_key) {
    DCHECK(it != partitions.begin()) << "Could not find partition start while looking for "
        << partition_key << " in " << yb::ToString(partitions);
    --it;
  }
  return group_by <= 1 ? it - partitions.begin() :
                         (it - partitions.begin()) / group_by * group_by;
}

PartitionKeyPtr FindPartitionStart(
    const VersionedTablePartitionListPtr& versioned_partitions, const PartitionKey& partition_key,
    size_t group_by) {
  const auto idx = FindPartitionStartIndex(versioned_partitions->keys, partition_key, group_by);
  return PartitionKeyPtr(versioned_partitions, &versioned_partitions->keys[idx]);
}


} // namespace client
} // namespace yb
