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

#include "yb/docdb/doc_reader.h"

#include <string>
#include <vector>

#include "yb/common/doc_hybrid_time.h"
#include "yb/common/hybrid_time.h"
#include "yb/common/ql_type.h"
#include "yb/common/schema.h"
#include "yb/common/transaction.h"

#include "yb/docdb/docdb-internal.h"
#include "yb/docdb/docdb_fwd.h"
#include "yb/docdb/docdb_rocksdb_util.h"
#include "yb/docdb/intent_aware_iterator.h"
#include "yb/docdb/read_operation_data.h"
#include "yb/docdb/shared_lock_manager_fwd.h"

#include "yb/dockv/doc_key.h"
#include "yb/dockv/doc_ttl_util.h"
#include "yb/dockv/pg_row.h"
#include "yb/dockv/reader_projection.h"
#include "yb/dockv/schema_packing.h"
#include "yb/dockv/subdocument.h"
#include "yb/dockv/value.h"
#include "yb/dockv/value_type.h"

#include "yb/qlexpr/ql_expr.h"

#include "yb/util/fast_varint.h"
#include "yb/util/logging.h"
#include "yb/util/monotime.h"
#include "yb/util/result.h"
#include "yb/util/status.h"

namespace yb {
namespace docdb {

using dockv::Expiration;
using dockv::SubDocument;
using dockv::ValueControlFields;
using dockv::ValueEntryType;

namespace {

constexpr int64_t kLivenessColumnIndex = -1;

template <class ResultType>
constexpr bool CheckExistOnly = std::is_same_v<ResultType, std::nullptr_t>;

// The struct that stores encoded doc hybrid time and decode it on demand.
class LazyDocHybridTime {
 public:
  void Assign(const EncodedDocHybridTime& value) {
    encoded_ = value;
    decoded_ = DocHybridTime();
  }

  const EncodedDocHybridTime& encoded() const {
    return encoded_;
  }

  EncodedDocHybridTime* RawPtr() {
    decoded_ = DocHybridTime();
    return &encoded_;
  }

  Result<DocHybridTime> decoded() const {
    if (!decoded_.is_valid()) {
      decoded_ = VERIFY_RESULT(encoded_.Decode());
    }
    return decoded_;
  }

  std::string ToString() const {
    return encoded_.ToString();
  }

 private:
  EncodedDocHybridTime encoded_;
  mutable DocHybridTime decoded_;
};

Slice NullSlice() {
  static char null_column_type = dockv::ValueEntryTypeAsChar::kNullLow;
  return Slice(&null_column_type, sizeof(null_column_type));
}

Expiration GetNewExpiration(
    const Expiration& parent_exp, MonoDelta ttl, HybridTime new_write_ht) {
  Expiration new_exp = parent_exp;
  // We may need to update the TTL in individual columns.
  if (new_write_ht >= new_exp.write_ht) {
    // We want to keep the default TTL otherwise.
    if (ttl != ValueControlFields::kMaxTtl) {
      new_exp.write_ht = new_write_ht;
      new_exp.ttl = ttl;
    } else if (new_exp.ttl.IsNegative()) {
      new_exp.ttl = -new_exp.ttl;
    }
  }

  // If the hybrid time is kMin, then we must be using default TTL.
  if (new_exp.write_ht == HybridTime::kMin) {
    new_exp.write_ht = new_write_ht;
  }

  return new_exp;
}

int64_t GetTtlRemainingSeconds(
    HybridTime read_time, HybridTime ttl_write_time, const Expiration& expiration) {
  if (!expiration) {
    return -1;
  }

  int64_t expiration_time_us =
      ttl_write_time.GetPhysicalValueMicros() + expiration.ttl.ToMicroseconds();
  int64_t remaining_us = expiration_time_us - read_time.GetPhysicalValueMicros();
  if (remaining_us <= 0) {
    return 0;
  }
  return remaining_us / MonoTime::kMicrosecondsPerSecond;
}

template <class ResultType>
std::string ResultAsString(const ResultType* result) {
  return AsString(*DCHECK_NOTNULL(result));
}

std::string ResultAsString(std::nullptr_t) {
  return "<NULL>";
}

std::string ResultAsString(const SubDocument* result) {
  return result->ToString(true);
}

YB_STRONGLY_TYPED_BOOL(NeedValue);

Slice AdjustRootDocKey(KeyBuffer* root_doc_key) {
  // Append kHighest to the root doc key, so it could serve as upperbound.
  root_doc_key->PushBack(dockv::KeyEntryTypeAsChar::kHighest);
  return root_doc_key->AsSlice().WithoutSuffix(1);
}

} // namespace

Result<DocHybridTime> GetTableTombstoneTime(
    Slice root_doc_key, const DocDB& doc_db,
    const TransactionOperationContext& txn_op_context,
    const ReadOperationData& read_operation_data) {
  dockv::DocKeyDecoder decoder(root_doc_key);
  RETURN_NOT_OK(decoder.DecodeToKeys());

  Slice table_id(root_doc_key.data(), decoder.left_input().data());

  if (table_id.empty()) {
    return DocHybridTime::kInvalid;
  }

  auto group_end = dockv::KeyEntryTypeAsChar::kGroupEnd;
  KeyBuffer table_id_buf(table_id, Slice(&group_end, 1));
  table_id = table_id_buf.AsSlice();

  auto iter = CreateIntentAwareIterator(
      doc_db, BloomFilterMode::USE_BLOOM_FILTER, table_id, rocksdb::kDefaultQueryId, txn_op_context,
      read_operation_data);
  iter->Seek(table_id);
  const auto& entry_data = VERIFY_RESULT_REF(iter->Fetch());
  if (!entry_data || !entry_data.value.FirstByteIs(dockv::ValueEntryTypeAsChar::kTombstone) ||
      entry_data.key != table_id) {
    return DocHybridTime::kInvalid;
  }

  return entry_data.write_time.Decode();
}

