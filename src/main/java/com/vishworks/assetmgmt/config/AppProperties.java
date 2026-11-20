package com.vishworks.assetmgmt.config;

import static com.vishworks.assetmgmt.util.Env.get;

import org.springframework.stereotype.Component;

/**
 * Application settings, read from the same environment variables (and with the same
 * defaults) as before the move to Spring Boot. Empty counts as unset, like the Go env().
 */
@Component
public class AppProperties {
  private final String databaseUrl = get("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable");
  private final String sesUrl = get("SES_URL", "http://localhost:8081");
  private final String mlScorerUrl = get("ML_SCORER_URL", "http://localhost:5001");
  private final String creditFacilityUrl = get("CREDIT_FACILITY_URL", "http://localhost:8082");
  private final String reportDir = get("REPORT_DIR", "./reports");
  private final String portalPublicUrl = get("PORTAL_PUBLIC_URL", "http://localhost:8090");
  private final String camundaPublicUrl = get("CAMUNDA_PUBLIC_URL", "http://localhost:8080");
  private final String sesPublicUrl = get("SES_PUBLIC_URL", "http://localhost:8081");
  private final String listenAddr = get("LISTEN_ADDR", ":8090");
  private final String bpmnDir = get("BPMN_DIR", "./bpmn");
  private final boolean deployOnStart = get("DEPLOY_ON_START", "true").equals("true");
  private final String camundaRestAddress = get("CAMUNDA_REST_ADDRESS", "http://localhost:8080");

  public String databaseUrl() { return databaseUrl; }
  public String sesUrl() { return sesUrl; }
  public String mlScorerUrl() { return mlScorerUrl; }
  public String creditFacilityUrl() { return creditFacilityUrl; }
  public String reportDir() { return reportDir; }
  /** Public URL of the API server / LP portal. */
  public String portalPublicUrl() { return portalPublicUrl; }
  /** Public URL of Operate/Tasklist. */
  public String camundaPublicUrl() { return camundaPublicUrl; }
  public String sesPublicUrl() { return sesPublicUrl; }
  public String listenAddr() { return listenAddr; }
  public String bpmnDir() { return bpmnDir; }
  public boolean deployOnStart() { return deployOnStart; }
  public String camundaRestAddress() { return camundaRestAddress; }
}
