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

#include "yb/common/transaction.h"
#include "yb/common/wire_protocol.h"

#include "yb/util/backoff_waiter.h"
#include "yb/util/tsan_util.h"
#include "yb/yql/pgwrapper/pg_locks_test_base.h"

DECLARE_uint64(transaction_heartbeat_usec);
DECLARE_double(transaction_max_missed_heartbeat_periods);
DECLARE_int32(transaction_table_num_tablets);
DECLARE_int32(heartbeat_interval_ms);
DECLARE_bool(auto_create_local_transaction_tables);
DECLARE_bool(force_global_transactions);
DECLARE_bool(TEST_mock_tablet_hosts_all_transactions);
DECLARE_bool(TEST_fail_abort_request_with_try_again);
DECLARE_bool(enable_wait_queues);
DECLARE_bool(enable_deadlock_detection);

using namespace std::literals;
using std::string;

namespace yb {
namespace pgwrapper {

using tserver::TabletServerServiceProxy;
using TransactionIdSet = std::unordered_set<TransactionId, TransactionIdHash>;
using TxnLocksMap = std::unordered_map<TransactionId, int, TransactionIdHash>;
using TabletTxnLocksMap = std::unordered_map<TabletId, TxnLocksMap>;

class PgGetLockStatusTest : public PgLocksTestBase {
 protected:
  void SetUp() override {
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_enable_wait_queues) = true;
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_enable_deadlock_detection) = true;
    PgLocksTestBase::SetUp();
  }

  Result<TransactionIdSet> GetTxnsInLockStatusResponse(
      const tserver::GetLockStatusResponsePB& resp) {
    if (resp.has_error()) {
      return StatusFromPB(resp.error().status());
    }

    TransactionIdSet txn_ids_set;
    for (const auto& tablet_lock_info : resp.tablet_lock_infos()) {
      for (const auto& txn_lock_pair : tablet_lock_info.transaction_locks()) {
        auto id = VERIFY_RESULT(TransactionId::FromString(txn_lock_pair.first));
        RSTATUS_DCHECK(!id.IsNil(),
                       IllegalState,
                       "Expected to see non-empty transaction id.");
        txn_ids_set.insert(id);
      }
    }
    return txn_ids_set;
  }

  Result<size_t> GetNumTxnsInLockStatusResponse(const tserver::GetLockStatusResponsePB& resp) {
    return VERIFY_RESULT(GetTxnsInLockStatusResponse(resp)).size();
  }

  Result<size_t> GetNumTabletsInLockStatusResponse(const tserver::GetLockStatusResponsePB& resp) {
    if (resp.has_error()) {
      return StatusFromPB(resp.error().status());
    }

    std::unordered_set<std::string> tablet_ids_set;
    for (const auto& tablet_lock_info : resp.tablet_lock_infos()) {
      tablet_ids_set.insert(tablet_lock_info.tablet_id());
    }
    return tablet_ids_set.size();
  }

  void VerifyResponse(const tserver::PgGetLockStatusResponsePB& resp,
                      TabletTxnLocksMap expected_tablet_txn_locks) {
    for (const auto& node_lock : resp.node_locks()) {
      for (const auto& tablet_locks : node_lock.tablet_lock_infos()) {
        if (tablet_locks.tablet_id().empty()) {
          continue;
        }

        auto tablet_map_it = expected_tablet_txn_locks.find(tablet_locks.tablet_id());
        ASSERT_NE(tablet_map_it, expected_tablet_txn_locks.end());
        ASSERT_EQ(tablet_locks.transaction_locks().size(), tablet_map_it->second.size());

        for (const auto& txn_lock_pair : tablet_locks.transaction_locks()) {
          auto id = ASSERT_RESULT(TransactionId::FromString(txn_lock_pair.first));
          auto txn_map_it = tablet_map_it->second.find(id);
          ASSERT_NE(txn_map_it, tablet_map_it->second.end());
          ASSERT_EQ(txn_lock_pair.second.locks_size(), txn_map_it->second);
          tablet_map_it->second.erase(txn_map_it);
        }
        ASSERT_TRUE(tablet_map_it->second.empty());
        expected_tablet_txn_locks.erase(tablet_map_it);
      }
    }
    ASSERT_TRUE(expected_tablet_txn_locks.empty());
  }
};


