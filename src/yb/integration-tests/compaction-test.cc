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

#include <sys/types.h>
#include "yb/client/table_alterer.h"
#include "yb/client/transaction_pool.h"

#include "yb/docdb/compaction_file_filter.h"
#include "yb/gutil/integral_types.h"
#include "yb/integration-tests/test_workload.h"
#include "yb/integration-tests/mini_cluster.h"

#include "yb/master/mini_master.h"
#include "yb/master/master.h"

#include "yb/rocksdb/statistics.h"
#include "yb/tablet/tablet_peer.h"
#include "yb/tablet/tablet.h"

#include "yb/tserver/ts_tablet_manager.h"

#include "yb/util/size_literals.h"
#include "yb/util/test_util.h"
#include "yb/util/tsan_util.h"

using namespace std::literals; // NOLINT

DECLARE_int64(db_write_buffer_size);
DECLARE_int32(rocksdb_level0_file_num_compaction_trigger);
DECLARE_int32(timestamp_history_retention_interval_sec);
DECLARE_bool(tablet_enable_ttl_file_filter);
DECLARE_int32(rocksdb_base_background_compactions);
DECLARE_int32(rocksdb_max_background_compactions);
DECLARE_int32(rocksdb_level0_file_num_compaction_trigger);
DECLARE_uint64(rocksdb_max_file_size_for_compaction);
DECLARE_bool(TEST_disable_adding_user_frontier_to_sst);
DECLARE_bool(TEST_disable_getting_user_frontier_from_mem_table);

namespace yb {

namespace tserver {

namespace {

constexpr auto kWaitDelay = 10ms;
constexpr auto kPayloadBytes = 8_KB;
constexpr auto kMemStoreSize = 100_KB;
constexpr auto kNumTablets = 3;



class RocksDbListener : public rocksdb::EventListener {
 public:
  void OnCompactionCompleted(rocksdb::DB* db, const rocksdb::CompactionJobInfo&) override {
    IncrementValueByDb(db, &num_compactions_completed_);
  }

  int GetNumCompactionsCompleted(rocksdb::DB* db) {
    return GetValueByDb(db, num_compactions_completed_);
  }

  void OnFlushCompleted(rocksdb::DB* db, const rocksdb::FlushJobInfo&) override {
    IncrementValueByDb(db, &num_flushes_completed_);
  }

  int GetNumFlushesCompleted(rocksdb::DB* db) {
    return GetValueByDb(db, num_flushes_completed_);
  }

  void Reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    num_compactions_completed_.clear();
    num_flushes_completed_.clear();
  }

 private:
  typedef std::unordered_map<const rocksdb::DB*, int> CountByDbMap;

  void IncrementValueByDb(const rocksdb::DB* db, CountByDbMap* map);
  int GetValueByDb(const rocksdb::DB* db, const CountByDbMap& map);

  std::mutex mutex_;
  CountByDbMap num_compactions_completed_;
  CountByDbMap num_flushes_completed_;
};

void RocksDbListener::IncrementValueByDb(const rocksdb::DB* db, CountByDbMap* map) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = map->find(db);
  if (it == map->end()) {
    (*map)[db] = 1;
  } else {
    ++(it->second);
  }
}

int RocksDbListener::GetValueByDb(const rocksdb::DB* db, const CountByDbMap& map) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = map.find(db);
  if (it == map.end()) {
    return 0;
  } else {
    return it->second;
  }
}

} // namespace

class CompactionTest : public YBTest {
 public:
  CompactionTest() {}

