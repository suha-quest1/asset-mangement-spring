package com.vishworks.assetmgmt.config;

import io.camunda.client.CredentialsProvider;
import io.camunda.client.spring.configuration.CredentialsProviderConfiguration;
import io.camunda.client.spring.properties.CamundaClientProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Camunda client credentials with the same rule as before: HTTP basic auth when
 * CAMUNDA_USERNAME is set (CAMUNDA_PASSWORD may be empty), otherwise none. The starter's own
 * basic-auth provider rejects an empty password and then silently falls back to no
 * authentication, so the provider is supplied here.
 */
@Configuration(proxyBeanMethods = false)
public class CamundaAuthConfiguration {

  /**
   * Replaces the starter's credentials-provider factory (it is a {@code @ConditionalOnMissingBean}
   * bean that derives the provider of the CamundaClient from {@code camunda.client.auth.*}).
   */
  @Bean
  public CredentialsProviderConfiguration credentialsProviderConfiguration() {
    return new CredentialsProviderConfiguration() {
      @Override
      public CredentialsProvider camundaClientCredentialsProvider(CamundaClientProperties properties) {
        return fromEnvironment();
      }
    };
  }

  static CredentialsProvider fromEnvironment() {
    String user = System.getenv("CAMUNDA_USERNAME");
    if (user == null || user.isEmpty()) {
      return new NoCredentials();
    }
    String pw = System.getenv("CAMUNDA_PASSWORD");
    return new BasicCredentials(user, pw == null ? "" : pw);
  }

  /** Authorization: Basic base64(user:password). */
  static final class BasicCredentials implements CredentialsProvider {
    private final String header;

    BasicCredentials(String user, String password) {
      header = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void applyCredentials(CredentialsApplier applier) {
      applier.put("Authorization", header);
    }

    @Override
    public boolean shouldRetryRequest(StatusCode statusCode) {
      return false;
    }
  }

  /** No credentials (the cluster's API is unprotected). */
  static final class NoCredentials implements CredentialsProvider {
    @Override
    public void applyCredentials(CredentialsApplier applier) {}

    @Override
    public boolean shouldRetryRequest(StatusCode statusCode) {
      return false;
    }
  }
}
