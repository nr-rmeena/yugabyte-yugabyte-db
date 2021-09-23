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

#include "yb/master/restore_sys_catalog_state.h"

#include "yb/common/entity_ids.h"
#include "yb/common/hybrid_time.h"
#include "yb/common/index.h"

#include "yb/common/pgsql_protocol.pb.h"
#include "yb/common/ql_expr.h"
#include "yb/docdb/consensus_frontier.h"
#include "yb/docdb/cql_operation.h"
#include "yb/docdb/docdb_rocksdb_util.h"
#include "yb/docdb/doc_rowwise_iterator.h"
#include "yb/docdb/doc_write_batch.h"
#include "yb/docdb/docdb.h"
#include "yb/docdb/pgsql_operation.h"

#include "yb/master/catalog_loaders.h"
#include "yb/master/master.pb.h"
#include "yb/master/master_backup.pb.h"
#include "yb/master/master_snapshot_coordinator.h"
#include "yb/master/master_util.h"
#include "yb/master/sys_catalog.h"
#include "yb/master/sys_catalog_writer.h"

#include "yb/rocksdb/write_batch.h"
#include "yb/tablet/tablet.h"
#include "yb/util/pb_util.h"

using namespace std::placeholders;

namespace yb {
namespace master {

namespace {

CHECKED_STATUS ApplyWriteRequest(
    const Schema& schema, QLWriteRequestPB* write_request,
    docdb::DocWriteBatch* write_batch) {
  std::shared_ptr<const Schema> schema_ptr(&schema, [](const Schema* schema){});
  docdb::DocOperationApplyData apply_data{.doc_write_batch = write_batch};
  IndexMap index_map;
  docdb::QLWriteOperation operation(schema_ptr, index_map, nullptr, boost::none);
  QLResponsePB response;
  RETURN_NOT_OK(operation.Init(write_request, &response));
  return operation.Apply(apply_data);
}

bool TableDeleted(const SysTablesEntryPB& table) {
  return table.state() == SysTablesEntryPB::DELETED ||
         table.state() == SysTablesEntryPB::DELETING ||
         table.hide_state() == SysTablesEntryPB::HIDING ||
         table.hide_state() == SysTablesEntryPB::HIDDEN;
}

Result<bool> MatchNamespace(
    const SnapshotScheduleFilterPB& filter, const NamespaceId& id,
    const SysNamespaceEntryPB& ns) {
  VLOG(1) << __func__ << "(" << id << ", " << ns.ShortDebugString() << ")";
  for (const auto& table_identifier : filter.tables().tables()) {
    if (table_identifier.has_namespace_() &&
        VERIFY_RESULT(master::NamespaceMatchesIdentifier(
            id, ns.database_type(), ns.name(), table_identifier.namespace_()))) {
      return true;
    }
  }
  return false;
}

Result<bool> MatchTable(
    const SnapshotScheduleFilterPB& filter, const TableId& id,
    const SysTablesEntryPB& table) {
  VLOG(1) << __func__ << "(" << id << ", " << table.ShortDebugString() << ")";
  for (const auto& table_identifier : filter.tables().tables()) {
    if (VERIFY_RESULT(master::TableMatchesIdentifier(id, table, table_identifier))) {
      return true;
    }
  }
  return false;
}

template <class PB>
struct GetEntryType;

template<> struct GetEntryType<SysNamespaceEntryPB>
    : public std::integral_constant<SysRowEntry::Type, SysRowEntry::NAMESPACE> {};

template<> struct GetEntryType<SysTablesEntryPB>
    : public std::integral_constant<SysRowEntry::Type, SysRowEntry::TABLE> {};

template<> struct GetEntryType<SysTabletsEntryPB>
    : public std::integral_constant<SysRowEntry::Type, SysRowEntry::TABLET> {};

} // namespace

RestoreSysCatalogState::RestoreSysCatalogState(SnapshotScheduleRestoration* restoration)
    : restoration_(*restoration) {}

Result<bool> RestoreSysCatalogState::PatchRestoringEntry(
    const std::string& id, SysNamespaceEntryPB* pb) {
  return true;
}

Result<bool> RestoreSysCatalogState::PatchRestoringEntry(
    const std::string& id, SysTablesEntryPB* pb) {
  if (pb->schema().table_properties().is_ysql_catalog_table()) {
    LOG(INFO) << "PITR: Adding " << pb->name() << " for restoring. ID: " << id;
    restoration_.system_tables_to_restore.emplace(id, pb->name());

    return false;
  }

  auto it = existing_objects_.tables.find(id);
  if (it == existing_objects_.tables.end()) {
    return STATUS_FORMAT(NotFound, "Not found restoring table: $0", id);
  }

  if (pb->version() != it->second.version()) {
    // Force schema update after restoration, if schema has changes.
    pb->set_version(it->second.version() + 1);
  }

  return true;
}

Result<bool> RestoreSysCatalogState::PatchRestoringEntry(
    const std::string& id, SysTabletsEntryPB* pb) {
  return true;
}

template <class PB>
Status RestoreSysCatalogState::AddRestoringEntry(
    const std::string& id, PB* pb, faststring* buffer) {
  auto type = GetEntryType<PB>::value;
  VLOG_WITH_FUNC(1) << SysRowEntry::Type_Name(type) << ": " << id << ", " << pb->ShortDebugString();

  if (!VERIFY_RESULT(PatchRestoringEntry(id, pb))) {
    return Status::OK();
  }
  auto& entry = *entries_.mutable_entries()->Add();
  entry.set_type(type);
  entry.set_id(id);
  pb_util::SerializeToString(*pb, buffer);
  entry.set_data(buffer->data(), buffer->size());
  restoration_.non_system_objects_to_restore.emplace(id, type);

  return Status::OK();
}

Status RestoreSysCatalogState::Process() {
  VLOG_WITH_FUNC(1) << "Restoring: " << restoring_objects_.SizesToString() << ", existing: "
                    << existing_objects_.SizesToString();

  VLOG_WITH_FUNC(2) << "Check restoring objects";
  VLOG_WITH_FUNC(4) << "Restoring namespaces: " << AsString(restoring_objects_.namespaces);
  faststring buffer;
  RETURN_NOT_OK_PREPEND(DetermineEntries(
      &restoring_objects_,
      [this, &buffer](const auto& id, auto* pb) {
        return AddRestoringEntry(id, pb, &buffer);
  }), "Determine restoring entries failed");

  VLOG_WITH_FUNC(2) << "Check existing objects";
  RETURN_NOT_OK_PREPEND(DetermineEntries(
      &existing_objects_,
      [this](const auto& id, auto* pb) {
        return CheckExistingEntry(id, *pb);
  }), "Determine obsolete entries failed");

  // Sort generated vectors, so binary search could be used to check whether object is obsolete.
  auto compare_by_first = [](const auto& lhs, const auto& rhs) { return lhs.first < rhs.first; };
  std::sort(restoration_.non_system_obsolete_tablets.begin(),
            restoration_.non_system_obsolete_tablets.end(),
            compare_by_first);
  std::sort(restoration_.non_system_obsolete_tables.begin(),
            restoration_.non_system_obsolete_tables.end(),
            compare_by_first);

  return Status::OK();
}

template <class ProcessEntry>
Status RestoreSysCatalogState::DetermineEntries(
    Objects* objects, const ProcessEntry& process_entry) {
  std::unordered_set<NamespaceId> namespaces;
  std::unordered_set<TableId> tables;

  for (auto& id_and_metadata : objects->namespaces) {
    if (!VERIFY_RESULT(MatchNamespace(
            restoration_.filter, id_and_metadata.first, id_and_metadata.second))) {
      continue;
    }
    if (!namespaces.insert(id_and_metadata.first).second) {
      continue;
    }
    RETURN_NOT_OK(process_entry(id_and_metadata.first, &id_and_metadata.second));
  }

  for (auto& id_and_metadata : objects->tables) {
    VLOG_WITH_FUNC(3) << "Checking: " << id_and_metadata.first << ", "
                      << id_and_metadata.second.ShortDebugString();

    bool match;
    if (TableDeleted(id_and_metadata.second)) {
      continue;
    }
    if (id_and_metadata.second.has_index_info()) {
      auto it = objects->tables.find(id_and_metadata.second.index_info().indexed_table_id());
      if (it == objects->tables.end()) {
        return STATUS_FORMAT(
            NotFound, "Indexed table $0 not found for index $1 ($2)",
            id_and_metadata.second.index_info().indexed_table_id(), id_and_metadata.first,
            id_and_metadata.second.name());
      }
      match = VERIFY_RESULT(MatchTable(restoration_.filter, it->first, it->second));
    } else {
      match = VERIFY_RESULT(MatchTable(
          restoration_.filter, id_and_metadata.first, id_and_metadata.second));
    }
    if (!match) {
      continue;
    }
    // Process pg_catalog tables that need to be restored.
    if (namespaces.insert(id_and_metadata.second.namespace_id()).second) {
      auto namespace_it = objects->namespaces.find(id_and_metadata.second.namespace_id());
      if (namespace_it == objects->namespaces.end()) {
        return STATUS_FORMAT(
            NotFound, "Namespace $0 not found for table $1", id_and_metadata.second.namespace_id(),
            id_and_metadata.first, id_and_metadata.second.name());
      }
      RETURN_NOT_OK(process_entry(namespace_it->first, &namespace_it->second));
    }
    RETURN_NOT_OK(process_entry(id_and_metadata.first, &id_and_metadata.second));
    tables.insert(id_and_metadata.first);
    VLOG(2) << "Table to restore: " << id_and_metadata.first << ", "
            << id_and_metadata.second.ShortDebugString();
  }
  for (auto& id_and_metadata : objects->tablets) {
    auto it = tables.find(id_and_metadata.second.table_id());
    if (it == tables.end()) {
      continue;
    }
    RETURN_NOT_OK(process_entry(id_and_metadata.first, &id_and_metadata.second));
    VLOG(2) << "Tablet to restore: " << id_and_metadata.first << ", "
            << id_and_metadata.second.ShortDebugString();
  }
  return Status::OK();
}

std::string RestoreSysCatalogState::Objects::SizesToString() const {
  return Format("{ tablets: $0 tables: $1 namespaces: $2 }",
                tablets.size(), tables.size(), namespaces.size());
}

template <class PB>
Status RestoreSysCatalogState::IterateSysCatalog(
    const Schema& schema, const docdb::DocDB& doc_db, HybridTime read_time,
    std::unordered_map<std::string, PB>* map) {
  auto iter = std::make_unique<docdb::DocRowwiseIterator>(
      schema, schema, boost::none, doc_db, CoarseTimePoint::max(),
      ReadHybridTime::SingleTime(read_time), nullptr);
  return EnumerateSysCatalog(iter.get(), schema, GetEntryType<PB>::value, [map](
          const Slice& id, const Slice& data) -> Status {
    auto pb = VERIFY_RESULT(pb_util::ParseFromSlice<PB>(data));
    if (!ShouldLoadObject(pb)) {
      return Status::OK();
    }
    if (!map->emplace(id.ToBuffer(), std::move(pb)).second) {
      return STATUS_FORMAT(IllegalState, "Duplicate $0: $1",
                           SysRowEntry::Type_Name(GetEntryType<PB>::value), id.ToBuffer());
    }
    return Status::OK();
  });
}

Status RestoreSysCatalogState::LoadObjects(
    const Schema& schema, const docdb::DocDB& doc_db, HybridTime read_time, Objects* objects) {
  RETURN_NOT_OK(IterateSysCatalog(schema, doc_db, read_time, &objects->namespaces));
  RETURN_NOT_OK(IterateSysCatalog(schema, doc_db, read_time, &objects->tables));
  RETURN_NOT_OK(IterateSysCatalog(schema, doc_db, read_time, &objects->tablets));
  return Status::OK();
}

Status RestoreSysCatalogState::LoadRestoringObjects(
    const Schema& schema, const docdb::DocDB& doc_db) {
  return LoadObjects(schema, doc_db, restoration_.restore_at, &restoring_objects_);
}

Status RestoreSysCatalogState::LoadExistingObjects(
    const Schema& schema, const docdb::DocDB& doc_db) {
  return LoadObjects(schema, doc_db, HybridTime::kMax, &existing_objects_);
}

Status RestoreSysCatalogState::CheckExistingEntry(
    const std::string& id, const SysTabletsEntryPB& pb) {
  VLOG_WITH_FUNC(4) << "Tablet: " << id << ", " << pb.ShortDebugString();
  if (restoring_objects_.tablets.count(id)) {
    return Status::OK();
  }
  LOG(INFO) << "PITR: Will remove tablet: " << id;
  restoration_.non_system_obsolete_tablets.emplace_back(id, pb);
  return Status::OK();
}

Status RestoreSysCatalogState::CheckExistingEntry(
    const std::string& id, const SysTablesEntryPB& pb) {
  if (pb.schema().table_properties().is_ysql_catalog_table()) {
    if (restoration_.system_tables_to_restore.count(id) == 0) {
      return STATUS_FORMAT(
          NotFound,
          "PG Catalog table $0 not found in the present set of tables"
          " but found in the objects to restore.",
          pb.name());
    }
    return Status::OK();
  }

  VLOG_WITH_FUNC(4) << "Table: " << id << ", " << pb.ShortDebugString();
  if (restoring_objects_.tables.count(id)) {
    return Status::OK();
  }
  LOG(INFO) << "PITR: Will remove table: " << id;
  restoration_.non_system_obsolete_tables.emplace_back(id, pb);

  return Status::OK();
}

// We don't delete newly created namespaces, because our filters namespace based.
Status RestoreSysCatalogState::CheckExistingEntry(
    const std::string& id, const SysNamespaceEntryPB& pb) {
  return Status::OK();
}

Status RestoreSysCatalogState::PrepareWriteBatch(
    const Schema& schema, docdb::DocWriteBatch* write_batch) {
  for (const auto& entry : entries_.entries()) {
    QLWriteRequestPB write_request;
    RETURN_NOT_OK(FillSysCatalogWriteRequest(
        entry.type(), entry.id(), entry.data(), QLWriteRequestPB::QL_STMT_INSERT, schema,
        &write_request));
    RETURN_NOT_OK(ApplyWriteRequest(schema, &write_request, write_batch));
  }

  for (const auto& tablet_id_and_pb : restoration_.non_system_obsolete_tablets) {
    RETURN_NOT_OK(PrepareTabletCleanup(
        tablet_id_and_pb.first, tablet_id_and_pb.second, schema, write_batch));
  }
  for (const auto& table_id_and_pb : restoration_.non_system_obsolete_tables) {
    RETURN_NOT_OK(PrepareTableCleanup(
        table_id_and_pb.first, table_id_and_pb.second, schema, write_batch));
  }

  return Status::OK();
}

Status RestoreSysCatalogState::PrepareTabletCleanup(
    const TabletId& id, SysTabletsEntryPB pb, const Schema& schema,
    docdb::DocWriteBatch* write_batch) {
  QLWriteRequestPB write_request;
  pb.set_state(SysTabletsEntryPB::DELETED);
  RETURN_NOT_OK(FillSysCatalogWriteRequest(
      SysRowEntry::TABLET, id, pb.SerializeAsString(),
      QLWriteRequestPB::QL_STMT_UPDATE, schema, &write_request));
  return ApplyWriteRequest(schema, &write_request, write_batch);
}

Status RestoreSysCatalogState::PrepareTableCleanup(
    const TableId& id, SysTablesEntryPB pb, const Schema& schema,
    docdb::DocWriteBatch* write_batch) {
  QLWriteRequestPB write_request;
  pb.set_state(SysTablesEntryPB::DELETING);
  pb.set_version(pb.version() + 1);
  RETURN_NOT_OK(FillSysCatalogWriteRequest(
      SysRowEntry::TABLE, id, pb.SerializeAsString(),
      QLWriteRequestPB::QL_STMT_UPDATE, schema, &write_request));
  return ApplyWriteRequest(schema, &write_request, write_batch);
}

Result<bool> RestoreSysCatalogState::TEST_MatchTable(
    const TableId& id, const SysTablesEntryPB& table) {
  return MatchTable(restoration_.filter, id, table);
}

void RestoreSysCatalogState::WriteToRocksDB(
    docdb::DocWriteBatch* write_batch, const HybridTime& write_time, const OpId& op_id,
    tablet::Tablet* tablet) {
  docdb::KeyValueWriteBatchPB kv_write_batch;
  write_batch->MoveToWriteBatchPB(&kv_write_batch);

  rocksdb::WriteBatch rocksdb_write_batch;
  PrepareNonTransactionWriteBatch(
      kv_write_batch, write_time, nullptr, &rocksdb_write_batch, nullptr);
  docdb::ConsensusFrontiers frontiers;
  set_op_id(op_id, &frontiers);
  set_hybrid_time(write_time, &frontiers);

  tablet->WriteToRocksDB(
      &frontiers, &rocksdb_write_batch, docdb::StorageDbType::kRegular);
}

class FetchState {
 public:
  explicit FetchState(const docdb::DocDB& doc_db, const ReadHybridTime& read_time)
      : iterator_(CreateIntentAwareIterator(
          doc_db,
          docdb::BloomFilterMode::DONT_USE_BLOOM_FILTER,
          boost::none,
          rocksdb::kDefaultQueryId,
          boost::none,
          CoarseTimePoint::max(),
          read_time)) {
  }

