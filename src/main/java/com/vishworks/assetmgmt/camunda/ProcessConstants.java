package com.vishworks.assetmgmt.camunda;

/**
 * Job types of the BPMN service tasks (zeebe:taskDefinition type="…" in bpmn/*.bpmn).
 * Every job worker method references exactly one of these constants.
 */
public final class ProcessConstants {
  private ProcessConstants() {}

  public static final String JOB_ISSUE_CAPITAL_CALL = "issueCapitalCall";
  public static final String JOB_NOTIFY_LPS = "notifyLPs";
  public static final String JOB_AUTO_FOLLOW_UP = "autoFollowUp";
  public static final String JOB_RECORD_LP_RESPONSE = "recordLPResponse";
  public static final String JOB_FINALIZE_RESPONSES = "finalizeResponses";
  public static final String JOB_AGGREGATE_LIQUIDITY = "aggregateLiquidity";
  public static final String JOB_PREDICT_DEFAULT_RISK = "predictDefaultRisk";
  public static final String JOB_SCORE_LP_PORTFOLIO = "scoreLPPortfolio";
  public static final String JOB_TRIGGER_BRIDGE = "triggerBridge";
  public static final String JOB_ESCALATE_TO_GP = "escalateToGP";
  public static final String JOB_APPLY_GP_DECISION = "applyGPDecision";
  public static final String JOB_SETTLE_AND_RECONCILE = "settleAndReconcile";
  public static final String JOB_EMIT_LIQUIDITY_REPORT = "emitLiquidityReport";
  public static final String JOB_MARK_CALL_CANCELLED = "markCallCancelled";
}
