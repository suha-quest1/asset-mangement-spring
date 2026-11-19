package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"confirmationId", "drawdownUSD", "feeUSD"})
public class BridgeResult {
  @JsonProperty("confirmationId") public String confirmationId = "";
  @JsonProperty("drawdownUSD") public double drawdownUSD;
  @JsonProperty("feeUSD") public double feeUSD;
}
