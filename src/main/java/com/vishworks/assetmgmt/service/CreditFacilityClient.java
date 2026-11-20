package com.vishworks.assetmgmt.service;

import static com.vishworks.assetmgmt.service.NotificationClient.m;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.util.Errors;
import java.io.IOException;
import org.springframework.stereotype.Service;

/** The credit facility (bridge loan) API. */
@Service
public class CreditFacilityClient {
  private final HttpJson httpJson;
  private final String creditUrl;

  public CreditFacilityClient(HttpJson httpJson, AppProperties props) {
    this.httpJson = httpJson;
    this.creditUrl = props.creditFacilityUrl();
  }

  public BridgeResult drawdown(String callId, double amount) throws ServiceException, InterruptedException {
    try {
      return httpJson.postJSON(creditUrl + "/drawdown", m("callId", callId, "amount", amount), new TypeReference<BridgeResult>() {});
    } catch (IOException e) {
      throw new ServiceException("credit facility: " + Errors.message(e), e);
    }
  }
}