  void SetUp() override {
    YBTest::SetUp();

    ASSERT_OK(clock_->Init());
    rocksdb_listener_ = std::make_shared<RocksDbListener>();

    // Start cluster.
    MiniClusterOptions opts;
    opts.num_tablet_servers = 1;
    cluster_.reset(new MiniCluster(opts));
    ASSERT_OK(cluster_->Start());
    // These flags should be set after minicluster start, so it wouldn't override them.
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_db_write_buffer_size) = kMemStoreSize;
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_rocksdb_level0_file_num_compaction_trigger) = 3;
    // Patch tablet options inside tablet manager, will be applied to newly created tablets.
    cluster_->GetTabletManager(0)->TEST_tablet_options()->listeners.push_back(rocksdb_listener_);

    client_ = ASSERT_RESULT(cluster_->CreateClient());
    transaction_manager_ = std::make_unique<client::TransactionManager>(
        client_.get(), clock_, client::LocalTabletFilter());
    transaction_pool_ = std::make_unique<client::TransactionPool>(
        transaction_manager_.get(), nullptr /* metric_entity */);
  }

  void TearDown() override {
    workload_->StopAndJoin();
    // Shutdown client before destroying transaction manager, so we don't have transaction RPCs
    // in progress after transaction manager is destroyed.
    client_->Shutdown();
    cluster_->Shutdown();
    YBTest::TearDown();
  }

  void SetupWorkload(IsolationLevel isolation_level) {
    workload_.reset(new TestWorkload(cluster_.get()));
    workload_->set_timeout_allowed(true);
    workload_->set_payload_bytes(kPayloadBytes);
    workload_->set_write_batch_size(1);
    workload_->set_num_write_threads(4);
    workload_->set_num_tablets(kNumTablets);
    workload_->set_transactional(isolation_level, transaction_pool_.get());
    workload_->set_ttl(ttl_to_use());
    workload_->set_table_ttl(table_ttl_to_use());
    workload_->Setup();
  }

 protected:

  // -1 implies no ttl.
  virtual int ttl_to_use() {
    return -1;
  }

  // -1 implies no table ttl.
  virtual int table_ttl_to_use() {
    return -1;
  }

  size_t BytesWritten() {
    return workload_->rows_inserted() * kPayloadBytes;
  }

  CHECKED_STATUS WriteAtLeast(size_t size_bytes) {
    workload_->Start();
    RETURN_NOT_OK(LoggedWaitFor(
        [this, size_bytes] { return BytesWritten() >= size_bytes; }, 60s,
        Format("Waiting until we've written at least $0 bytes ...", size_bytes), kWaitDelay));
    workload_->StopAndJoin();
    LOG(INFO) << "Wrote " << BytesWritten() << " bytes.";
    return Status::OK();
  }

  CHECKED_STATUS WriteAtLeastFilesPerDb(int num_files) {
    auto dbs = GetAllRocksDbs(cluster_.get());
    workload_->Start();
    RETURN_NOT_OK(LoggedWaitFor(
        [this, &dbs, num_files] {
            for (auto* db : dbs) {
              if (rocksdb_listener_->GetNumFlushesCompleted(db
              ) < num_files) {
                return false;
              }
            }
            return true;
          }, 60s,
        Format("Waiting until we've written at least $0 files per rocksdb ...", num_files),
        kWaitDelay * kTimeMultiplier));
    workload_->StopAndJoin();
    LOG(INFO) << "Wrote " << BytesWritten() << " bytes.";
    return Status::OK();
  }

  CHECKED_STATUS ChangeTableTTL(const client::YBTableName& table_name, int ttl_sec) {
    RETURN_NOT_OK(client_->TableExists(table_name));
    auto alterer = client_->NewTableAlterer(table_name);
    TableProperties table_properties;
    table_properties.SetDefaultTimeToLive(ttl_sec * MonoTime::kMillisecondsPerSecond);
    alterer->SetTableProperties(table_properties);
    return alterer->Alter();
  }

  void TestCompactionAfterTruncate();
  void TestCompactionWithoutFrontiers(const int num_without_frontiers);

  std::unique_ptr<MiniCluster> cluster_;
  std::unique_ptr<client::YBClient> client_;
  server::ClockPtr clock_{new server::HybridClock()};
  std::unique_ptr<client::TransactionManager> transaction_manager_;
  std::unique_ptr<client::TransactionPool> transaction_pool_;
  std::unique_ptr<TestWorkload> workload_;
  std::shared_ptr<RocksDbListener> rocksdb_listener_;
};

