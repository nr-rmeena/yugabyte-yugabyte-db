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
package org.yb.client;

import com.google.protobuf.Message;
import org.jboss.netty.buffer.ChannelBuffer;
import org.yb.master.Master;
import org.yb.util.Pair;

public class DeleteUniverseReplicationRequest extends YRpc<DeleteUniverseReplicationResponse> {

  private final String replicationGroupName;

  DeleteUniverseReplicationRequest(YBTable table, String replicationGroupName) {
    super(table);
    this.replicationGroupName = replicationGroupName;
  }

  @Override
  ChannelBuffer serialize(Message header) {
    assert header.isInitialized();

    final Master.DeleteUniverseReplicationRequestPB.Builder builder =
      Master.DeleteUniverseReplicationRequestPB.newBuilder().setProducerId(replicationGroupName);

    return toChannelBuffer(header, builder.build());
  }

  @Override
  String serviceName() {
    return MASTER_SERVICE_NAME;
  }

  @Override
  String method() {
    return "DeleteUniverseReplication";
  }

  @Override
  Pair<DeleteUniverseReplicationResponse, Object> deserialize(
    CallResponse callResponse, String tsUUID) throws Exception {
    final Master.DeleteUniverseReplicationResponsePB.Builder builder =
      Master.DeleteUniverseReplicationResponsePB.newBuilder();

    readProtobuf(callResponse.getPBMessage(), builder);

    final Master.MasterErrorPB error = builder.hasError() ? builder.getError() : null;

    DeleteUniverseReplicationResponse response =
      new DeleteUniverseReplicationResponse(deadlineTracker.getElapsedMillis(),
        tsUUID, error);

    return new Pair<>(response, error);
  }
}
