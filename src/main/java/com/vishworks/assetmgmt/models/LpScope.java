package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Variables visible in an lp-response child instance. */
public class LpScope {
  @JsonProperty("callId") public String callId = "";
  @JsonProperty("lp") public LP lp = new LP();
  @JsonProperty("amountUSD") public Double amountUSD;
  @JsonProperty("responseStatus") public String responseStatus = "";
  @JsonProperty("followUps") public int followUps;
}