void CompactionTest::TestCompactionAfterTruncate() {
  // Write some data before truncate to make sure truncate wouldn't be noop.
  ASSERT_OK(WriteAtLeast(kMemStoreSize * kNumTablets * 1.2));

  const auto table_info = ASSERT_RESULT(FindTable(cluster_.get(), workload_->table_name()));
  ASSERT_OK(workload_->client().TruncateTable(table_info->id(), true /* wait */));

  rocksdb_listener_->Reset();
  // Write enough to trigger compactions.
  ASSERT_OK(WriteAtLeastFilesPerDb(FLAGS_rocksdb_level0_file_num_compaction_trigger + 1));

  auto dbs = GetAllRocksDbs(cluster_.get());
  ASSERT_OK(LoggedWaitFor(
      [&dbs] {
        for (auto* db : dbs) {
          if (db->GetLiveFilesMetaData().size() >
              FLAGS_rocksdb_level0_file_num_compaction_trigger) {
            return false;
          }
        }
        return true;
      },
      60s, "Waiting until we have number of SST files not higher than threshold ...", kWaitDelay));
}

void CompactionTest::TestCompactionWithoutFrontiers(const int num_without_frontiers) {
  // Write a number of files without frontiers
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_disable_adding_user_frontier_to_sst) = true;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_disable_getting_user_frontier_from_mem_table) = true;
  ASSERT_OK(WriteAtLeastFilesPerDb(num_without_frontiers));
  // If the number of files to write without frontiers is less than the number to
  // trigger compaction, then write the rest with frontiers.
  if (num_without_frontiers < FLAGS_rocksdb_level0_file_num_compaction_trigger + 1) {
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_disable_adding_user_frontier_to_sst) = false;
    const int num_with_frontiers =
        (FLAGS_rocksdb_level0_file_num_compaction_trigger + 1) - num_without_frontiers;
    ASSERT_OK(WriteAtLeastFilesPerDb(num_with_frontiers));
  }

  auto dbs = GetAllRocksDbs(cluster_.get());
  ASSERT_OK(LoggedWaitFor(
      [&dbs] {
        for (auto* db : dbs) {
          if (db->GetLiveFilesMetaData().size() >
              FLAGS_rocksdb_level0_file_num_compaction_trigger) {
            return false;
          }
        }
        return true;
      },
      60s,
      "Waiting until we have number of SST files not higher than threshold ...",
      kWaitDelay * kTimeMultiplier));
  // reset FLAGS_TEST_disable_adding_user_frontier_to_sst
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_disable_adding_user_frontier_to_sst) = false;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_disable_getting_user_frontier_from_mem_table) = false;
}

TEST_F(CompactionTest, CompactionAfterTruncate) {
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);
  TestCompactionAfterTruncate();
}

TEST_F(CompactionTest, CompactionAfterTruncateTransactional) {
  SetupWorkload(IsolationLevel::SNAPSHOT_ISOLATION);
  TestCompactionAfterTruncate();
}

TEST_F(CompactionTest, CompactionWithoutAnyUserFrontiers) {
  SetupWorkload(IsolationLevel::SNAPSHOT_ISOLATION);
  // Create enough SST files without user frontiers to trigger compaction.
  TestCompactionWithoutFrontiers(FLAGS_rocksdb_level0_file_num_compaction_trigger + 1);
}

TEST_F(CompactionTest, CompactionWithSomeUserFrontiers) {
  SetupWorkload(IsolationLevel::SNAPSHOT_ISOLATION);
  // Create only one SST file without user frontiers.
  TestCompactionWithoutFrontiers(1);
}

class CompactionTestWithTTL : public CompactionTest {
 protected:
  int ttl_to_use() override {
    return kTTLSec;
  }
  const int kTTLSec = 1;
};

