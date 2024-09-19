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

#include <algorithm>

#include <gtest/gtest.h>

#include "yb/integration-tests/mini_cluster.h"
#include "yb/integration-tests/yb_mini_cluster_test_base.h"

#include "yb/master/catalog_manager.h"
#include "yb/master/master.h"
#include "yb/master/master_client.proxy.h"
#include "yb/master/mini_master.h"
#include "yb/master/test_async_rpc_manager.h"

#include "yb/rpc/messenger.h"

#include "yb/tserver/mini_tablet_server.h"
#include "yb/tserver/tablet_server.h"
#include "yb/tserver/tserver_service.proxy.h"

#include "yb/util/backoff_waiter.h"
#include "yb/util/countdown_latch.h"
#include "yb/util/status_callback.h"
#include "yb/util/test_macros.h"
#include "yb/util/unique_lock.h"

using namespace std::chrono_literals;

DECLARE_bool(TEST_enable_object_locking_for_table_locks);
DECLARE_int32(retrying_ts_rpc_max_delay_ms);
DECLARE_int32(retrying_rpc_max_jitter_ms);

namespace yb {

class ObjectLockTest : public YBMiniClusterTestBase<MiniCluster> {
 public:
  ObjectLockTest() {}

  void SetUp() override {
    ANNOTATE_UNPROTECTED_WRITE(FLAGS_TEST_enable_object_locking_for_table_locks) = true;
    YBMiniClusterTestBase::SetUp();
    MiniClusterOptions opts;
    opts.num_tablet_servers = 3;
    opts.num_masters = num_masters();
    cluster_ = std::make_unique<MiniCluster>(opts);
    ASSERT_OK(cluster_->Start());
    ASSERT_OK(cluster_->WaitForTabletServerCount(opts.num_tablet_servers));

    rpc::MessengerBuilder bld("Client");
    client_messenger_ = ASSERT_RESULT(bld.Build());
    proxy_cache_ = std::make_unique<rpc::ProxyCache>(client_messenger_.get());
  }

  void DoBeforeTearDown() override {
    client_messenger_->Shutdown();
    YBMiniClusterTestBase::DoBeforeTearDown();
  }

 protected:
  virtual int num_masters() { return 1; }

  tserver::TabletServerServiceProxy TServerProxyFor(const tserver::MiniTabletServer* tserver) {
    return tserver::TabletServerServiceProxy{
        proxy_cache_.get(), HostPort::FromBoundEndpoint(tserver->bound_rpc_addr())};
  }

  tserver::TabletServerServiceProxy TServerProxy(size_t i) {
    return TServerProxyFor(cluster_->mini_tablet_server(i));
  }

  tserver::TabletServerServiceProxy MasterProxy(const master::MiniMaster* master) {
    return tserver::TabletServerServiceProxy{proxy_cache_.get(), master->bound_rpc_addr()};
  }

  Result<tserver::TabletServerServiceProxy> MasterLeaderProxy() {
    return MasterProxy(VERIFY_RESULT(cluster_->GetLeaderMiniMaster()));
  }