  CHECKED_STATUS SetPrefix(const Slice& prefix) {
    if (prefix_.empty()) {
      iterator_->Seek(prefix);
    } else {
      iterator_->SeekForward(prefix);
    }
    prefix_ = prefix;
    finished_ = false;
    return Update();
  }

  bool finished() const {
    return finished_;
  }

  Slice key() const {
    return key_;
  }

  Slice value() const {
    return iterator_->value();
  }

  CHECKED_STATUS Next() {
    iterator_->SeekOutOfSubDoc(key_);
    return Update();
  }

 private:
  CHECKED_STATUS Update() {
    if (!iterator_->valid()) {
      finished_ = true;
      return Status::OK();
    }
    auto fetched_key = VERIFY_RESULT(iterator_->FetchKey());
    key_ = fetched_key.key;
    if (!key_.starts_with(prefix_)) {
      finished_ = true;
      return Status::OK();
    }

    return Status::OK();
  }

  std::unique_ptr<docdb::IntentAwareIterator> iterator_;
  Slice prefix_;
  Slice key_;
  bool finished_ = false;
};

void AddKeyValue(const Slice& key, const Slice& value, docdb::DocWriteBatch* write_batch) {
  auto& pair = write_batch->AddRaw();
  pair.first.assign(key.cdata(), key.size());
  pair.second.assign(value.cdata(), value.size());
}

struct PgCatalogTableData {
  std::array<uint8_t, kUuidSize + 1> prefix;
  const TableName* name;

