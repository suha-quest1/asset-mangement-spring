package com.vishworks.assetmgmt.service;

import static com.vishworks.assetmgmt.service.NotificationClient.m;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.util.Errors;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/** The ML default-risk scorer. */
@Service
public class RiskScorerClient {
  private final HttpJson httpJson;
  private final String mlScorerUrl;

  public RiskScorerClient(HttpJson httpJson, AppProperties props) {
    this.httpJson = httpJson;
    this.mlScorerUrl = props.mlScorerUrl();
  }

  /** One element of the ML scorer response. */
  public static class Score {
    @JsonProperty("lpId") public String lpId = "";
    @JsonProperty("riskScore") public double riskScore;
  }

  public List<Score> score(String callId, List<LPResponse> lpResponses) throws ServiceException, InterruptedException {
    try {
      return httpJson.postJSON(mlScorerUrl + "/score", m("callId", callId, "lpResponses", lpResponses),
          new TypeReference<List<Score>>() {});
    } catch (IOException e) {
      throw new ServiceException("ml-scorer: " + Errors.message(e), e);
    }
  }
}
