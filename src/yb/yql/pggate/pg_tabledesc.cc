//--------------------------------------------------------------------------------------------------
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
//--------------------------------------------------------------------------------------------------

#include "yb/yql/pggate/pg_tabledesc.h"
#include "yb/yql/pggate/pggate_flags.h"

#include "yb/client/table.h"

#include "yb/common/pg_system_attr.h"
#include "yb/common/ql_value.h"

namespace yb {
namespace pggate {

PgTableDesc::PgTableDesc(const client::YBTablePtr& table)
    : table_(table), table_partitions_(table_->GetVersionedPartitions()) {

  size_t idx = 0;
  for (const auto& column : schema().columns()) {
    attr_num_map_.emplace(column.order(), idx++);
  }
}

Result<size_t> PgTableDesc::FindColumn(int attr_num) const {
  // Find virtual columns.
  if (attr_num == static_cast<int>(PgSystemAttrNum::kYBTupleId)) {
    return num_columns();
  }

  // Find physical column.
  const auto itr = attr_num_map_.find(attr_num);
  if (itr != attr_num_map_.end()) {
    return itr->second;
  }

  return STATUS_FORMAT(InvalidArgument, "Invalid column number $0", attr_num);
}

Result<YBCPgColumnInfo> PgTableDesc::GetColumnInfo(int16_t attr_number) const {
  YBCPgColumnInfo column_info {
    .is_primary = false,
    .is_hash = false
  };
  const auto itr = attr_num_map_.find(attr_number);
  if (itr != attr_num_map_.end()) {
    column_info.is_primary = itr->second < schema().num_key_columns();
    column_info.is_hash = itr->second < schema().num_hash_key_columns();
  }
  return column_info;
}

bool PgTableDesc::IsColocated() const {
  return table_->colocated();
}

bool PgTableDesc::IsHashPartitioned() const {
  return schema().num_hash_key_columns() > 0;
}

bool PgTableDesc::IsRangePartitioned() const {
  return schema().num_hash_key_columns() == 0;
}

const std::vector<std::string>& PgTableDesc::GetPartitions() const {
  return table_partitions_->keys;
}

int PgTableDesc::GetPartitionCount() const {
  return table_partitions_->keys.size();
}

Result<string> PgTableDesc::DecodeYbctid(const Slice& ybctid) const {
  // TODO(neil) If a partition schema can have both hash and range partitioning, this function needs
  // to be updated to return appropriate primary key.
  RSTATUS_DCHECK(!IsHashPartitioned() || !IsRangePartitioned(), InvalidArgument,
                 "Partitioning schema by both hash and range is not yet supported");

  // Use range key if there's no hash columns.
  // NOTE: Also see bug github #5832.
  if (IsRangePartitioned()) {
    // Decoding using range partitioning method.
    return ybctid.ToBuffer();
  }

  // Decoding using hash partitioning method.
  // Do not check with predicate IsHashPartitioning() for now to use existing behavior by default.
  uint16 hash_code = VERIFY_RESULT(docdb::DocKey::DecodeHash(ybctid));
  return PartitionSchema::EncodeMultiColumnHashValue(hash_code);
}

Result<size_t> PgTableDesc::FindPartitionIndex(const Slice& ybctid) const {
  // Find partition index based on ybctid value.
  // - Hash Partition: ybctid -> hashcode -> key -> partition index.
  // - Range Partition: ybctid == key -> partition index.
  string partition_key = VERIFY_RESULT(DecodeYbctid(ybctid));
  return client::FindPartitionStartIndex(table_partitions_->keys, partition_key);
}

Status PgTableDesc::SetScanBoundary(PgsqlReadRequestPB *req,
                                    const string& partition_lower_bound,
                                    bool lower_bound_is_inclusive,
                                    const string& partition_upper_bound,
                                    bool upper_bound_is_inclusive) {
  // Setup lower boundary.
  if (!partition_lower_bound.empty()) {
    req->mutable_lower_bound()->set_key(partition_lower_bound);
    req->mutable_lower_bound()->set_is_inclusive(lower_bound_is_inclusive);
  }

  // Setup upper boundary.
  if (!partition_upper_bound.empty()) {
    req->mutable_upper_bound()->set_key(partition_upper_bound);
    req->mutable_upper_bound()->set_is_inclusive(upper_bound_is_inclusive);
  }

  return Status::OK();
}

const client::YBTableName& PgTableDesc::table_name() const {
  return table_->name();
}

size_t PgTableDesc::num_hash_key_columns() const {
  return schema().num_hash_key_columns();
}

size_t PgTableDesc::num_key_columns() const {
  return schema().num_key_columns();
}

size_t PgTableDesc::num_columns() const {
  return schema().num_columns();
}

const PartitionSchema& PgTableDesc::partition_schema() const {
  return table_->partition_schema();
}

const Schema& PgTableDesc::schema() const {
  return table_->InternalSchema();
}

uint32_t PgTableDesc::schema_version() const {
  return table_->schema().version();
}

std::unique_ptr<client::YBPgsqlWriteOp> PgTableDesc::NewPgsqlInsert() {
  return client::YBPgsqlWriteOp::NewInsert(table_);
}

std::unique_ptr<client::YBPgsqlWriteOp> PgTableDesc::NewPgsqlUpdate() {
  return client::YBPgsqlWriteOp::NewUpdate(table_);
}

std::unique_ptr<client::YBPgsqlWriteOp> PgTableDesc::NewPgsqlDelete() {
  return client::YBPgsqlWriteOp::NewDelete(table_);
}

std::unique_ptr<client::YBPgsqlWriteOp> PgTableDesc::NewPgsqlTruncateColocated() {
  return client::YBPgsqlWriteOp::NewTruncateColocated(table_);
}

std::unique_ptr<client::YBPgsqlReadOp> PgTableDesc::NewPgsqlSelect() {
  return client::YBPgsqlReadOp::NewSelect(table_);
}

std::unique_ptr<client::YBPgsqlReadOp> PgTableDesc::NewPgsqlSample() {
  return client::YBPgsqlReadOp::NewSample(table_);
}

}  // namespace pggate
}  // namespace yb