  CHECKED_STATUS SetTableId(const TableId& table_id) {
    Uuid cotable_id;
    RETURN_NOT_OK(cotable_id.FromHexString(table_id));
    prefix[0] = docdb::ValueTypeAsChar::kTableId;
    cotable_id.EncodeToComparable(&prefix[1]);
    return Status::OK();
  }
};

Status RestoreSysCatalogState::ProcessPgCatalogRestores(
    const Schema& pg_yb_catalog_version_schema,
    const docdb::DocDB& restoring_db,
    const docdb::DocDB& existing_db,
    docdb::DocWriteBatch* write_batch) {
  if (restoration_.system_tables_to_restore.empty()) {
    return Status::OK();
  }

  FetchState restoring_state(restoring_db, ReadHybridTime::SingleTime(restoration_.restore_at));
  FetchState existing_state(existing_db, ReadHybridTime::Max());
  char tombstone_char = docdb::ValueTypeAsChar::kTombstone;
  Slice tombstone(&tombstone_char, 1);

  std::vector<PgCatalogTableData> tables(restoration_.system_tables_to_restore.size() + 1);
  size_t idx = 0;
  RETURN_NOT_OK(tables[0].SetTableId(kPgYbCatalogVersionTableId));
  tables[0].name = nullptr;
  ++idx;
  for (auto& id_and_name : restoration_.system_tables_to_restore) {
    auto& table = tables[idx];
    RETURN_NOT_OK(table.SetTableId(id_and_name.first));
    table.name = &id_and_name.second;
    ++idx;
  }


  std::sort(tables.begin(), tables.end(), [](const auto& lhs, const auto& rhs) {
    return Slice(lhs.prefix).compare(Slice(rhs.prefix)) < 0;
  });

  for (auto& table : tables) {
    size_t num_updates = 0;
    size_t num_inserts = 0;
    size_t num_deletes = 0;
    Slice prefix(table.prefix);

    RETURN_NOT_OK(restoring_state.SetPrefix(prefix));
    RETURN_NOT_OK(existing_state.SetPrefix(prefix));

    while (!restoring_state.finished() && !existing_state.finished()) {
      auto compare_result = restoring_state.key().compare(existing_state.key());
      if (compare_result == 0) {
        if (table.name != nullptr) {
          if (restoring_state.value().compare(existing_state.value())) {
            ++num_updates;
            AddKeyValue(restoring_state.key(), restoring_state.value(), write_batch);
          }
        } else {
          docdb::SubDocKey sub_doc_key;
          RETURN_NOT_OK(sub_doc_key.FullyDecodeFrom(
              restoring_state.key(), docdb::HybridTimeRequired::kFalse));
          SCHECK_EQ(sub_doc_key.subkeys().size(), 1, Corruption, "Wrong number of subdoc keys");
          if (sub_doc_key.subkeys()[0].value_type() == docdb::ValueType::kColumnId) {
            auto column_id = sub_doc_key.subkeys()[0].GetColumnId();
            const ColumnSchema& column = VERIFY_RESULT(pg_yb_catalog_version_schema.column_by_id(
                column_id));
            if (column.name() == "current_version") {
              docdb::Value value;
              RETURN_NOT_OK(value.Decode(existing_state.value()));
              docdb::DocPath path(sub_doc_key.doc_key().Encode(), sub_doc_key.subkeys());
              RETURN_NOT_OK(write_batch->SetPrimitive(
                  path, docdb::PrimitiveValue(value.primitive_value().GetInt64() + 1)));
            }
          }
        }
        RETURN_NOT_OK(restoring_state.Next());
        RETURN_NOT_OK(existing_state.Next());
      } else if (compare_result < 0) {
        ++num_inserts;
        AddKeyValue(restoring_state.key(), restoring_state.value(), write_batch);
        RETURN_NOT_OK(restoring_state.Next());
      } else {
        ++num_deletes;
        AddKeyValue(existing_state.key(), tombstone, write_batch);
        RETURN_NOT_OK(existing_state.Next());
      }
    }

    while (!restoring_state.finished()) {
      ++num_inserts;
      AddKeyValue(restoring_state.key(), restoring_state.value(), write_batch);
      RETURN_NOT_OK(restoring_state.Next());
    }

    while (!existing_state.finished()) {
      ++num_deletes;
      AddKeyValue(existing_state.key(), tombstone, write_batch);
      RETURN_NOT_OK(existing_state.Next());
    }

    if (num_updates + num_inserts + num_deletes != 0) {
      LOG(INFO) << "PITR: Pg system table: " << *table.name << ", updates: " << num_updates
                << ", inserts: " << num_inserts << ", deletes: " << num_deletes;
    }
  }

  return Status::OK();
}

}  // namespace master
}  // namespace yb