  // TODO(dtxn) scan through all involved transactions first to cache statuses in a batch,
  // so during building subdocument we don't need to request them one by one.
  // TODO(dtxn) we need to restart read with scan_ht = commit_ht if some transaction was committed
  // at time commit_ht within [scan_ht; read_request_time + max_clock_skew). Also we need
  // to wait until time scan_ht = commit_ht passed.
  // TODO(dtxn) for each scanned key (and its subkeys) we need to avoid *new* values committed at
  // ht <= scan_ht (or just ht < scan_ht?)
  // Question: what will break if we allow later commit at ht <= scan_ht ? Need to write down
  // detailed example.

Result<std::optional<SubDocument>> TEST_GetSubDocument(
    Slice sub_doc_key,
    const DocDB& doc_db,
    const rocksdb::QueryId query_id,
    const TransactionOperationContext& txn_op_context,
    const ReadOperationData& read_operation_data,
    const dockv::ReaderProjection* projection) {
  auto iter = CreateIntentAwareIterator(
      doc_db, BloomFilterMode::USE_BLOOM_FILTER, sub_doc_key, query_id,
      txn_op_context, read_operation_data);
  DOCDB_DEBUG_LOG("GetSubDocument for key $0 @ $1", sub_doc_key.ToDebugHexString(),
                  iter->read_time().ToString());

  iter->Seek(sub_doc_key);
  const auto& fetched = VERIFY_RESULT_REF(iter->Fetch());
  if (!fetched || !fetched.key.starts_with(sub_doc_key)) {
    return std::nullopt;
  }

  dockv::SchemaPackingStorage schema_packing_storage(TableType::YQL_TABLE_TYPE);
  DocDBTableReader doc_reader(
      iter.get(), read_operation_data.deadline, projection, TableType::YQL_TABLE_TYPE,
      schema_packing_storage);
  RETURN_NOT_OK(doc_reader.UpdateTableTombstoneTime(VERIFY_RESULT(GetTableTombstoneTime(
      sub_doc_key, doc_db, txn_op_context, read_operation_data))));
  SubDocument result;
  KeyBuffer key_buffer(sub_doc_key);
  if (VERIFY_RESULT(doc_reader.Get(&key_buffer, fetched, &result)) != DocReaderResult::kNotFound) {
    return result;
  }
  return std::nullopt;
}

// Shared information about packed row. I.e. common for all columns in this row.
class DocDBTableReader::PackedRowData {
 public:
  PackedRowData(
      DocDBTableReader* reader,
      std::reference_wrapper<const dockv::SchemaPackingStorage> schema_packing_storage)
      : reader_(*DCHECK_NOTNULL(reader)), schema_packing_storage_(schema_packing_storage) {
  }

  Result<ValueControlFields> ObtainControlFields(bool liveness_column, Slice* value) {
    if (liveness_column) {
      return control_fields_;
    }

    return VERIFY_RESULT(ValueControlFields::Decode(value));
  }

  auto GetTimestamp(const ValueControlFields& control_fields) const {
    return control_fields.has_timestamp() ? control_fields.timestamp : control_fields_.timestamp;
  }

  const LazyDocHybridTime& doc_ht() const {
    return *DCHECK_NOTNULL(doc_ht_);
  }

  template <class ColumnDecoder>
  Status Decode(
      Slice value, const LazyDocHybridTime* doc_ht, const ValueControlFields& control_fields,
      ColumnDecoder column_decoder) {
    DVLOG_WITH_FUNC(4)
        << "value: " << value.ToDebugHexString() << ", control fields: "
        << control_fields.ToString() << ", doc_ht: " << doc_ht->ToString();

    doc_ht_ = doc_ht;
    control_fields_ = control_fields;

    if (!schema_packing_version_.empty() &&
        value.starts_with(schema_packing_version_.AsSlice())) {
      value.remove_prefix(schema_packing_version_.size());
    } else {
      RETURN_NOT_OK(UpdateSchemaPacking(&value));
    }

    const auto& projection = *DCHECK_NOTNULL(reader_.projection_);
    auto projection_index = projection.num_key_columns;
    auto num_value_columns = projection.num_value_columns();
    for (size_t index = 0; index != num_value_columns; ++index, ++projection_index) {
      auto packed_index = packed_index_[index];
      if (packed_index == dockv::SchemaPacking::kSkippedColumnIdx) {
        DVLOG_WITH_FUNC(4) << "no packed index for: " << index;
        column_decoder(projection_index, std::nullopt);
        continue;
      }
      auto column_value = schema_packing_->GetValue(packed_index, value);
      DVLOG_WITH_FUNC(4) << "packed index: " << packed_index << ", value: " << column_value;
      // Remove buggy intent_doc_ht from start of the column. See #16650 for details.
      if (column_value.TryConsumeByte(dockv::KeyEntryTypeAsChar::kHybridTime)) {
        RETURN_NOT_OK(DocHybridTime::EncodedFromStart(&column_value));
      }
      if (column_value.empty()) {
        column_value = NullSlice();
      }
      RETURN_NOT_OK(column_decoder(projection_index, column_value));
    }

    return Status::OK();
  }

  Status UpdateSchemaPacking(Slice* value) {
    const auto* start = value->cdata();
    value->consume_byte();
    schema_packing_ = &VERIFY_RESULT(schema_packing_storage_.GetPacking(value)).get();
    schema_packing_version_.Assign(start, value->cdata());

    packed_index_.clear();
    packed_index_.reserve(reader_.projection_->num_value_columns());
    for (const auto& column : reader_.projection_->value_columns()) {
      packed_index_.push_back(schema_packing_->GetIndex(column.id));
    }
    return Status::OK();
  }

 private:
  DocDBTableReader& reader_;
  const dockv::SchemaPackingStorage& schema_packing_storage_;