TEST_F(CompactionTestWithTTL, CompactionAfterExpiry) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_timestamp_history_retention_interval_sec) = 0;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_rocksdb_level0_file_num_compaction_trigger) = 10;
  // Testing compaction without compaction file filtering for TTL expiration.
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = false;
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);

  rocksdb_listener_->Reset();
  auto dbs = GetAllRocksDbs(cluster_.get(), false);

  // Write enough to be short of triggering compactions.
  ASSERT_OK(WriteAtLeastFilesPerDb(0.8 * FLAGS_rocksdb_level0_file_num_compaction_trigger));
  size_t size_before_compaction = 0;
  for (auto* db : dbs) {
    size_before_compaction += db->GetCurrentVersionSstFilesUncompressedSize();
  }
  LOG(INFO) << "size_before_compaction is " << size_before_compaction;

  LOG(INFO) << "Sleeping";
  SleepFor(MonoDelta::FromSeconds(2 * kTTLSec));

  // Write enough to trigger compactions.
  ASSERT_OK(WriteAtLeastFilesPerDb(FLAGS_rocksdb_level0_file_num_compaction_trigger));

  ASSERT_OK(LoggedWaitFor(
      [&dbs] {
        for (auto* db : dbs) {
          if (db->GetLiveFilesMetaData().size() >
              FLAGS_rocksdb_level0_file_num_compaction_trigger) {
            return false;
          }
        }
        return true;
      },
      60s, "Waiting until we have number of SST files not higher than threshold ...", kWaitDelay));

  // Assert that the data size is smaller now.
  size_t size_after_compaction = 0;
  for (auto* db : dbs) {
    size_after_compaction += db->GetCurrentVersionSstFilesUncompressedSize();
  }
  LOG(INFO) << "size_after_compaction is " << size_after_compaction;
  EXPECT_LT(size_after_compaction, size_before_compaction);

  SleepFor(MonoDelta::FromSeconds(2 * kTTLSec));

  constexpr int kCompactionTimeoutSec = 60;
  const auto table_info = ASSERT_RESULT(FindTable(cluster_.get(), workload_->table_name()));
  ASSERT_OK(workload_->client().FlushTables(
    {table_info->id()}, false, kCompactionTimeoutSec, /* compaction */ true));
  // Assert that the data size is all wiped up now.
  size_t size_after_manual_compaction = 0;
  uint64_t num_sst_files_filtered = 0;
  for (auto* db : dbs) {
    size_after_manual_compaction += db->GetCurrentVersionSstFilesUncompressedSize();
    auto stats = db->GetOptions().statistics;
    num_sst_files_filtered
        += stats->getTickerCount(rocksdb::COMPACTION_FILES_FILTERED);
  }
  LOG(INFO) << "size_after_manual_compaction is " << size_after_manual_compaction;
  EXPECT_EQ(size_after_manual_compaction, 0);
  EXPECT_EQ(num_sst_files_filtered, 0);
}

class CompactionTestWithFileExpiration : public CompactionTest {
 public:
  void SetUp() override {
    CompactionTest::SetUp();
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_timestamp_history_retention_interval_sec) = 0;
    // Disable automatic compactions, but continue to allow manual compactions.
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_rocksdb_base_background_compactions) = 0;
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_rocksdb_max_background_compactions) = 0;
  }
 protected:
  size_t GetTotalSizeOfDbs();
  uint64_t GetNumFilesInDbs();
  uint64_t CountFilteredSSTFiles();
  uint64_t CountUnfilteredSSTFiles();
  void ExecuteManualCompaction();
  void WriteRecordsAllExpire();
  int table_ttl_to_use() override {
    return kTableTTLSec;
  }
  const int kTableTTLSec = 1;
};

size_t CompactionTestWithFileExpiration::GetTotalSizeOfDbs() {
  size_t total_size_dbs = 0;
  auto dbs = GetAllRocksDbs(cluster_.get(), false);
  for (auto* db : dbs) {
    total_size_dbs += db->GetCurrentVersionSstFilesUncompressedSize();
  }
  return total_size_dbs;
}

uint64_t CompactionTestWithFileExpiration::GetNumFilesInDbs() {
  uint64_t total_files_dbs = 0;
  auto dbs = GetAllRocksDbs(cluster_.get(), false);
  for (auto* db : dbs) {
    total_files_dbs += db->GetCurrentVersionNumSSTFiles();
  }
  return total_files_dbs;
}

