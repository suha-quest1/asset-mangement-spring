package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Variables visible in one GP review sub-process instance. */
public class GpScope {
  @JsonProperty("callId") public String callId = "";
  @JsonProperty("highRiskLp") public LPResponse highRiskLp = new LPResponse();
  @JsonProperty("gpAction") public String gpAction = "";
  @JsonProperty("gpName") public String gpName = "";
  @JsonProperty("gpComment") public String gpComment = "";
}