  const dockv::SchemaPacking* schema_packing_ = nullptr;
  ByteBuffer<0x10> schema_packing_version_;
  boost::container::small_vector<int64_t, 0x10> packed_index_;

  const LazyDocHybridTime* doc_ht_;
  ValueControlFields control_fields_;
};

DocDBTableReader::DocDBTableReader(
    IntentAwareIterator* iter, CoarseTimePoint deadline,
    const dockv::ReaderProjection* projection,
    TableType table_type,
    std::reference_wrapper<const dockv::SchemaPackingStorage> schema_packing_storage)
    : iter_(iter),
      deadline_info_(deadline),
      projection_(projection),
      table_type_(table_type),
      packed_row_(new PackedRowData(this, schema_packing_storage)) {
  if (!projection_) {
    return;
  }

  encoded_projection_.resize(projection_->num_value_columns() + 1);
  dockv::KeyEntryValue::kLivenessColumn.AppendToKey(&encoded_projection_[0]);
  size_t i = 0;
  for (const auto& column : projection->value_columns()) {
    column.subkey.AppendToKey(&encoded_projection_[++i]);
  }
  VLOG_WITH_FUNC(4)
      << "Projection: " << AsString(*projection_) << ", read time: " << iter_->read_time();
}

DocDBTableReader::~DocDBTableReader() = default;

void DocDBTableReader::SetTableTtl(const Schema& table_schema) {
  table_expiration_ = Expiration(dockv::TableTTL(table_schema));
}

Status DocDBTableReader::UpdateTableTombstoneTime(DocHybridTime doc_ht) {
  if (doc_ht.is_valid()) {
    table_tombstone_time_.Assign(doc_ht);
  }
  return Status::OK();
}

SubDocument* AllocateChild(SubDocument* parent, const dockv::KeyEntryValue& key) {
  return &parent->AllocateChild(key);
}

auto GetChild(SubDocument* entry, const dockv::KeyEntryValue& key) {
  return entry ? entry->GetChild(key) : nullptr;
}

auto GetChild(std::nullptr_t, const dockv::KeyEntryValue& key) {
  return nullptr;
}

bool DeleteChild(SubDocument* entry, const dockv::KeyEntryValue& key) {
  entry->DeleteChild(key);
  return entry->object_num_keys() == 0;
}

bool NeedAllocate(SubDocument* entry) {
  return !entry;
}

std::nullptr_t AllocateChild(std::nullptr_t parent, const dockv::KeyEntryValue& key) {
  return nullptr;
}

bool DeleteChild(std::nullptr_t entry, const dockv::KeyEntryValue& key) {
  return false;
}

bool NeedAllocate(std::nullptr_t entry) {
  return false;
}

void ClearCollection(SubDocument* entry) {
  if (IsCollectionType(entry->type())) {
    entry->object_container().clear();
  }
}

void ClearCollection(std::nullptr_t entry) {
}

// Scan state entry. See state_ description below for details.
template <class Out>
struct StateEntryTemplate {
  dockv::KeyBytes key_entry; // Represents the part of the key that is related to this state entry.
  LazyDocHybridTime write_time;
  Expiration expiration;
  dockv::KeyEntryValue key_value; // Decoded key_entry.
  Out out;

  std::string ToString() const {
    return YB_STRUCT_TO_STRING(write_time, expiration, key_value);
  }
};

template <class Out>
Out EnsureOut(StateEntryTemplate<Out>* entry) {
  if (NeedAllocate(entry->out)) {
    entry->out = AllocateChild(EnsureOut(entry - 1), entry->key_value);
  }
  return entry->out;
}

Status DecodeFromValue(Slice value_slice, dockv::PrimitiveValue* out) {
  return out->DecodeFromValue(value_slice);
}

Status DecodeFromValue(Slice value_slice, std::nullptr_t out) {
  return Status::OK();
}

template <class Entry>
class StateEntryConverter {
 public:
  StateEntryConverter(Entry* entry, bool* became_empty)
      : entry_(entry), became_empty_(became_empty) {}

  void Decode(std::nullopt_t, DataType = DataType::NULL_VALUE_TYPE) {
    if (entry_->out == nullptr) {
      return;
    }

    entry_->out = nullptr;
    *became_empty_ = DeleteChild(entry_[-1].out, entry_->key_value) || *became_empty_;
  }

  Status Decode(Slice value_slice, DataType) {
    return DecodeFromValue(value_slice, EnsureOut(entry_));
  }

  auto Out() {
    return EnsureOut(entry_);
  }

 private:
  Entry* entry_;
  bool* became_empty_;
};

// Returns true if value is NOT tombstone, false if value is tombstone.
Result<bool> TryDecodeValueOnly(
    Slice value_slice, DataType data_type, QLValuePB* out) {
  if (dockv::DecodeValueEntryType(value_slice) == ValueEntryType::kTombstone) {
    out->Clear();
    return false;
  }
  if (data_type != DataType::NULL_VALUE_TYPE) {
    RETURN_NOT_OK(dockv::PrimitiveValue::DecodeToQLValuePB(value_slice, data_type, out));
  } else {
    out->Clear();
  }
  return true;
}

Result<bool> TryDecodeValueOnly(
    Slice value_slice, DataType, dockv::PrimitiveValue* out) {
  DCHECK_ONLY_NOTNULL(out);
  if (dockv::DecodeValueEntryType(value_slice) == ValueEntryType::kTombstone) {
    *out = dockv::PrimitiveValue::kTombstone;
    return false;
  }
  RETURN_NOT_OK(out->DecodeFromValue(value_slice));
  return true;
}

void SetNullResult(const dockv::ReaderProjection& projection, qlexpr::QLTableRow* out) {
  for (const auto& column : projection.value_columns()) {
    out->MarkTombstoned(column.id);
  }
}

class DocDbToQLTableRowConverter {
 public:
  DocDbToQLTableRowConverter(qlexpr::QLTableRow* row, ColumnId column)
      : row_(*DCHECK_NOTNULL(row)), column_(column) {}

