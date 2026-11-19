package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/** The union of process variables the late-stage workers read. */
@JsonPropertyOrder({"callId", "fundId", "targetAmountUSD", "deadlineDays", "lpList", "lpResponses", "totalCommitted",
    "gapUSD", "gapPercent", "bridgeTriggered", "bridgeResult", "portfolioRisk", "highRiskLPs", "gpDecisions",
    "settlementMode"})
public class CallState {
  @JsonProperty("callId") public String callId = "";
  @JsonProperty("fundId") public String fundId = "";
  @JsonProperty("targetAmountUSD") public double targetAmountUSD;
  @JsonProperty("deadlineDays") public int deadlineDays;
  @JsonProperty("lpList") public List<LP> lpList;
  @JsonProperty("lpResponses") public List<LPResponse> lpResponses;
  @JsonProperty("totalCommitted") public double totalCommitted;
  @JsonProperty("gapUSD") public double gapUSD;
  @JsonProperty("gapPercent") public double gapPercent;
  @JsonProperty("bridgeTriggered") public boolean bridgeTriggered;
  @JsonProperty("bridgeResult") public BridgeResult bridgeResult;
  @JsonProperty("portfolioRisk") public PortfolioRisk portfolioRisk;
  @JsonProperty("highRiskLPs") public List<LPResponse> highRiskLPs;
  @JsonProperty("gpDecisions") public List<GPDecision> gpDecisions;
  @JsonProperty("settlementMode") public String settlementMode = "";
}
