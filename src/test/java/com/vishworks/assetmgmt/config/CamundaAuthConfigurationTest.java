package com.vishworks.assetmgmt.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CamundaAuthConfigurationTest {
  static Map<String, String> headers(io.camunda.client.CredentialsProvider p) throws Exception {
    Map<String, String> h = new HashMap<>();
    p.applyCredentials(h::put);
    return h;
  }

  @Test
  void basicAuthAllowsAnEmptyPassword() throws Exception {
    assertEquals("Basic YWRtaW46cHc=", headers(new CamundaAuthConfiguration.BasicCredentials("admin", "pw")).get("Authorization"));
    assertEquals("Basic YWRtaW46", headers(new CamundaAuthConfiguration.BasicCredentials("admin", "")).get("Authorization"));
  }

  @Test
  void noCredentialsSendsNoHeader() throws Exception {
    assertTrue(headers(new CamundaAuthConfiguration.NoCredentials()).isEmpty());
  }
}
