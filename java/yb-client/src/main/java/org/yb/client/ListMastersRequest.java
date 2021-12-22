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
import org.yb.Common.PeerRole;
import org.yb.Common.HostPortPB;
import org.yb.consensus.Metadata;
import org.yb.WireProtocol;
import org.yb.annotations.InterfaceAudience;
import org.yb.master.Master;
import org.yb.util.Pair;
import org.yb.util.ServerInfo;
import org.jboss.netty.buffer.ChannelBuffer;

import java.util.ArrayList;
import java.util.List;

@InterfaceAudience.Private
class ListMastersRequest extends YRpc<ListMastersResponse> {
  public ListMastersRequest(YBTable masterTable) {
    super(masterTable);
  }

  @Override
  ChannelBuffer serialize(Message header) {
    assert header.isInitialized();
    final Master.ListMastersRequestPB.Builder builder =
      Master.ListMastersRequestPB.newBuilder();
    return toChannelBuffer(header, builder.build());
  }

  @Override
  String serviceName() { return MASTER_SERVICE_NAME; }

  @Override
  String method() {
    return "ListMasters";
  }

  @Override
  Pair<ListMastersResponse, Object> deserialize(CallResponse callResponse,
                                                String masterUUID) throws Exception {
    final Master.ListMastersResponsePB.Builder respBuilder =
      Master.ListMastersResponsePB.newBuilder();
    readProtobuf(callResponse.getPBMessage(), respBuilder);
    List<ServerInfo> masters = new ArrayList<ServerInfo>();
    boolean hasErr = respBuilder.hasError();
    if (!hasErr) {
      ServerInfo master = null;
      HostPortPB rpc_addr = null;
      for (WireProtocol.ServerEntryPB entry : respBuilder.getMastersList()) {
        if (entry.hasRegistration()) {
          if (entry.getRegistration().getBroadcastAddressesList().isEmpty()) {
            rpc_addr = entry.getRegistration().getPrivateRpcAddresses(0);
          } else {
            rpc_addr = entry.getRegistration().getBroadcastAddresses(0);
          }
        } else {
          rpc_addr = null;
        }
        master = new ServerInfo(entry.getInstanceId().getPermanentUuid().toStringUtf8(),
                                rpc_addr != null ? rpc_addr.getHost() : "UNKNOWN",
                                rpc_addr != null ? rpc_addr.getPort() : 0,
                                entry.getRole() == PeerRole.LEADER,
                                entry.hasError() ? entry.getError().getCode().name() : "ALIVE");
        masters.add(master);
      }
    }

    ListMastersResponse response = new ListMastersResponse(
        deadlineTracker.getElapsedMillis(), masterUUID, hasErr, masters);

    return new Pair<ListMastersResponse, Object>(response, hasErr ? respBuilder.getError() : null);
  }
}
