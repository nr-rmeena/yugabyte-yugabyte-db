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

#ifndef YB_MASTER_TABLET_SPLIT_MANAGER_H
#define YB_MASTER_TABLET_SPLIT_MANAGER_H

#include <unordered_set>

#include "yb/master/master_fwd.h"
#include "yb/master/tablet_split_candidate_filter.h"
#include "yb/master/tablet_split_complete_handler.h"
#include "yb/master/tablet_split_driver.h"

namespace yb {
namespace master {

using std::unordered_set;

class TabletSplitManager : public TabletSplitCompleteHandlerIf {
 public:
  TabletSplitManager(TabletSplitCandidateFilterIf* filter,
                     TabletSplitDriverIf* driver,
                     XClusterSplitDriverIf* xcluster_split_driver);

  // Perform one round of tablet splitting. This method is not thread-safe.
  void MaybeDoSplitting(const TableInfoMap& table_info_map);

  void ProcessSplitTabletResult(const Status& status,
                                const TableId& split_table_id,
                                const SplitTabletIds& split_tablet_ids);

  CHECKED_STATUS ValidateSplitCandidateTable(const TableInfo& table);

  static CHECKED_STATUS ValidateSplitCandidateTablet(const TabletInfo& tablet);

 private:
  unordered_set<TabletId> FindSplitsWithTask(const vector<TableInfoPtr>& tables);

  bool ShouldSplitTablet(const TabletInfo& tablet);

  void ScheduleSplits(const unordered_set<TabletId>& splits_to_schedule);

  void DoSplitting(const TableInfoMap& table_info_map);

  TabletSplitCandidateFilterIf* filter_;
  TabletSplitDriverIf* driver_;
  XClusterSplitDriverIf* xcluster_split_driver_;

  CoarseTimePoint last_run_time_;
};

}  // namespace master
}  // namespace yb
#endif // YB_MASTER_TABLET_SPLIT_MANAGER_H
