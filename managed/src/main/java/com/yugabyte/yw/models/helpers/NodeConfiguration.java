// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.helpers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import javax.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Pair of node configuration type and its value. */
@Data
@ApiModel(description = "A node configuration.")
public class NodeConfiguration {
  @NotNull
  @ApiModelProperty(required = true)
  public Type type;

  @NotNull
  @ApiModelProperty(value = "unlimited", required = true)
  @EqualsAndHashCode.Exclude
  public String value;

  /**
   * Checks if the value is accepted according to the predicate in the type.
   *
   * @return true if it is accepted or configured, else false.
   */
  @JsonIgnore
  public boolean isConfigured() {
    return type.isConfigured(value);
  }

  /** Type of the configuration. The predicate can be minimum value comparison like ulimit. */
  public enum Type {
    // TODO add more types.
    NTP_SERVICE_STATUS(v -> v.equalsIgnoreCase("running"));

    // Predicate to test if a value is acceptable.
    private final Predicate<String> predicate;

    private Type(Predicate<String> predicate) {
      this.predicate = predicate;
    }

    private Type() {
      this.predicate = null;
    }

    public boolean isConfigured(String value) {
      if (predicate == null) {
        return true;
      }
      return predicate.test(value);
    }
  }

  /** Group of types. This can be extended to add minimum requirements. */
  public enum TypeGroup {
    ALL(EnumSet.allOf(Type.class));

    private final Set<Type> requiredConfigTypes;

    private TypeGroup(Set<Type> requiredConfigTypes) {
      this.requiredConfigTypes = Collections.unmodifiableSet(requiredConfigTypes);
    }

    public Set<Type> getRequiredConfigTypes() {
      return requiredConfigTypes;
    }
  }
}