 private:
  std::unique_ptr<rpc::Messenger> client_messenger_;
  std::unique_ptr<rpc::ProxyCache> proxy_cache_;
};

constexpr uint64_t kSessionId = 1;
constexpr uint64_t kSessionId2 = 2;
constexpr uint64_t kDatabaseID = 1;
constexpr uint64_t kObjectId = 1;
constexpr uint64_t kObjectId2 = 2;
constexpr size_t kTimeoutMs = 5000;

tserver::AcquireObjectLockRequestPB AcquireRequestFor(
    uint64_t session_id, uint64_t database_id, uint64_t object_id, TableLockType lock_type) {
  tserver::AcquireObjectLockRequestPB req;
  req.set_session_id(session_id);
  req.set_session_host_uuid("localhost");
  auto* lock = req.add_object_locks();
  lock->set_database_oid(database_id);
  lock->set_object_oid(object_id);
  lock->set_lock_type(lock_type);
  return req;
}

rpc::RpcController RpcController() {
  rpc::RpcController controller;
  controller.set_timeout(MonoDelta::FromMilliseconds(kTimeoutMs));
  return controller;
}

Status AcquireLockAt(
    tserver::TabletServerServiceProxy* proxy, uint64_t session_id, uint64_t database_id,
    uint64_t object_id, TableLockType type) {
  tserver::AcquireObjectLockResponsePB resp;
  auto req = AcquireRequestFor(session_id, database_id, object_id, type);
  auto rpc_controller = RpcController();
  return proxy->AcquireObjectLocks(req, &resp, &rpc_controller);
}

void AcquireLockAsyncAt(
    tserver::TabletServerServiceProxy* proxy, rpc::RpcController* controller, uint64_t session_id,
    uint64_t database_id, uint64_t object_id, TableLockType type, std::function<void()> callback,
    tserver::AcquireObjectLockResponsePB* resp) {
  auto req = AcquireRequestFor(session_id, database_id, object_id, type);
  proxy->AcquireObjectLocksAsync(req, resp, controller, callback);
}

tserver::ReleaseObjectLockRequestPB ReleaseRequestFor(
    uint64_t session_id, uint64_t database_id, uint64_t object_id) {
  tserver::ReleaseObjectLockRequestPB req;
  req.set_session_id(session_id);
  req.set_session_host_uuid("localhost");
  auto* lock = req.add_object_locks();
  lock->set_database_oid(database_id);
  lock->set_object_oid(object_id);
  return req;
}

Status ReleaseLockAt(
    tserver::TabletServerServiceProxy* proxy, uint64_t session_id, uint64_t database_id,
    uint64_t object_id) {
  tserver::ReleaseObjectLockResponsePB resp;
  rpc::RpcController controller = RpcController();
  auto req = ReleaseRequestFor(session_id, database_id, object_id);
  return proxy->ReleaseObjectLocks(req, &resp, &controller);
}

TEST_F(ObjectLockTest, AcquireObjectLocks) {
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  ASSERT_OK(AcquireLockAt(
      &master_proxy, kSessionId, kDatabaseID, kObjectId, TableLockType::ACCESS_EXCLUSIVE));
}

TEST_F(ObjectLockTest, ReleaseObjectLocks) {
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  ASSERT_OK(AcquireLockAt(
      &master_proxy, kSessionId, kDatabaseID, kObjectId, TableLockType::ACCESS_EXCLUSIVE));
  ASSERT_OK(ReleaseLockAt(&master_proxy, kSessionId, kDatabaseID, kObjectId));
}

TEST_F(ObjectLockTest, AcquireObjectLocksWaitsOnTServer) {
  // Acquire lock on TServer-0
  auto* tserver0 = cluster_->mini_tablet_server(0);
  auto tserver0_proxy = TServerProxy(0);
  ASSERT_OK(AcquireLockAt(
      &tserver0_proxy, kSessionId, kDatabaseID, kObjectId, TableLockType::ACCESS_SHARE));

  ASSERT_EQ(tserver0->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 0);
  CountDownLatch latch(1);
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  tserver::AcquireObjectLockResponsePB resp;
  auto controller = RpcController();
  AcquireLockAsyncAt(
      &master_proxy, &controller, kSessionId2, kDatabaseID, kObjectId,
      TableLockType::ACCESS_EXCLUSIVE, latch.CountDownCallback(), &resp);

  // Wait. But the lock acquisition should not be successful.
  ASSERT_OK(WaitFor(
      [tserver0]() -> bool {
        return tserver0->server()->ts_local_lock_manager()->TEST_WaitingLocksSize() > 0;
      },
      MonoDelta::FromMilliseconds(kTimeoutMs), "wait for blocking on TServer0"));

  // Release lock at TServer-0
  ASSERT_OK(ReleaseLockAt(&tserver0_proxy, kSessionId, kDatabaseID, kObjectId));

  // Verify that lock acquistion at master is successful.
  ASSERT_TRUE(latch.WaitFor(MonoDelta::FromMilliseconds(kTimeoutMs)));
  ASSERT_EQ(tserver0->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 0);
}

TEST_F(ObjectLockTest, AcquireAndReleaseDDLLock) {
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  ASSERT_OK(AcquireLockAt(
      &master_proxy, kSessionId2, kDatabaseID, kObjectId, TableLockType::ACCESS_EXCLUSIVE));
  ASSERT_OK(ReleaseLockAt(&master_proxy, kSessionId2, kDatabaseID, kObjectId));

  // Release non-existent lock.
  ASSERT_OK(ReleaseLockAt(&master_proxy, kSessionId2, kDatabaseID, kObjectId2));
}

TEST_F(ObjectLockTest, AcquireObjectLocksRetriesUponMultipleTServerAddition) {
  auto* tserver0 = cluster_->mini_tablet_server(0);
  auto tserver0_proxy = TServerProxyFor(tserver0);
  ASSERT_OK(AcquireLockAt(
      &tserver0_proxy, kSessionId, kDatabaseID, kObjectId, TableLockType::ACCESS_SHARE));

  CountDownLatch latch(1);
  tserver::AcquireObjectLockResponsePB resp;
  auto controller = RpcController();
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  AcquireLockAsyncAt(
      &master_proxy, &controller, kSessionId2, kDatabaseID, kObjectId,
      TableLockType::ACCESS_EXCLUSIVE, latch.CountDownCallback(), &resp);

  // Wait. But the lock acquisition should not be successful.
  ASSERT_OK(WaitFor(
      [tserver0]() -> bool {
        return tserver0->server()->ts_local_lock_manager()->TEST_WaitingLocksSize() > 0;
      },
      MonoDelta::FromMilliseconds(kTimeoutMs), "wait for blocking on TServer0"));

  auto num_ts = cluster_->num_tablet_servers();
  ASSERT_OK(cluster_->AddTabletServer());
  ASSERT_OK(cluster_->WaitForTabletServerCount(num_ts + 1));

  // Add TS-4.
  auto* added_tserver1 = cluster_->mini_tablet_server(num_ts);
  ASSERT_EQ(added_tserver1->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 0);
  auto added_tserver1_proxy = TServerProxyFor(added_tserver1);
  ASSERT_OK(AcquireLockAt(
      &added_tserver1_proxy, kSessionId, kDatabaseID, kObjectId, TableLockType::ACCESS_SHARE));
  ASSERT_GE(added_tserver1->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 1);

  ASSERT_EQ(added_tserver1->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 0);
  ASSERT_GE(tserver0->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 1);
  ASSERT_OK(ReleaseLockAt(&tserver0_proxy, kSessionId, kDatabaseID, kObjectId));

  // Now the exclusive lock acquisition should be retried to added_tserver1. But wait on it since
  // we took a shared lock above.
  ASSERT_OK(WaitFor(
      [added_tserver1]() -> bool {
        return added_tserver1->server()->ts_local_lock_manager()->TEST_WaitingLocksSize() > 0;
      },
      MonoDelta::FromMilliseconds(kTimeoutMs), "wait for blocking on TServer0"));
  ASSERT_GE(added_tserver1->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 1);
  // Release lock at TS-4
  ASSERT_OK(ReleaseLockAt(&added_tserver1_proxy, kSessionId, kDatabaseID, kObjectId));

  // Verify that lock acquistion at master is successful.
  ASSERT_TRUE(latch.WaitFor(MonoDelta::FromMilliseconds(kTimeoutMs)));
  // lock acquisition be have retried and taken the lock on TS-4
  ASSERT_GE(added_tserver1->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 1);

  // Add TS-5
  ASSERT_OK(cluster_->AddTabletServer());
  ASSERT_OK(cluster_->WaitForTabletServerCount(num_ts + 2));

  auto* added_tserver2 = cluster_->mini_tablet_server(num_ts + 1);
  ASSERT_OK(WaitFor(
      [added_tserver2]() {
        return added_tserver2->server()->ts_local_lock_manager()->TEST_GrantedLocksSize() > 0;
      },
      1s, "Wait for the added TS to bootstrap"));
  // DDL lock acquisition should have bootstrapped during registration and taken the lock on TS-5
  // also
  ASSERT_GE(added_tserver2->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 1);
  ASSERT_EQ(added_tserver2->server()->ts_local_lock_manager()->TEST_WaitingLocksSize(), 0);
}

TEST_F(ObjectLockTest, BootstrapTServersUponAddition) {
  auto master_proxy = ASSERT_RESULT(MasterLeaderProxy());
  ASSERT_OK(AcquireLockAt(
      &master_proxy, kSessionId2, kDatabaseID, kObjectId, TableLockType::ACCESS_EXCLUSIVE));

  auto num_ts = cluster_->num_tablet_servers();
  ASSERT_OK(cluster_->AddTabletServer());
  ASSERT_OK(cluster_->WaitForTabletServerCount(num_ts + 1));

  auto* added_tserver = cluster_->mini_tablet_server(num_ts);
  ASSERT_OK(WaitFor(
      [added_tserver]() {
        return added_tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize() > 0;
      },
      1s, "Wait for the added TS to bootstrap"));

  auto expected_locks =
      cluster_->mini_tablet_server(0)->server()->ts_local_lock_manager()->TEST_GrantedLocksSize();
  ASSERT_GE(expected_locks, 1);
  // Expect to see that the lock acquisition happens even at the new tserver
  LOG(INFO) << "Counts after acquiring the DDL lock and adding TServers";
  for (auto ts : cluster_->mini_tablet_servers()) {
    LOG(INFO) << ts->ToString() << " TestWaitingLocksSize: "
              << ts->server()->ts_local_lock_manager()->TEST_WaitingLocksSize()
              << " TestGrantedLocksSize: "
              << ts->server()->ts_local_lock_manager()->TEST_GrantedLocksSize();
    ASSERT_EQ(ts->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), expected_locks);
  }