  void Decode(std::nullopt_t, DataType) {
    row_.MarkTombstoned(column_);
  }

  Status Decode(Slice value_slice, DataType data_type) {
    if (data_type != DataType::NULL_VALUE_TYPE) {
      return dockv::PrimitiveValue::DecodeToQLValuePB(
          value_slice, data_type, &row_.AllocColumn(column_).value);
    }

    row_.MarkTombstoned(column_);
    return Status::OK();
  }

 private:
  qlexpr::QLTableRow& row_;
  ColumnId column_;
};

inline auto MakeConverter(qlexpr::QLTableRow* row, size_t, ColumnId column) {
  return DocDbToQLTableRowConverter(row, column);
}

template <class Value>
auto DecodePackedColumn(
    qlexpr::QLTableRow* row, size_t index, Value value, const dockv::ReaderProjection& projection) {
  const auto& projected_column = projection.columns[index];
  return MakeConverter(row, index, projected_column.id).Decode(value, projected_column.data_type);
}

void SetNullResult(const dockv::ReaderProjection& projection, dockv::PgTableRow* out) {
  out->SetNull();
}

class DocDbToPgTableRowConverter {
 public:
  DocDbToPgTableRowConverter(dockv::PgTableRow* row, size_t column_index)
      : row_(*DCHECK_NOTNULL(row)), column_index_(column_index) {}

  void Decode(std::nullopt_t, DataType) {
    row_.SetNull(column_index_);
  }

  Status Decode(Slice value_slice, DataType data_type) {
    DVLOG_WITH_FUNC(4)
        << "value: " << value_slice.ToDebugHexString() << ", column index: " << column_index_;

    if (data_type == DataType::NULL_VALUE_TYPE) {
      return Status::OK();
    }
    return row_.DecodeValue(column_index_, value_slice);
  }

 private:
  dockv::PgTableRow& row_;
  size_t column_index_;
};

inline auto MakeConverter(dockv::PgTableRow* row, size_t column_index, ColumnId) {
  return DocDbToPgTableRowConverter(row, column_index);
}

auto DecodePackedColumn(
    dockv::PgTableRow* row, size_t index, Slice value, const dockv::ReaderProjection&) {
  return row->DecodeValue(index, value);
}

auto DecodePackedColumn(
    dockv::PgTableRow* row, size_t index, std::nullopt_t value, const dockv::ReaderProjection&) {
  return row->SetNull(index);
}

class NullPtrRowConverter {
 public:
  void Decode(std::nullopt_t, DataType = DataType::NULL_VALUE_TYPE) {
  }

  Status Decode(Slice value_slice, DataType = DataType::NULL_VALUE_TYPE) {
    return Status::OK();
  }
};

inline auto MakeConverter(std::nullptr_t row, size_t column_index, ColumnId) {
  return NullPtrRowConverter();
}

template <class Value>
auto DecodePackedColumn(
    std::nullptr_t row, size_t index, Value value, const dockv::ReaderProjection& projection) {
  return NullPtrRowConverter().Decode(value);
}

void SetNullResult(const dockv::ReaderProjection& projection, std::nullptr_t out) {
}

template <class Converter> requires (!std::is_pointer_v<Converter>)
Result<bool> TryDecodeValueOnly(
    Slice value_slice, DataType data_type, Converter converter) {
  if (dockv::DecodeValueEntryType(value_slice) == ValueEntryType::kTombstone) {
    converter.Decode(std::nullopt, data_type);
    return false;
  }
  RETURN_NOT_OK(converter.Decode(value_slice, data_type));
  return true;
}

Result<bool> TryDecodeValueOnly(Slice value_slice, DataType data_type, std::nullptr_t) {
  return dockv::DecodeValueEntryType(value_slice) != ValueEntryType::kTombstone;
}

DocReaderResult FoundResult(bool iter_valid) {
  return iter_valid ? DocReaderResult::kFoundNotFinished : DocReaderResult::kFoundAndFinished;
}

Status DecodeRowValue(Slice row_value, SubDocument* result) {
  SubDocument temp(dockv::DecodeValueEntryType(row_value));
  RETURN_NOT_OK(temp.DecodeFromValue(row_value));
  *result = temp;
  return Status::OK();
}

Status DecodeRowValue(Slice row_value, std::nullptr_t) {
  return Status::OK();
}

// Implements main logic in the reader.
// Used keep scan state and avoid passing it between methods.
// It is less performant than FlatGetHelper, but handles the general case of nested documents.
// Not used for YSQL if FLAGS_ysql_use_flat_doc_reader is true.
template <bool is_flat_doc, bool ysql, bool check_exists_only>
class DocDBTableReader::GetHelperBase {
 public:
  static constexpr bool kIsFlatDoc = is_flat_doc;
  static constexpr bool kYsql = ysql;
  static constexpr bool kCheckExistOnly = check_exists_only;

  GetHelperBase(DocDBTableReader* reader, KeyBuffer* root_doc_key)
      : reader_(*DCHECK_NOTNULL(reader)),
        root_doc_key_buffer_(root_doc_key),
        root_doc_key_(AdjustRootDocKey(root_doc_key)),
        upperbound_scope_(root_doc_key->AsSlice(), reader_.iter_) {
  }

  virtual ~GetHelperBase() {
    root_doc_key_buffer_->PopBack();
  }

 protected:
  virtual bool CheckForRootValue() = 0;
  virtual std::string GetResultAsString() const = 0;
  virtual Status ProcessEntry(
      Slice subkeys, Slice value_slice, const EncodedDocHybridTime& write_time) = 0;
  virtual Status InitRowValue(
      Slice row_value, LazyDocHybridTime* root_write_time,
      const ValueControlFields& control_fields) = 0;

