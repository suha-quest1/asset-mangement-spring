package com.vishworks.assetmgmt.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A Limited Partner as carried in the process (lpList element). */
@JsonPropertyOrder({"lpId", "commitmentUSD", "email", "drawAmountUSD"})
public class LP {
  @JsonProperty("lpId") public String lpId = "";
  @JsonProperty("commitmentUSD") public double commitmentUSD;
  @JsonProperty("email") public String email = "";
  @JsonProperty("drawAmountUSD") @JsonInclude(JsonInclude.Include.NON_DEFAULT) public double drawAmountUSD; // omitempty
}