TEST_F(PgGetLockStatusTest, TestGetLockStatusWithCustomTabletTxnsMap) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  const auto& tablet_id = session.first_involved_tablet;
  auto resp = ASSERT_RESULT(GetLockStatus(tablet_id));
  auto num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 1);

  // Start another transaction an operate on the same tablet.
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  ASSERT_OK(conn.ExecuteFormat("UPDATE $0 SET v=v+10 WHERE k=$1", table, 2));

  // Expect to see 2 transaction in GetLockStatus resp.
  resp = ASSERT_RESULT(GetLockStatus(tablet_id));
  num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 2);

  // Restrict LockStatus to return locks corresponding to the input transaction ids.
  std::vector<TransactionId> txn_ids(1, session.txn_id);
  resp = ASSERT_RESULT(GetLockStatus(tablet_id, txn_ids));
  num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 1);
}

TEST_F(PgGetLockStatusTest, TestGetLockStatusWithCustomTransactionsList) {
  const auto table1 = "foo";
  const auto table2 = "bar";
  const auto tablet1 = CreateTableAndGetTabletId(table1);
  const auto tablet2 = CreateTableAndGetTabletId(table2);
  auto session1 = ASSERT_RESULT(Init("foo", "1", false /* create table */));
  auto session2 = ASSERT_RESULT(Init("bar", "1", false /* create table */));

  std::vector<TransactionId> txn_ids;
  txn_ids.push_back(session1.txn_id);
  txn_ids.push_back(session2.txn_id);

  auto resp = ASSERT_RESULT(GetLockStatus(txn_ids));
  auto num_tablets = ASSERT_RESULT(GetNumTabletsInLockStatusResponse(resp));
  ASSERT_EQ(num_tablets, 2);
  auto num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 2);

  ASSERT_OK(session1.conn->ExecuteFormat("UPDATE $0 SET v=v+10 WHERE k=2", table2));
  txn_ids.pop_back();

  resp = ASSERT_RESULT(GetLockStatus(txn_ids));
  num_tablets = ASSERT_RESULT(GetNumTabletsInLockStatusResponse(resp));
  ASSERT_EQ(num_tablets, 2);
  num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 1);
}

TEST_F(PgGetLockStatusTest, YB_DISABLE_TEST_IN_TSAN(TestLocksFromWaitQueue)) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  // Create a second transaction and make it wait on the earlier txn. This txn won't acquire
  // any locks and will wait in the wait-queue.
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  auto status_future = ASSERT_RESULT(
      ExpectBlockedAsync(&conn, Format("UPDATE $0 SET v=v+10 WHERE k=$1", table, key)));

  // Assert that locks corresponding to the waiter txn as well are returned in
  // GetLockStatusResponsePB.
  SleepFor(MonoDelta::FromSeconds(2 * kTimeMultiplier));
  const auto& tablet_id = session.first_involved_tablet;
  auto resp = ASSERT_RESULT(GetLockStatus(tablet_id));
  auto num_txns = ASSERT_RESULT(GetNumTxnsInLockStatusResponse(resp));
  ASSERT_EQ(num_txns, 2);

  ASSERT_TRUE(conn.IsBusy());
  ASSERT_OK(session.conn->Execute("COMMIT"));
  ASSERT_OK(status_future.get());
}

TEST_F(PgGetLockStatusTest, YB_DISABLE_TEST_IN_TSAN(TestLocksOfSingleShardWaiters)) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  auto conn = ASSERT_RESULT(Connect());
  // Fire a single row update that will wait on the earlier launched transaction.
  auto status_future = ASSERT_RESULT(
      ExpectBlockedAsync(&conn, Format("UPDATE $0 SET v=v+10 WHERE k=$1", table, key)));

  SleepFor(MonoDelta::FromSeconds(2 * kTimeMultiplier));
  const auto& tablet_id = session.first_involved_tablet;
  auto resp = ASSERT_RESULT(GetLockStatus(tablet_id));
  ASSERT_EQ(resp.tablet_lock_infos(0).single_shard_waiters_size(), 1);
  ASSERT_TRUE(conn.IsBusy());
  ASSERT_OK(session.conn->Execute("COMMIT"));
  ASSERT_OK(status_future.get());
}