  Result<DocReaderResult> DoRun(
      const FetchedEntry& prefetched_key, LazyDocHybridTime* root_write_time) {
    auto& fetched_key = VERIFY_RESULT_REF(Prepare(prefetched_key, root_write_time));

    if (kCheckExistOnly) {
      if (found_) {
        return FoundResult(/* iter_valid= */ true);
      }
      auto iter_valid = VERIFY_RESULT(Scan(&fetched_key));
      return found_ ? FoundResult(iter_valid) : DocReaderResult::kNotFound;
    }

    if (!reader_.projection_) {
      // projection could be null in tests only.
      cannot_scan_columns_ = true;
    }

    auto iter_valid = VERIFY_RESULT(Scan(&fetched_key));

    if (found_ ||
        CheckForRootValue()) { // Could only happen in tests.
      return FoundResult(iter_valid);
    }

    return DocReaderResult::kNotFound;
  }

  // Scans DocDB for entries related to root_doc_key_.
  // Iterator should already point to the first such entry.
  // Changes nearly all internal state fields.
  Result<bool> Scan(const FetchedEntry* fetched_key) {
    DCHECK_ONLY_NOTNULL(fetched_key);
    if (!*fetched_key) {
      RETURN_NOT_OK(reader_.deadline_info_.CheckDeadlinePassed());
      return false;
    }
    for (;;) {
      RETURN_NOT_OK(reader_.deadline_info_.CheckDeadlinePassed());

      if (!VERIFY_RESULT(HandleRecord(*fetched_key))) {
        return true;
      }

      fetched_key = &VERIFY_RESULT_REF(reader_.iter_->Fetch());
      if (!*fetched_key) {
        break;
      }
      DVLOG_WITH_PREFIX_AND_FUNC(4)
          << "new position: " << dockv::SubDocKey::DebugSliceToString(fetched_key->key)
          << ", value: " << dockv::Value::DebugSliceToString(fetched_key->value);
    }
    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "found: " << found_ << ", column index: " << column_index_ << ", result: "
        << GetResultAsString();
    return false;
  }

  Result<bool> HandleRecord(const FetchedEntry& key_result) {
    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "key: " << dockv::SubDocKey::DebugSliceToString(key_result.key) << ", write time: "
        << key_result.write_time.ToString() << ", value: "
        << key_result.value.ToDebugHexString();
    DCHECK(key_result.key.starts_with(root_doc_key_));
    auto subkeys = key_result.key.WithoutPrefix(root_doc_key_.size());

    return DoHandleRecord(key_result, subkeys);
  }

  Result<bool> DoHandleRecord(
      const FetchedEntry& key_result, Slice subkeys) {
    if (!kCheckExistOnly && reader_.projection_) {
      auto projection_column_encoded_key_prefix = CurrentEncodedProjection();
      int compare_result = subkeys.compare_prefix(projection_column_encoded_key_prefix);
      DVLOG_WITH_PREFIX_AND_FUNC(4)
          << "Subkeys: " << subkeys.ToDebugHexString()
          << ", column: " << current_column_->subkey
          << ", compare_result: " << compare_result;
      if (compare_result < 0) {
        SeekProjectionColumn();
        return true;
      }

      if (compare_result > 0) {
        if (!VERIFY_RESULT(NextColumn())) {
          return false;
        }

        return DoHandleRecord(key_result, subkeys);
      }

      if (kIsFlatDoc) {
        SCHECK_EQ(
            subkeys.size(), projection_column_encoded_key_prefix.size(), IllegalState,
            "DocDBTableReader::FlatGetHelper supports at most 1 subkey");
      }
    }

    RETURN_NOT_OK(ProcessEntry(subkeys, key_result.value, key_result.write_time));
    if (kCheckExistOnly && found_) {
      return false;
    }
    reader_.iter_->SeekPastSubKey(key_result.key);
    return true;
  }

  // We are not yet reached next projection subkey, seek to it.
  void SeekProjectionColumn() {
    root_key_entry_->AppendRawBytes(CurrentEncodedProjection());
    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "Seek next column: "
        << dockv::SubDocKey::DebugSliceToString(*root_key_entry_);
    reader_.iter_->SeekForward(root_key_entry_->AsSlice());
    root_key_entry_->Truncate(root_doc_key_.size());
  }

  Result<bool> NextColumn() {
    ++column_index_;
    if (column_index_ == make_signed(reader_.projection_->num_value_columns())) {
      return false;
    }
    if (column_index_ == 0) {
      current_column_ = &reader_.projection_->value_column(0);
    } else {
      ++current_column_;
    }
    return true;
  }

  Result<const FetchedEntry&> Prepare(
      const FetchedEntry& key_result, LazyDocHybridTime* root_write_time) {
    DVLOG_WITH_PREFIX_AND_FUNC(4) << "Pos: " << reader_.iter_->DebugPosToString();

    root_key_entry_->AppendRawBytes(root_doc_key_);

    DCHECK(key_result.key.starts_with(root_doc_key_));

    root_write_time->Assign(reader_.table_tombstone_time_);
    if (root_doc_key_.size() != key_result.key.size() ||
        key_result.write_time < root_write_time->encoded()) {
      RETURN_NOT_OK(InitRowValue(Slice(), root_write_time, ValueControlFields()));
      return key_result;
    }

    root_write_time->Assign(key_result.write_time);

    auto value = key_result.value;
    auto control_fields = VERIFY_RESULT(ValueControlFields::Decode(&value));

    RETURN_NOT_OK(InitRowValue(value, root_write_time, control_fields));

    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "Write time: " << root_write_time->ToString() << ", control fields: "
        << control_fields.ToString();
    reader_.iter_->Next();
    return reader_.iter_->Fetch();
  }

  bool IsObsolete(const Expiration& expiration) {
    if (expiration.ttl == ValueControlFields::kMaxTtl) {
      return false;
    }

    return dockv::HasExpiredTTL(
        expiration.write_ht, expiration.ttl, reader_.iter_->read_time().read);
  }

