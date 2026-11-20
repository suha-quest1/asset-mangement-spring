package com.vishworks.assetmgmt.worker;

import com.vishworks.assetmgmt.camunda.ProcessConstants;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.models.LpScope;
import com.vishworks.assetmgmt.service.CapitalCallService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Job workers of the service tasks in bpmn/lp-response.bpmn (one child instance per LP). */
@Component
public class LpResponseWorkers {
  private final CapitalCallService capitalCallService;

  public LpResponseWorkers(CapitalCallService capitalCallService) {
    this.capitalCallService = capitalCallService;
  }

  /** Sends a tiered escalation communication to an overdue LP (custom header "stage"). */
  @JobWorker(type = ProcessConstants.JOB_AUTO_FOLLOW_UP)
  public Map<String, Object> autoFollowUp(ActivatedJob job) throws Exception {
    LpScope in = job.getVariablesAsType(LpScope.class);
    int stage = JobValues.atoi(JobValues.header(job, "stage"));
    capitalCallService.autoFollowUp(in, stage);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("followUps", stage);
    return vars;
  }

  /** Finalises one LP as committed / deferred / defaulted (custom header "status"). */
  @JobWorker(type = ProcessConstants.JOB_RECORD_LP_RESPONSE)
  public Map<String, Object> recordLPResponse(ActivatedJob job) throws Exception {
    LpScope in = job.getVariablesAsType(LpScope.class);
    LPResponse r = capitalCallService.recordLPResponse(in, JobValues.header(job, "status"));
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("lpResponse", r);
    return vars;
  }
}
