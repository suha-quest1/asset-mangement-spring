package com.vishworks.assetmgmt.worker;

import static com.vishworks.assetmgmt.util.GoFmt.round2;

import com.vishworks.assetmgmt.camunda.ProcessConstants;
import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.CapitalCallInput;
import com.vishworks.assetmgmt.models.GpScope;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.models.PortfolioRisk;
import com.vishworks.assetmgmt.service.CapitalCallService;
import com.vishworks.assetmgmt.service.CapitalCallService.IssuedCall;
import com.vishworks.assetmgmt.service.CapitalCallService.Liquidity;
import com.vishworks.assetmgmt.util.GoFmt;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Job workers of the service tasks in bpmn/capital-call.bpmn. Each method reads the process
 * variables, delegates to {@link CapitalCallService} and returns the variables to complete the
 * job with. A thrown exception fails the job (see WorkerConfiguration).
 */
@Component
public class CapitalCallWorkers {
  private final CapitalCallService capitalCallService;

  public CapitalCallWorkers(CapitalCallService capitalCallService) {
    this.capitalCallService = capitalCallService;
  }

  @JobWorker(type = ProcessConstants.JOB_ISSUE_CAPITAL_CALL)
  public Map<String, Object> issueCapitalCall(ActivatedJob job) throws Exception {
    CapitalCallInput in = job.getVariablesAsType(CapitalCallInput.class);
    IssuedCall issued = capitalCallService.issueCapitalCall(in, JobValues.processInstanceKey(job));
    long spd = issued.secondsPerDay;
    // Follow-up schedule: D+3 email, D+7 call, D+10 legal notice, then deadline.
    long fin = (issued.deadlineDays - 10L) * spd;
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("lpList", issued.lpList);
    vars.put("drawAmounts", issued.drawAmounts);
    vars.put("deadlineDays", issued.deadlineDays);
    vars.put("waitStage1", JobValues.isoSeconds(3 * spd));
    vars.put("waitStage2", JobValues.isoSeconds(4 * spd));
    vars.put("waitStage3", JobValues.isoSeconds(3 * spd));
    vars.put("waitFinal", JobValues.isoSeconds(fin));
    vars.put("callDeadline", JobValues.isoSeconds((issued.deadlineDays + 3L) * spd));
    vars.put("deadlineAt", GoFmt.rfc3339Utc(issued.deadline.atZone(ZoneId.systemDefault()).toInstant()));
    vars.put("bridgeTriggered", false);
    vars.put("bridgeResult", null);
    vars.put("highRiskLPs", List.of());
    vars.put("gpDecisions", List.of());
    vars.put("settlementMode", "normal");
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_NOTIFY_LPS)
  public void notifyLPs(ActivatedJob job) throws Exception {
    CapitalCallInput in = job.getVariablesAsType(CapitalCallInput.class);
    capitalCallService.notifyLPs(in);
  }

  /** Response window closed early or hard deadline reached (custom header "reason"). */
  @JobWorker(type = ProcessConstants.JOB_FINALIZE_RESPONSES)
  public Map<String, Object> finalizeResponses(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    String reason = JobValues.header(job, "reason");
    List<LPResponse> responses = capitalCallService.finalizeResponses(in, reason);
    Map<String, Object> vars = new LinkedHashMap<>();
    // A Go nil slice (no rows) is encoded as null.
    vars.put("lpResponses", responses.isEmpty() ? null : responses);
    vars.put("settlementMode", reason);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_AGGREGATE_LIQUIDITY)
  public Map<String, Object> aggregateLiquidity(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    Liquidity l = capitalCallService.aggregateLiquidity(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("totalCommitted", round2(l.committed));
    vars.put("gapUSD", round2(l.gap));
    vars.put("gapPercent", l.pct);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_PREDICT_DEFAULT_RISK)
  public Map<String, Object> predictDefaultRisk(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    List<LPResponse> high = capitalCallService.predictDefaultRisk(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("lpResponses", in.lpResponses);
    vars.put("highRiskLPs", high);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_SCORE_LP_PORTFOLIO)
  public Map<String, Object> scoreLPPortfolio(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    PortfolioRisk pr = capitalCallService.scoreLPPortfolio(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("portfolioRisk", pr);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_TRIGGER_BRIDGE)
  public Map<String, Object> triggerBridge(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    BridgeResult br = capitalCallService.triggerBridge(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("bridgeTriggered", true);
    vars.put("bridgeResult", br);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_ESCALATE_TO_GP)
  public void escalateToGP(ActivatedJob job) throws Exception {
    GpScope in = job.getVariablesAsType(GpScope.class);
    capitalCallService.escalateToGP(in);
  }

  @JobWorker(type = ProcessConstants.JOB_APPLY_GP_DECISION)
  public Map<String, Object> applyGPDecision(ActivatedJob job) throws Exception {
    GpScope in = job.getVariablesAsType(GpScope.class);
    capitalCallService.applyGPDecision(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("gpAction", in.gpAction);
    vars.put("gpName", in.gpName);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_SETTLE_AND_RECONCILE)
  public Map<String, Object> settleAndReconcile(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    int wires = capitalCallService.settleAndReconcile(in);
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("wiresSettled", wires);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_EMIT_LIQUIDITY_REPORT)
  public Map<String, Object> emitLiquidityReport(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    String[] paths = capitalCallService.emitLiquidityReport(in, JobValues.processInstanceKey(job));
    Map<String, Object> vars = new LinkedHashMap<>();
    vars.put("reportPdf", paths[1]);
    vars.put("reportJson", paths[0]);
    return vars;
  }

  @JobWorker(type = ProcessConstants.JOB_MARK_CALL_CANCELLED)
  public void markCallCancelled(ActivatedJob job) throws Exception {
    CallState in = job.getVariablesAsType(CallState.class);
    capitalCallService.markCallCancelled(in);
  }
}