  std::string LogPrefix() const {
    return dockv::DocKey::DebugSliceToString(root_doc_key_) +
           (kCheckExistOnly ? "[?]: " : ": ");
  }

  Slice CurrentEncodedProjection() const {
    // The liveness column is inserted at the begining of encoded projection, so we get +1 here.
    return reader_.encoded_projection_[column_index_ + 1].AsSlice();
  }

  static constexpr bool TtlCheckRequired() {
    // TODO(scanperf) also avoid checking TTL for YCQL tables w/o TTL.
    return !kYsql;
  }

  static const dockv::ProjectedColumn& ProjectedLivenessColumn() {
    static dockv::ProjectedColumn kProjectedLivenessColumn = {
      .id = ColumnId(dockv::KeyEntryValue::kLivenessColumn.GetColumnId()),
      .subkey = dockv::KeyEntryValue::kLivenessColumn,
      .data_type = DataType::NULL_VALUE_TYPE,
    };
    return kProjectedLivenessColumn;
  }

  DocDBTableReader& reader_;
  KeyBuffer* root_doc_key_buffer_;
  Slice old_upperbound_;
  Slice root_doc_key_;
  // Pointer to root key entry that is owned by subclass. Can't be nullptr.
  dockv::KeyBytes* root_key_entry_;

  // Index of the current column in projection.
  int64_t column_index_ = kLivenessColumnIndex;
  const dockv::ProjectedColumn* current_column_ = &ProjectedLivenessColumn();

  // Set to true when there is no projection or root is not an object (that only can happen when
  // called from the tests).
  bool cannot_scan_columns_ = false;

  // Whether we found row related value or not.
  bool found_ = false;

  IntentAwareIteratorUpperboundScope upperbound_scope_;
};

// Implements main logic in the reader.
// Used keep scan state and avoid passing it between methods.
// When we just check if row exists, then ResultType will be std::nullptr_t, to mark that
// we don't need to decode actual value.
template <class ResultType>
using BaseOfGetHelper = DocDBTableReader::GetHelperBase<
        /* is_flat_doc= */ false, /* ysql= */ false, CheckExistOnly<ResultType>>;

template <class ResultType>
class DocDBTableReader::GetHelper : public BaseOfGetHelper<ResultType> {
 public:
  using Base = BaseOfGetHelper<ResultType>;
  using StateEntry = StateEntryTemplate<ResultType>;

  GetHelper(DocDBTableReader* reader, KeyBuffer* root_doc_key, ResultType result)
      : Base(reader, root_doc_key), result_(result) {
    state_.emplace_back(StateEntry {
      .key_entry = dockv::KeyBytes(),
      .write_time = LazyDocHybridTime(),
      .expiration = reader_.table_expiration_,
      .key_value = {},
      .out = result_,
    });
    root_key_entry_ = &state_.front().key_entry;
  }

  Result<DocReaderResult> Run(const FetchedEntry& fetched_entry) {
    return Base::DoRun(fetched_entry, &state_.front().write_time);
  }

  std::string GetResultAsString() const override { return ResultAsString(result_); }

  bool CheckForRootValue() override {
    if (!has_root_value_) {
      return false;
    }
    ClearCollection(result_);
    return true;
  }

  Status ProcessEntry(
      Slice subkeys, Slice value_slice, const EncodedDocHybridTime& write_time) override {
    subkeys = CleanupState(subkeys);
    if (state_.back().write_time.encoded() >= write_time) {
      DVLOG_WITH_PREFIX_AND_FUNC(4)
          << "State: " << AsString(state_) << ", write_time: " << write_time;
      return Status::OK();
    }
    auto control_fields = VERIFY_RESULT(ValueControlFields::Decode(&value_slice));
    RETURN_NOT_OK(AllocateNewStateEntries(
        subkeys, write_time, control_fields.ttl));
    return ApplyEntryValue(value_slice, control_fields);
  }

  void DecodePackedColumn(
      std::nullopt_t, const dockv::ProjectedColumn* projected_column) {
    AllocateChild(result_, projected_column->subkey);
  }

  Status DoDecodePackedColumn(
      Slice value, const dockv::ProjectedColumn* projected_column, std::nullptr_t) {
    return Status::OK();
  }

  Status DoDecodePackedColumn(
      Slice value, const dockv::ProjectedColumn* projected_column, SubDocument* out) {
    auto control_fields = VERIFY_RESULT(reader_.packed_row_->ObtainControlFields(
        projected_column == &ProjectedLivenessColumn(), &value));
    const auto& write_time = reader_.packed_row_->doc_ht();
    const auto expiration = GetNewExpiration(
          state_.back().expiration, control_fields.ttl,
          VERIFY_RESULT(write_time.decoded()).hybrid_time());

    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "column: " << projected_column->ToString() << ", value: " << value.ToDebugHexString()
        << ", control_fields: " << control_fields.ToString() << ", write time: "
        << write_time.decoded().ToString() << ", expiration: " << expiration.ToString()
        << ", obsolete: " << IsObsolete(expiration);

    if (IsObsolete(expiration)) {
      return Status::OK();
    }

    RETURN_NOT_OK(TryDecodeValue(
        reader_.packed_row_->GetTimestamp(control_fields), write_time, expiration, value, out));
    found_ = true;
    return Status::OK();
  }

  Status DecodePackedColumn(Slice value, const dockv::ProjectedColumn* projected_column) {
    return DoDecodePackedColumn(
        value, projected_column, AllocateChild(result_, projected_column->subkey));
  }

  Result<bool> TryDecodeValue(
      UserTimeMicros timestamp, const LazyDocHybridTime& write_time, const Expiration& expiration,
      Slice value_slice, dockv::SubDocument* out) {
    if (!VERIFY_RESULT(TryDecodeValueOnly(value_slice, current_column_->data_type, out))) {
      return false;
    }

    RETURN_NOT_OK(ProcessControlFields(timestamp, write_time, expiration, out));

    return true;
  }

