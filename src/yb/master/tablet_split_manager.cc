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

#include "yb/master/tablet_split_manager.h"

#include <chrono>

#include <gflags/gflags.h>

#include "yb/util/format.h"
#include "yb/util/unique_lock.h"

DEFINE_int32(process_split_tablet_candidates_interval_msec, 2000,
             "The tick interval time for processing accumulated tablet split candidates.");
DEFINE_int32(max_queued_split_candidates, 5,
             "The max number of pending tablet split candidates we will hold onto. We potentially "
             "iterate through every candidate in the queue for each tablet we process in a tablet "
             "report so this size should be kept relatively small to avoid any issues.");

constexpr int32 kHardLimitCandidateQueueSize = 100;

namespace yb {
namespace master {

namespace {

int32 GetCandidateQueueLimit() {
  return std::min(kHardLimitCandidateQueueSize, FLAGS_max_queued_split_candidates);
}

} // namespace

TabletSplitManager::TabletSplitManager(
    const TabletSplitCandidateFilterIf* filter, TabletSplitDriverIf* driver):
    filter_(filter),
    driver_(driver) {}

Status TabletSplitManager::Init() {
  process_tablet_candidates_task_.reset(new BackgroundTask(
    std::function<void()>([this]() { ProcessQueuedSplitItems(); }),
    "tablet split manager",
    "process queued tablet split candidates",
    std::chrono::milliseconds(FLAGS_process_split_tablet_candidates_interval_msec)));
  return process_tablet_candidates_task_->Init();
}

void TabletSplitManager::Shutdown() {
  if (process_tablet_candidates_task_) {
    process_tablet_candidates_task_->Shutdown();
  }
}

Status TabletSplitManager::ScheduleSplitIfNeeded(
    const TabletInfo& tablet_info,
    const TabletServerId& drive_info_ts_uuid,
    const TabletReplicaDriveInfo& drive_info) {
  UniqueLock<decltype(mutex_)> lock(mutex_);
  if (candidates_.size() >= GetCandidateQueueLimit()) {
    return Status::OK();
  }
  auto tablet_id = tablet_info.tablet_id();
  if (std::find(candidates_.begin(), candidates_.end(), tablet_id) != candidates_.end()) {
    return Status::OK();
  }
  auto is_tablet_leader_drive_info = (
      VERIFY_RESULT(tablet_info.GetLeader())->permanent_uuid() == drive_info_ts_uuid);
  if (is_tablet_leader_drive_info
      && filter_->ValidateSplitCandidate(tablet_info).ok()
      && filter_->ShouldSplitValidCandidate(drive_info)) {
    candidates_.push_back(tablet_id);
  }
  return Status::OK();
}

void TabletSplitManager::ProcessQueuedSplitItems() {
  UniqueLock<decltype(mutex_)> lock(mutex_);
  if (!candidates_.empty()) {
    auto tablet_id = candidates_.front();
    WARN_NOT_OK(
        driver_->SplitTablet(tablet_id),
        Format("Failed to trigger split for tablet_id: $0.", tablet_id));
    candidates_.pop_front();
  }
}

}  // namespace master
}  // namespace yb