TEST_F(PgGetLockStatusTest, TestGetLockStatusSimple) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  tserver::PgGetLockStatusRequestPB req;
  req.set_transaction_id(session.txn_id.data(), session.txn_id.size());
  req.set_max_num_txns(50);
  auto resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {
    {
      session.first_involved_tablet,
      {
        {session.txn_id, 2}
      }
    }
  });
}

TEST_F(PgGetLockStatusTest, TestGetLockStatusOfOldTxns) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  auto min_txn_age_ms = 2000;
  SleepFor(MonoDelta::FromMilliseconds(kTimeMultiplier * min_txn_age_ms));
  tserver::PgGetLockStatusRequestPB req;
  req.set_min_txn_age_ms(min_txn_age_ms);
  req.set_max_num_txns(50);

  // Start another txn which wouldn't be considered old.
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  ASSERT_OK(conn.FetchFormat("SELECT * FROM $0 WHERE k=$1 FOR UPDATE", table, 2));

  auto resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {
    {
      session.first_involved_tablet,
      {
        {session.txn_id, 2}
      }
    }
  });

  // Fetch lock status after sleep and expect to see the other transaction as well.
  SleepFor(MonoDelta::FromMilliseconds(kTimeMultiplier * min_txn_age_ms));
  // Workaround to get the other transaction id, currently can't get it through a pg command.
  auto tserver_lock_status_resp = ASSERT_RESULT(GetLockStatus(session.first_involved_tablet));
  auto txns_set = ASSERT_RESULT(GetTxnsInLockStatusResponse(tserver_lock_status_resp));
  txns_set.erase(session.txn_id);
  ASSERT_EQ(txns_set.size(), 1);
  auto other_txn = *txns_set.begin();
  ASSERT_NE(other_txn, session.txn_id);

  resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {
    {
      session.first_involved_tablet,
      {
        {session.txn_id, 2},
        {other_txn, 2}
      }
    }
  });

  // Fetch locks of the specified transaction alone
  req.set_transaction_id(other_txn.data(), other_txn.size());
  resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {
    {
      session.first_involved_tablet,
      {
        {other_txn, 2}
      }
    }
  });
  req.clear_transaction_id();

  req.set_min_txn_age_ms(min_txn_age_ms * 100);
  resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {});
}

TEST_F(PgGetLockStatusTest, TestGetLockStatusLimitNumOldTxns) {
  const auto table = "foo";
  const auto key = "1";
  auto session = ASSERT_RESULT(Init(table, key));

  auto min_txn_age_ms = 2000;
  SleepFor(MonoDelta::FromMilliseconds(kTimeMultiplier * min_txn_age_ms));
  tserver::PgGetLockStatusRequestPB req;
  req.set_min_txn_age_ms(min_txn_age_ms);
  // Limit num old txns being returned. Assert that the oldest txns are prioritized over new ones.
  // Sleep to make sure coodinator aborts unresponsive transactions.
  auto abort_unresponsive_txn_usec = FLAGS_transaction_heartbeat_usec *
                                     kTimeMultiplier *
                                     FLAGS_transaction_max_missed_heartbeat_periods * 2;
  SleepFor(MonoDelta::FromMicroseconds(abort_unresponsive_txn_usec));
  // Start another txn which wouldn't be considered old.
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  ASSERT_OK(conn.FetchFormat("SELECT * FROM $0 WHERE k=$1 FOR UPDATE", table, 2));

  req.set_max_num_txns(1);
  auto resp = ASSERT_RESULT(GetLockStatus(req));
  VerifyResponse(resp, {
    {
      session.first_involved_tablet,
      {
        {session.txn_id, 2}
      }
    }
  });
}