  template <class Out>
  Result<bool> TryDecodeValue(
      UserTimeMicros timestamp, const LazyDocHybridTime& write_time, const Expiration& expiration,
      Slice value_slice, Out out_provider) {
    if (!VERIFY_RESULT(TryDecodeValueOnly(value_slice, current_column_->data_type, out_provider))) {
      return false;
    }

    RETURN_NOT_OK(ProcessControlFields(timestamp, write_time, expiration, out_provider.Out()));

    return true;
  }

  Status ProcessControlFields(
      UserTimeMicros timestamp, const LazyDocHybridTime& write_time, const Expiration& expiration,
      dockv::PrimitiveValue* out) {
    auto write_ht = VERIFY_RESULT(write_time.decoded()).hybrid_time();
    if (timestamp != ValueControlFields::kInvalidTimestamp) {
      out->SetWriteTime(timestamp);
    } else {
      out->SetWriteTime(write_ht.GetPhysicalValueMicros());
    }
    out->SetTtl(GetTtlRemainingSeconds(reader_.iter_->read_time().read, write_ht, expiration));

    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "write_ht: " << write_ht << ", timestamp: " << timestamp << ", expiration: "
        << expiration.ToString() << ", out: " << out->ToString(true);
    return Status::OK();
  }

  Status ProcessControlFields(
      UserTimeMicros timestamp, const LazyDocHybridTime& write_time, const Expiration& expiration,
      std::nullptr_t) {
    return Status::OK();
  }

  Result<bool> TryDecodeValue(
      UserTimeMicros timestamp, const LazyDocHybridTime& write_time, const Expiration& expiration,
      Slice value_slice, std::nullptr_t out) {
    return VERIFY_RESULT(TryDecodeValueOnly(value_slice, current_column_->data_type, out));
  }

  Status InitRowValue(
      Slice row_value, LazyDocHybridTime* root_write_time,
      const ValueControlFields& control_fields) override {
    auto value_type = dockv::DecodeValueEntryType(row_value);
    if (value_type == ValueEntryType::kPackedRow) {
      RETURN_NOT_OK(reader_.packed_row_->Decode(
          row_value, root_write_time, control_fields, [this](size_t index, auto value){
            return DecodePackedColumn(value, &reader_.projection_->columns[index]);
          }));
      RETURN_NOT_OK(DecodePackedColumn(NullSlice(), &ProjectedLivenessColumn()));
      if (TtlCheckRequired()) {
        auto& root_expiration = state_.front().expiration;
        root_expiration = GetNewExpiration(
            root_expiration, ValueControlFields::kMaxTtl,
            VERIFY_RESULT(root_write_time->decoded()).hybrid_time());
      }
    } else if (value_type != ValueEntryType::kTombstone && value_type != ValueEntryType::kInvalid) {
      // Used in tests only
      has_root_value_ = true;
      found_ = true;
      if (value_type != ValueEntryType::kObject) {
        RETURN_NOT_OK(DecodeRowValue(row_value, result_));
        cannot_scan_columns_ = true;
      }
    }

    return Status::OK();
  }

 private:
  using Base::IsObsolete;
  using Base::LogPrefix;
  using Base::ProjectedLivenessColumn;
  using Base::TtlCheckRequired;
  using Base::cannot_scan_columns_;
  using Base::column_index_;
  using Base::current_column_;
  using Base::found_;
  using Base::reader_;
  using Base::root_key_entry_;

  // Removes state_ elements that are that are not related to the passed in subkeys.
  // Returns remaining part of subkeys, that not represented in state_.
  Slice CleanupState(Slice subkeys) {
    for (size_t i = 1; i != state_.size(); ++i) {
      if (!subkeys.starts_with(state_[i].key_entry)) {
        state_.resize(i);
        break;
      }
      subkeys.remove_prefix(state_[i].key_entry.size());
    }
    return subkeys;
  }

  Status AllocateNewStateEntries(
      Slice subkeys, const EncodedDocHybridTime& write_time, MonoDelta ttl) {
    LazyDocHybridTime lazy_write_time;
    lazy_write_time.Assign(write_time);
    while (!subkeys.empty()) {
      auto start = subkeys.data();
      state_.emplace_back();
      auto& entry = state_.back();
      auto& parent = (&entry)[-1];
      RETURN_NOT_OK(entry.key_value.DecodeFromKey(&subkeys));
      entry.key_entry.AppendRawBytes(Slice(start, subkeys.data()));
      entry.write_time = subkeys.empty() ? lazy_write_time : parent.write_time;
      entry.out = GetChild(parent.out, entry.key_value);
      if (TtlCheckRequired()) {
        entry.expiration = GetNewExpiration(
            parent.expiration, ttl, VERIFY_RESULT(entry.write_time.decoded()).hybrid_time());
      }
    }
    return Status::OK();
  }

  // Return true if entry value was accepted.
  Status ApplyEntryValue(
      Slice value_slice, const ValueControlFields& control_fields) {
    auto& current = state_.back();
    DVLOG_WITH_PREFIX_AND_FUNC(4)
        << "State: " << AsString(state_) << ", value: " << value_slice.ToDebugHexString()
        << ", obsolete: " << IsObsolete(current.expiration);

    bool became_empty = false;
    StateEntryConverter converter(&current, &became_empty);
    if (!IsObsolete(current.expiration)) {
      if (VERIFY_RESULT(TryDecodeValue(
              control_fields.timestamp, current.write_time, current.expiration, value_slice,
              converter))) {
        found_ = true;
        return Status::OK();
      }
    } else {
      converter.Decode(std::nullopt);
    }

    if (became_empty && state_.size() == 2) {
      found_ = false;
    }

    return Status::OK();
  }

  ResultType result_;

