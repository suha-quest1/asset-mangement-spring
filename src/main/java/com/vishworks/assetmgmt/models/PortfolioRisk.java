package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({"concentrationScore", "topRiskyLPs", "averageRiskScore", "expectedLossUSD"})
public class PortfolioRisk {
  /** HHI 0..1. */
  @JsonProperty("concentrationScore") public double concentrationScore;
  @JsonProperty("topRiskyLPs") public List<String> topRiskyLPs;
  @JsonProperty("averageRiskScore") public double averageRiskScore;
  @JsonProperty("expectedLossUSD") public double expectedLossUSD;
}
