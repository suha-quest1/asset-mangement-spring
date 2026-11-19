package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** The result of one lp-response child process. */
@JsonPropertyOrder({"lpId", "email", "commitmentUSD", "drawAmountUSD", "status", "amountUSD", "riskScore", "followUps"})
public class LPResponse {
  @JsonProperty("lpId") public String lpId = "";
  @JsonProperty("email") @JsonInclude(JsonInclude.Include.NON_EMPTY) public String email = "";
  @JsonProperty("commitmentUSD") @JsonInclude(JsonInclude.Include.NON_DEFAULT) public double commitmentUSD;
  @JsonProperty("drawAmountUSD") @JsonInclude(JsonInclude.Include.NON_DEFAULT) public double drawAmountUSD;
  /** committed | deferred | defaulted. */
  @JsonProperty("status") public String status = "";
  @JsonProperty("amountUSD") public double amountUSD;
  @JsonProperty("riskScore") public double riskScore;
  @JsonProperty("followUps") @JsonInclude(JsonInclude.Include.NON_DEFAULT) public int followUps;

  public LPResponse copy() {
    LPResponse r = new LPResponse();
    r.lpId = lpId;
    r.email = email;
    r.commitmentUSD = commitmentUSD;
    r.drawAmountUSD = drawAmountUSD;
    r.status = status;
    r.amountUSD = amountUSD;
    r.riskScore = riskScore;
    r.followUps = followUps;
    return r;
  }
}