uint64_t CompactionTestWithFileExpiration::CountFilteredSSTFiles() {
  auto dbs = GetAllRocksDbs(cluster_.get(), false);
  uint64_t num_sst_files_filtered = 0;
  for (auto* db : dbs) {
    auto stats = db->GetOptions().statistics;
    num_sst_files_filtered
        += stats->getTickerCount(rocksdb::COMPACTION_FILES_FILTERED);
  }
  LOG(INFO) << "Number of filtered SST files: " << num_sst_files_filtered;
  return num_sst_files_filtered;
}

uint64_t CompactionTestWithFileExpiration::CountUnfilteredSSTFiles() {
  auto dbs = GetAllRocksDbs(cluster_.get(), false);
  uint64_t num_sst_files_unfiltered = 0;
  for (auto* db : dbs) {
    auto stats = db->GetOptions().statistics;
    num_sst_files_unfiltered
        += stats->getTickerCount(rocksdb::COMPACTION_FILES_NOT_FILTERED);
  }
  LOG(INFO) << "Number of unfiltered SST files: " << num_sst_files_unfiltered;
  return num_sst_files_unfiltered;
}

void CompactionTestWithFileExpiration::ExecuteManualCompaction() {
  constexpr int kCompactionTimeoutSec = 60;
  const auto table_info = ASSERT_RESULT(FindTable(cluster_.get(), workload_->table_name()));
  ASSERT_OK(workload_->client().FlushTables(
    {table_info->id()}, false, kCompactionTimeoutSec, /* compaction */ true));
}

void CompactionTestWithFileExpiration::WriteRecordsAllExpire() {
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);
  rocksdb_listener_->Reset();

  ASSERT_OK(WriteAtLeastFilesPerDb(10));
  auto size_before_compaction = GetTotalSizeOfDbs();
  auto files_before_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size before compaction: " << size_before_compaction <<
      ", num files: " << files_before_compaction;

  LOG(INFO) << "Sleeping";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));

  ExecuteManualCompaction();
  // Assert that the data size is all wiped up now.
  auto size_after_manual_compaction = GetTotalSizeOfDbs();
  auto files_after_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size after compaction: " << size_after_manual_compaction <<
      ", num files: " << files_after_compaction;
  EXPECT_EQ(size_after_manual_compaction, 0);
  EXPECT_EQ(files_after_compaction, 0);
}

TEST_F(CompactionTestWithFileExpiration, CompactionNoFileExpiration) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = false;
  WriteRecordsAllExpire();
  ASSERT_GT(CountUnfilteredSSTFiles(), 0);
  ASSERT_EQ(CountFilteredSSTFiles(), 0);
}

TEST_F(CompactionTestWithFileExpiration, FileExpirationAfterExpiry) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = true;
  WriteRecordsAllExpire();
  auto num_sst_files = CountFilteredSSTFiles();
  ASSERT_GT(num_sst_files, 0);
}

TEST_F(CompactionTestWithFileExpiration, ValueTTLOverridesTableTTL) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = true;
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);
  // Set the value-level TTL to too high to expire.
  workload_->set_ttl(10000000);
  rocksdb_listener_->Reset();

  ASSERT_OK(WriteAtLeastFilesPerDb(10));
  auto size_before_compaction = GetTotalSizeOfDbs();
  auto files_before_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size before compaction: " << size_before_compaction <<
      ", num files: " << files_before_compaction;

  LOG(INFO) << "Sleeping";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));

  ExecuteManualCompaction();
  // Assert that the data size is all wiped up now.
  auto size_after_manual_compaction = GetTotalSizeOfDbs();
  auto files_after_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size after compaction: " << size_after_manual_compaction <<
      ", num files: " << files_after_compaction;
  EXPECT_GT(size_after_manual_compaction, 0);
  EXPECT_GT(files_after_compaction, 0);
  ASSERT_EQ(CountFilteredSSTFiles(), 0);
}

