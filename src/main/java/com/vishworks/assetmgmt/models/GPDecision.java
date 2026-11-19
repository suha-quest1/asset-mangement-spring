package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"lpId", "riskScore", "action", "gpName"})
public class GPDecision {
  @JsonProperty("lpId") public String lpId = "";
  @JsonProperty("riskScore") public double riskScore;
  /** waive | enforce. */
  @JsonProperty("action") public String action = "";
  @JsonProperty("gpName") public String gpName = "";
}