TEST_F(PgGetLockStatusTest, TestGetWaitStart) {
  const auto table = "foo";
  const auto locked_key = "2";
  auto session = ASSERT_RESULT(Init(table, "1"));

  auto blocker = ASSERT_RESULT(Connect());

  ASSERT_OK(blocker.StartTransaction(IsolationLevel::READ_COMMITTED));
  ASSERT_OK(blocker.FetchFormat("SELECT * FROM $0 WHERE k=$1 FOR UPDATE", table, locked_key));

  std::atomic<bool> txn_finished = false;
  std::thread th([&session, &table, &locked_key, &txn_finished] {
    ASSERT_OK(session.conn->FetchFormat(
        "SELECT * FROM $0 WHERE k=$1 FOR UPDATE", table, locked_key));
    txn_finished.store(true);
  });

  SleepFor(1ms * kTimeMultiplier);

  auto res = ASSERT_RESULT(blocker.FetchValue<int64_t>(
    "SELECT COUNT(*) FROM yb_lock_status(null, null) WHERE waitstart IS NOT NULL"));
  // The statement above acquires two locks --
  // {STRONG_READ,STRONG_WRITE} on the primary key
  // {WEAK_READ,WEAK_WRITE} on the table
  ASSERT_EQ(res, 2);

  ASSERT_OK(blocker.CommitTransaction());
  ASSERT_OK(WaitFor([&] {
    return txn_finished.load();
  }, 5s * kTimeMultiplier, "select for update to unblock and execute"));
  th.join();
  ASSERT_OK(session.conn->CommitTransaction());
}

TEST_F(PgGetLockStatusTest, TestBlockedBy) {
  const auto table = "waiter_table";
  const auto locked_key = "2";

  // Start waiter txn first to ensure it is the oldest
  auto waiter_session = ASSERT_RESULT(Init(table, "1"));

  SleepFor(10ms * kTimeMultiplier);

  auto session1 = ASSERT_RESULT(Init("foo", "1"));
  auto session2 = ASSERT_RESULT(Init("bar", "1"));

  // Have both sessions acquire lock on locked_key so they will both block our waiter
  ASSERT_OK(session1.conn->FetchFormat(
      "SELECT * FROM $0 WHERE k=$1 FOR KEY SHARE", table, locked_key));
  ASSERT_OK(session2.conn->FetchFormat(
      "SELECT * FROM $0 WHERE k=$1 FOR KEY SHARE", table, locked_key));

  // Try acquiring exclusive lock on locked_key async
  std::atomic<bool> lock_acquired = false;
  std::thread th([&waiter_session, &table, &locked_key, &lock_acquired] {
    ASSERT_OK(waiter_session.conn->FetchFormat(
        "SELECT * FROM $0 WHERE k=$1 FOR UPDATE", table, locked_key));
    lock_acquired.store(true);
  });

  SleepFor(2 * FLAGS_heartbeat_interval_ms * 1ms * kTimeMultiplier);

  tserver::PgGetLockStatusRequestPB req;
  req.set_max_num_txns(1);
  auto resp = ASSERT_RESULT(GetLockStatus(req));

  ASSERT_EQ(resp.node_locks_size(), 1);
  ASSERT_EQ(resp.node_locks(0).tablet_lock_infos_size(), 1);
  ASSERT_EQ(resp.node_locks(0).tablet_lock_infos(0).transaction_locks_size(), 1);
  for (const auto& [txn_id, txn_lock] :
          resp.node_locks(0).tablet_lock_infos(0).transaction_locks()) {
    auto waiter_txn_id = ASSERT_RESULT(TransactionId::FromString(txn_id));
    ASSERT_EQ(waiter_txn_id, waiter_session.txn_id);

    // Two locks from the Init() setup, and two locks from the blocked SELECT...FOR UPDATE
    ASSERT_EQ(txn_lock.locks().size(), 4);

    for (const auto& lock : txn_lock.locks()) {
      if (lock.has_wait_end_ht()) {
        continue;
      }

      std::set<TransactionId> blockers;
      for (const auto& blocking_txn_id : lock.blocking_txn_ids()) {
        auto decoded = ASSERT_RESULT(FullyDecodeTransactionId(blocking_txn_id));
        blockers.insert(decoded);
        ASSERT_TRUE(decoded == session1.txn_id || decoded == session2.txn_id);
      }
      ASSERT_EQ(blockers.size(), 2);
    }
  }

  ASSERT_OK(session1.conn->CommitTransaction());
  ASSERT_OK(session2.conn->CommitTransaction());
  ASSERT_OK(WaitFor([&] {
    return lock_acquired.load();
  }, 5s * kTimeMultiplier, "select for update to unblock and execute"));
  th.join();
  ASSERT_OK(waiter_session.conn->CommitTransaction());
}

} // namespace pgwrapper
} // namespace yb