  ASSERT_OK(ReleaseLockAt(&master_proxy, kSessionId2, kDatabaseID, kObjectId));

  LOG(INFO) << "Counts after releasing the DDL lock";
  expected_locks = 0;
  for (auto ts : cluster_->mini_tablet_servers()) {
    LOG(INFO) << ts->ToString() << " TestWaitingLocksSize: "
              << ts->server()->ts_local_lock_manager()->TEST_WaitingLocksSize()
              << " TestGrantedLocksSize: "
              << ts->server()->ts_local_lock_manager()->TEST_GrantedLocksSize();
    ASSERT_EQ(ts->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), expected_locks);
  }
}

class MultiMasterObjectLockTest : public ObjectLockTest {
 protected:
  int num_masters() override {
    return 3;
  }
};

TEST_F_EX(ObjectLockTest, AcquireAndReleaseDDLLockAcrossMasterFailover, MultiMasterObjectLockTest) {
  const auto num_ts = cluster_->num_tablet_servers();
  auto* leader_master1 = ASSERT_RESULT(cluster_->GetLeaderMiniMaster());
  {
    LOG(INFO) << "Acquiring lock on object " << kObjectId << " from master "
              << leader_master1->ToString();
    auto master_proxy = MasterProxy(leader_master1);
    ASSERT_OK(AcquireLockAt(
        &master_proxy, kSessionId2, kDatabaseID, kObjectId, TableLockType::ACCESS_EXCLUSIVE));
  }

  for (const auto& tserver : cluster_->mini_tablet_servers()) {
    LOG(INFO) << tserver->ToString() << " GrantedLocks "
              << tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize();
    ASSERT_GE(tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 1);
  }

  LOG(INFO) << "Stepping down from " << leader_master1->ToString();
  ASSERT_OK(cluster_->StepDownMasterLeader());
  ASSERT_OK(cluster_->WaitForTabletServerCount(num_ts));

  ASSERT_OK(cluster_->AddTabletServer());
  ASSERT_OK(cluster_->WaitForTabletServerCount(num_ts + 1));

  auto* added_tserver = cluster_->mini_tablet_server(num_ts);
  ASSERT_OK(WaitFor(
      [added_tserver]() {
        return added_tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize() > 0;
      },
      1s, "Wait for the added TS to bootstrap"));
  LOG(INFO) << added_tserver->ToString() << " GrantedLocks "
            << added_tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize();
  ASSERT_GE(added_tserver->server()->ts_local_lock_manager()->TEST_GrantedLocksSize(), 1);

  // Release lock
  auto* leader_master2 = ASSERT_RESULT(cluster_->GetLeaderMiniMaster());
  {
    LOG(INFO) << "Releasing lock on object " << kObjectId << " at master "
              << leader_master2->ToString();
    auto master_proxy = MasterProxy(leader_master2);
    ASSERT_OK(ReleaseLockAt(&master_proxy, kSessionId2, kDatabaseID, kObjectId));
  }
}

}  // namespace yb
