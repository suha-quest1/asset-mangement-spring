package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** The start payload of the capital-call process. */
@JsonPropertyOrder({"callId", "fundId", "targetAmountUSD", "lpList", "deadlineDays", "secondsPerDay"})
public class CapitalCallInput {
  @JsonProperty("callId") public String callId = "";
  @JsonProperty("fundId") public String fundId = "";
  @JsonProperty("targetAmountUSD") public double targetAmountUSD;
  @JsonProperty("lpList") public List<LP> lpList;
  @JsonProperty("deadlineDays") public int deadlineDays;
  /** Compresses "business days" for demos (0 = real days). omitempty. */
  @JsonProperty("secondsPerDay") @JsonInclude(JsonInclude.Include.NON_DEFAULT) public int secondsPerDay;
}