TEST_F(CompactionTestWithFileExpiration, MixedExpiringAndNonExpiring) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = true;
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);

  rocksdb_listener_->Reset();
  ASSERT_OK(WriteAtLeastFilesPerDb(10));
  auto size_before_sleep = GetTotalSizeOfDbs();
  auto files_before_sleep = GetNumFilesInDbs();
  LOG(INFO) << "Total size of " << files_before_sleep <<
      " files that should expire: " << size_before_sleep;

  LOG(INFO) << "Sleeping";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));

  rocksdb_listener_->Reset();
  ASSERT_OK(WriteAtLeastFilesPerDb(1));

  ExecuteManualCompaction();
  // Assert that the data size is all wiped up now.
  size_t size_after_manual_compaction = GetTotalSizeOfDbs();
  uint64_t files_after_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size of " << files_after_compaction << " files after compaction: "
      << size_after_manual_compaction;
  EXPECT_GT(size_after_manual_compaction, 0);
  EXPECT_LT(size_after_manual_compaction, size_before_sleep);
  EXPECT_GT(files_after_compaction, 0);
  EXPECT_LT(files_after_compaction, files_before_sleep);
  ASSERT_GT(CountFilteredSSTFiles(), 0);
}

TEST_F(CompactionTestWithFileExpiration, FileThatNeverExpires) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = true;
  const int kNumFilesToWrite = 10;
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);

  rocksdb_listener_->Reset();
  ASSERT_OK(WriteAtLeastFilesPerDb(kNumFilesToWrite));
  auto size_to_expire = GetTotalSizeOfDbs();
  auto files_to_expire = GetNumFilesInDbs();
  LOG(INFO) << "Total size of " << files_to_expire <<
      " files that should expire: " << size_to_expire;

  LOG(INFO) << "Sleeping to expire files";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));

  // Set workload TTL to not expire.
  workload_->set_ttl(docdb::kResetTTL);
  rocksdb_listener_->Reset();
  ASSERT_OK(WriteAtLeastFilesPerDb(1));
  ExecuteManualCompaction();

  auto filtered_sst_files = CountFilteredSSTFiles();
  ASSERT_GT(filtered_sst_files, 0);

  // Write 10 more files that would expire if not for the non-expiring file previously written.
  rocksdb_listener_->Reset();
  workload_->set_ttl(-1);
  ASSERT_OK(WriteAtLeastFilesPerDb(kNumFilesToWrite));

  LOG(INFO) << "Sleeping to expire files";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));
  ExecuteManualCompaction();

  // Assert that there is still some data remaining, and that we haven't filtered any new files.
  auto size_after_manual_compaction = GetTotalSizeOfDbs();
  auto files_after_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size after compaction: " << size_after_manual_compaction <<
      ", num files: " << files_after_compaction;
  EXPECT_GT(size_after_manual_compaction, 0);
  EXPECT_GT(files_after_compaction, 0);
  ASSERT_EQ(filtered_sst_files, CountFilteredSSTFiles());
}

TEST_F(CompactionTestWithFileExpiration, ShouldNotExpireDueToHistoryRetention) {
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_timestamp_history_retention_interval_sec) = 1000000;
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_tablet_enable_ttl_file_filter) = true;
  SetupWorkload(IsolationLevel::NON_TRANSACTIONAL);
  rocksdb_listener_->Reset();

  ASSERT_OK(WriteAtLeastFilesPerDb(10));
  auto size_before_compaction = GetTotalSizeOfDbs();
  auto files_before_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size before compaction: " << size_before_compaction <<
      ", num files: " << files_before_compaction;

  LOG(INFO) << "Sleeping to expire files according to TTL (history retention prevents deletion)";
  SleepFor(MonoDelta::FromSeconds(2 * kTableTTLSec));

  ExecuteManualCompaction();
  // Assert that there is still data after compaction, and no SST files have been filtered.
  auto size_after_manual_compaction = GetTotalSizeOfDbs();
  auto files_after_compaction = GetNumFilesInDbs();
  LOG(INFO) << "Total size after compaction: " << size_after_manual_compaction <<
      ", num files: " << files_after_compaction;
  EXPECT_GT(size_after_manual_compaction, 0);
  EXPECT_GT(files_after_compaction, 0);
  ASSERT_EQ(CountFilteredSSTFiles(), 0);
}

} // namespace tserver
} // namespace yb
