package com.yugabyte.yw.models.helpers;

import com.yugabyte.yw.models.AvailabilityZone;
import lombok.Data;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
public class LoadBalancerConfig {
  private String lbName;
  private Map<AvailabilityZone, Set<NodeDetails>> azNodes;
  private Set<NodeDetails> allNodes;

  public LoadBalancerConfig(String lbName) {
    this.lbName = lbName;
    this.azNodes = new HashMap<>();
    this.allNodes = new HashSet<>();
  }

  public void addNodes(AvailabilityZone az, Set<NodeDetails> nodes) {
    if (CollectionUtils.isNotEmpty(nodes)) {
      azNodes.computeIfAbsent(az, k -> new HashSet<>()).addAll(nodes);
      allNodes.addAll(nodes);
    }
  }

  public void addAll(Map<AvailabilityZone, Set<NodeDetails>> otherAzNodes) {
    if (MapUtils.isNotEmpty(otherAzNodes)) {
      otherAzNodes.forEach(
          (key, value) ->
              azNodes.merge(
                  key,
                  value,
                  (v1, v2) -> {
                    v1.addAll(v2);
                    return v1;
                  }));
      for (Set<NodeDetails> otherNodes : otherAzNodes.values()) {
        allNodes.addAll(otherNodes);
      }
    }
  }
}