  // Scanning stack.
  // I.e. the first entry is related to whole document (i.e. row).
  // The second entry corresponds to column.
  // And other entries are list/map entries in case of complex documents.
  boost::container::small_vector<StateEntry, 4> state_;

  // Used in tests only, when we have value for root_doc_key_ itself.
  // In actual DB we don't have values for pure doc key.
  // Only delete marker, that is handled in a different way.
  bool has_root_value_ = false;
};

template <class ResultType>
using BaseOfFlatGetHelper = DocDBTableReader::GetHelperBase<
        /* is_flat_doc= */ true, /* ysql= */ true, CheckExistOnly<ResultType>>;

// It is more performant than DocDBTableReader::GetHelper, but can't handle the general case of
// nested documents that is possible in YCQL.
// Used for YSQL if FLAGS_ysql_use_flat_doc_reader is true.
template <class ResultType>
class DocDBTableReader::FlatGetHelper : public BaseOfFlatGetHelper<ResultType> {
 public:
  using Base = BaseOfFlatGetHelper<ResultType>;

  FlatGetHelper(
      DocDBTableReader* reader, KeyBuffer* root_doc_key, ResultType result)
      : Base(reader, root_doc_key), result_(result) {
    row_expiration_ = reader->table_expiration_;
    root_key_entry_ = &row_key_;
  }

  Result<DocReaderResult> Run(const FetchedEntry& fetched_entry) {
    return Base::DoRun(fetched_entry, &row_write_time_);
  }

  std::string GetResultAsString() const override { return ResultAsString(result_); }

  bool CheckForRootValue() override {
    return false;
  }

  // Return true if entry is more recent than packed row.
  Status ProcessEntry(
      Slice /* subkeys */, Slice value_slice, const EncodedDocHybridTime& write_time) override {
    if (row_write_time_.encoded() >= write_time) {
      DVLOG_WITH_PREFIX_AND_FUNC(4) << "write_time: " << write_time.ToString();
      return Status::OK();
    }

    const auto decode_result = VERIFY_RESULT(column_index_ == kLivenessColumnIndex
        ? TryDecodeValueOnly(value_slice, current_column_->data_type, /* out= */ nullptr)
        : TryDecodeValueOnly(
              value_slice, current_column_->data_type,
              MakeConverter(
                  result_, reader_.projection_->num_key_columns + column_index_,
                  current_column_->id)));

    if (decode_result) {
      found_ = true;
    }

    return Status::OK();
  }

  Status InitRowValue(
      Slice row_value, LazyDocHybridTime* root_write_time,
      const ValueControlFields& control_fields) override {
    DCHECK_ONLY_NOTNULL(reader_.projection_);
    auto value_type = dockv::DecodeValueEntryType(row_value);
    if (value_type != ValueEntryType::kPackedRow) {
      SetNullResult(*reader_.projection_, result_);
      return Status::OK();
    }
    found_ = true;
    if (Base::kCheckExistOnly) {
      return Status::OK();
    }
    return reader_.packed_row_->Decode(
        row_value, root_write_time, control_fields,
        [this](size_t index, auto value) {
      return DecodePackedColumn(result_, index, value, *reader_.projection_);
    });
  }

 private:
  using Base::IsObsolete;
  using Base::LogPrefix;
  using Base::TtlCheckRequired;
  using Base::column_index_;
  using Base::current_column_;
  using Base::found_;
  using Base::reader_;
  using Base::root_key_entry_;

  // Owned by the DocDBTableReader::FlatGetHelper user.
  ResultType result_;

  dockv::KeyBytes row_key_;
  LazyDocHybridTime row_write_time_;
  Expiration row_expiration_;
};

Result<DocReaderResult> DocDBTableReader::Get(
    KeyBuffer* root_doc_key, const FetchedEntry& fetched_entry, SubDocument* out) {
  {
    GetHelper<SubDocument*> helper(this, root_doc_key, DCHECK_NOTNULL(out));
    auto result = VERIFY_RESULT(helper.Run(fetched_entry));

    if (result != DocReaderResult::kNotFound) {
      return result;
    }
  }

  if (!projection_) { // Could only happen in tests.
    return DocReaderResult::kNotFound;
  }

  // In YCQL we could have value for column not listed in projection.
  // It means that other columns have NULL values, so if such column present, then
  // we should return row consisting of NULLs.
  // Here we check if there are columns values not listed in projection.
  iter_->Seek(root_doc_key->AsSlice());
  const auto& new_fetched_entry = VERIFY_RESULT_REF(iter_->Fetch());
  if (!new_fetched_entry) {
    return DocReaderResult::kNotFound;
  }

  GetHelper<std::nullptr_t> helper(this, root_doc_key, nullptr);
  return helper.Run(new_fetched_entry);
}

template <class Res>
Result<DocReaderResult> DocDBTableReader::DoGetFlat(
    KeyBuffer* root_doc_key, const FetchedEntry& fetched_entry, Res* result) {
  if (result == nullptr || !projection_->has_value_columns()) {
    FlatGetHelper<std::nullptr_t> helper(this, root_doc_key, nullptr);
    return helper.Run(fetched_entry);
  }

  FlatGetHelper<Res*> helper(this, root_doc_key, result);
  return helper.Run(fetched_entry);
}

Result<DocReaderResult> DocDBTableReader::GetFlat(
    KeyBuffer* root_doc_key, const FetchedEntry& fetched_entry, qlexpr::QLTableRow* result) {
  return DoGetFlat(root_doc_key, fetched_entry, result);
}

Result<DocReaderResult> DocDBTableReader::GetFlat(
    KeyBuffer* root_doc_key, const FetchedEntry& fetched_entry, dockv::PgTableRow* result) {
  if (result) {
    DCHECK_EQ(result->projection(), *projection_);
  }
  return DoGetFlat(root_doc_key, fetched_entry, result);
}

}  // namespace docdb
}  // namespace yb
