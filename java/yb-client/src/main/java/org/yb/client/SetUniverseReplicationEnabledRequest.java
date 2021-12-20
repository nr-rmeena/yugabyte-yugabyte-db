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

public class SetUniverseReplicationEnabledRequest
  extends YRpc<SetUniverseReplicationEnabledResponse> {

  private final String replicationGroupName;
  private final boolean enabled;

  SetUniverseReplicationEnabledRequest(
    YBTable table,
    String replicationGroupName,
    boolean enabled) {
    super(table);
    this.replicationGroupName = replicationGroupName;
    this.enabled = enabled;
  }

  @Override
  ChannelBuffer serialize(Message header) {
    assert header.isInitialized();

    final Master.SetUniverseReplicationEnabledRequestPB.Builder builder =
      Master.SetUniverseReplicationEnabledRequestPB.newBuilder()
        .setProducerId(replicationGroupName)
        .setIsEnabled(enabled);

    return toChannelBuffer(header, builder.build());
  }

  @Override
  String serviceName() {
    return MASTER_SERVICE_NAME;
  }

  @Override
  String method() {
    return "SetUniverseReplicationEnabled";
  }

  @Override
  Pair<SetUniverseReplicationEnabledResponse, Object> deserialize(
    CallResponse callResponse, String tsUUID) throws Exception {
    final Master.SetUniverseReplicationEnabledResponsePB.Builder builder =
      Master.SetUniverseReplicationEnabledResponsePB.newBuilder();

    readProtobuf(callResponse.getPBMessage(), builder);

    final Master.MasterErrorPB error = builder.hasError() ? builder.getError() : null;

    SetUniverseReplicationEnabledResponse response =
      new SetUniverseReplicationEnabledResponse(deadlineTracker.getElapsedMillis(), tsUUID, error);

    return new Pair<>(response, error);
  }
}
