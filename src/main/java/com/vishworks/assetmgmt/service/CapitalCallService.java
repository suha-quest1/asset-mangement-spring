package com.vishworks.assetmgmt.service;

import static com.vishworks.assetmgmt.service.NotificationClient.m;
import static com.vishworks.assetmgmt.util.GoFmt.round2;
import static com.vishworks.assetmgmt.util.GoFmt.sprintf;
import static com.vishworks.assetmgmt.util.Lists.nz;

import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.handlers.ReportWriter;
import com.vishworks.assetmgmt.models.BridgeResult;
import com.vishworks.assetmgmt.models.CallState;
import com.vishworks.assetmgmt.models.CapitalCallInput;
import com.vishworks.assetmgmt.models.GpScope;
import com.vishworks.assetmgmt.models.LP;
import com.vishworks.assetmgmt.models.LPResponse;
import com.vishworks.assetmgmt.models.LpScope;
import com.vishworks.assetmgmt.models.Models;
import com.vishworks.assetmgmt.models.PortfolioRisk;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.GoFmt;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * The business logic of the Intelligent Capital Call &amp; Liquidity Orchestration use case,
 * one method per BPMN service task (moved unchanged from the former Handlers class). The job
 * worker components read the process variables, call these methods and return the variables.
 */
@Service
public class CapitalCallService {
  private final Db db;
  private final CallRepository calls;
  private final NotificationClient notifications;
  private final RiskScorerClient riskScorer;
  private final CreditFacilityClient creditFacility;
  private final ReportWriter reportWriter;
  /** Public URL of the API server / LP portal. */
  private final String portalUrl;
  /** Public URL of Operate/Tasklist. */
  private final String camundaUrl;

  public CapitalCallService(Db db, CallRepository calls, NotificationClient notifications, RiskScorerClient riskScorer,
      CreditFacilityClient creditFacility, ReportWriter reportWriter, AppProperties props) {
    this.db = db;
    this.calls = calls;
    this.notifications = notifications;
    this.riskScorer = riskScorer;
    this.creditFacility = creditFacility;
    this.reportWriter = reportWriter;
    this.portalUrl = props.portalPublicUrl();
    this.camundaUrl = props.camundaPublicUrl();
  }

  // ─── 1. issueCapitalCall ───────────────────────────────────────────────

  /** Result of {@link #issueCapitalCall}: what the job worker turns into process variables. */
  public static final class IssuedCall {
    public List<LP> lpList;
    public Map<String, Object> drawAmounts;
    public int deadlineDays;
    public long secondsPerDay;
    public LocalDateTime deadline;
  }

  /**
   * Creates the call record in the fund admin system, computes each LP's pro-rata draw
   * amount and derives the (demo-compressible) timers.
   */
  public IssuedCall issueCapitalCall(CapitalCallInput in, String processInstanceKey) throws Exception {
    if (in.callId.isEmpty() || in.lpList == null || in.lpList.isEmpty() || in.targetAmountUSD <= 0) {
      throw new ServiceException("invalid capital call input: callId, lpList and targetAmountUSD are required");
    }
    if (in.deadlineDays <= 0) {
      in.deadlineDays = 10;
    }
    long spd = in.secondsPerDay;
    if (spd <= 0) {
      spd = 86400;
    }

    double total = 0.0;
    for (LP lp : in.lpList) {
      total += lp.commitmentUSD;
    }
    Map<String, Object> draws = new TreeMap<>();
    for (LP lp : in.lpList) {
      double d = round2(in.targetAmountUSD * lp.commitmentUSD / total);
      lp.drawAmountUSD = d;
      draws.put(lp.lpId, d);
    }

    // time.Now().Add(...) written to a "timestamp" column: pgx keeps the local wall clock.
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime deadline = now.plusSeconds((long) in.deadlineDays * spd);
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        try {
          Db.exec(c, """

              		INSERT INTO capital_calls (call_id, fund_id, process_instance_key, target_amount_usd, status, deadline_days, seconds_per_day, deadline_date)
              		VALUES ($1,$2,$3,$4,'issued',$5,$6,$7)
              		ON CONFLICT (call_id) DO UPDATE SET process_instance_key=EXCLUDED.process_instance_key""",
              in.callId, in.fundId, processInstanceKey, in.targetAmountUSD, in.deadlineDays, in.secondsPerDay,
              deadline);
        } catch (SQLException e) {
          throw new ServiceException("insert capital_calls: " + Errors.message(e), e);
        }
        for (LP lp : in.lpList) {
          try {
            Db.exec(c, """

                			INSERT INTO capital_call_lps (call_id, lp_id, email, commitment_usd, draw_amount_usd, status)
                			VALUES ($1,$2,$3,$4,$5,'issued') ON CONFLICT (call_id, lp_id) DO NOTHING""",
                in.callId, lp.lpId, lp.email, lp.commitmentUSD, lp.drawAmountUSD);
          } catch (SQLException e) {
            throw new ServiceException("insert capital_call_lps: " + Errors.message(e), e);
          }
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
    calls.event(in.callId, "", "issueCapitalCall", sprintf("Call issued for $%.0f across %d LPs (deadline %d days)",
        in.targetAmountUSD, in.lpList.size(), in.deadlineDays));

    IssuedCall out = new IssuedCall();
    out.lpList = in.lpList;
    out.drawAmounts = draws;
    out.deadlineDays = in.deadlineDays;
    out.secondsPerDay = spd;
    out.deadline = deadline;
    return out;
  }

  // ─── 2. notifyLPs ──────────────────────────────────────────────────────

  public void notifyLPs(CapitalCallInput in) throws Exception {
    List<LP> lps = in.lpList == null ? List.of() : in.lpList;
    for (LP lp : lps) {
      notifications.email(m(
          "to", lp.email,
          "subject", sprintf("Capital Call %s — Action Required", in.callId),
          "body", sprintf("Dear %s, fund %s has issued capital call %s. Your pro-rata draw is $%.2f. Confirm via the secure portal: %s/?call=%s&lp=%s",
              lp.lpId, in.fundId, in.callId, lp.drawAmountUSD, portalUrl, in.callId, lp.lpId),
          "callId", in.callId, "lpId", lp.lpId, "type", "notification"));
    }
    db.exec("UPDATE capital_call_lps SET status='notified', updated_at=NOW() WHERE call_id=$1 AND status='issued'", in.callId);
    calls.setCallStatus(in.callId, "awaiting_lps");
    calls.event(in.callId, "", "notifyLPs", sprintf("Batch notice sent to %d LPs via SES", lps.size()));
  }

  // ─── child process: lp-response ────────────────────────────────────────

  static final Map<Integer, String> STAGE_NAMES = Map.of(
      1, "email reminder (D+3)", 2, "phone call (D+7)", 3, "legal notice (D+10)");

  /** Sends a tiered escalation communication to an overdue LP. */
  public void autoFollowUp(LpScope in, int stage) throws Exception {
    String name = STAGE_NAMES.getOrDefault(stage, "");
    notifications.email(m(
        "to", in.lp.email,
        "subject", sprintf("Capital Call %s — Follow-up %d: %s", in.callId, stage, name),
        "body", sprintf("LP %s has not confirmed its $%.2f draw. Escalation stage %d (%s).", in.lp.lpId, in.lp.drawAmountUSD, stage, name),
        "callId", in.callId, "lpId", in.lp.lpId, "stage", stage, "type", "followup"));
    db.exec("UPDATE capital_call_lps SET status='overdue', followup_stage=$3, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, in.lp.lpId, stage);
    calls.event(in.callId, in.lp.lpId, "autoFollowUp", "Sent " + name);
  }

  /**
   * Finalises one LP: committed / deferred (from the lpCommitment message) or defaulted
   * (deadline passed). Its output lpResponse is collected by the multi-instance call activity.
   */
  public LPResponse recordLPResponse(LpScope in, String status) throws Exception {
    double amount = 0.0;
    if (status.equals("committed")) {
      if (in.responseStatus.equals("deferred")) {
        status = "deferred";
      } else {
        amount = in.lp.drawAmountUSD;
        if (in.amountUSD != null && in.amountUSD > 0) {
          amount = in.amountUSD;
        }
      }
    }
    if (status.isEmpty()) {
      status = "defaulted";
    }
    db.exec("UPDATE capital_call_lps SET status=$3, committed_usd=$4, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, in.lp.lpId, status, amount);
    calls.refreshAggregates(in.callId);
    String detail = sprintf("LP %s -> %s ($%.0f)", in.lp.lpId, status, amount);
    if (in.followUps > 0) {
      detail += sprintf(" after %d follow-up(s)", in.followUps);
    }
    calls.event(in.callId, in.lp.lpId, "lpResponse", detail);
    LPResponse r = new LPResponse();
    r.lpId = in.lp.lpId;
    r.email = in.lp.email;
    r.commitmentUSD = in.lp.commitmentUSD;
    r.drawAmountUSD = in.lp.drawAmountUSD;
    r.status = status;
    r.amountUSD = amount;
    r.followUps = in.followUps;
    return r;
  }

  /**
   * Runs when the response window is closed early (GP force-settlement message) or the
   * hard deadline timer fires on the multi-instance: rebuilds lpResponses from the fund
   * ledger, defaulting every LP that has not committed yet.
   */
  public List<LPResponse> finalizeResponses(CallState in, String reason) throws Exception {
    List<LPResponse> out = db.query(
        "SELECT lp_id, COALESCE(email,''), commitment_usd::float8, draw_amount_usd::float8, committed_usd::float8, status, followup_stage FROM capital_call_lps WHERE call_id=$1 ORDER BY lp_id",
        rs -> {
          LPResponse r = new LPResponse();
          r.lpId = rs.getString(1);
          r.email = rs.getString(2);
          r.commitmentUSD = rs.getDouble(3);
          r.drawAmountUSD = rs.getDouble(4);
          r.amountUSD = rs.getDouble(5);
          r.status = rs.getString(6);
          r.followUps = rs.getInt(7);
          if (!r.status.equals("committed") && !r.status.equals("deferred")) {
            r.status = "defaulted";
            r.amountUSD = 0;
          }
          return r;
        },
        in.callId);
    db.exec("UPDATE capital_call_lps SET status='defaulted', committed_usd=0 WHERE call_id=$1 AND status NOT IN ('committed','deferred')", in.callId);
    calls.event(in.callId, "", "finalizeResponses", "Response window closed early (" + reason + "); outstanding LPs marked defaulted");
    return out;
  }

  // ─── 5. aggregateLiquidity ─────────────────────────────────────────────

  /** Result of {@link #aggregateLiquidity}. */
  public static final class Liquidity {
    public double committed;
    public double gap;
    public double pct;
  }

  public Liquidity aggregateLiquidity(CallState in) throws Exception {
    double committed = 0.0;
    for (LPResponse r : nz(in.lpResponses)) {
      if (r.status.equals("committed")) {
        committed += r.amountUSD;
      }
    }
    double gap = Math.max(0, in.targetAmountUSD - committed);
    double pct = 0.0;
    if (in.targetAmountUSD > 0) {
      pct = round2(gap / in.targetAmountUSD * 100);
    }
    db.exec("UPDATE capital_calls SET status='aggregated', received_amount_usd=$2, gap_usd=$3, gap_percent=$4, updated_at=NOW() WHERE call_id=$1",
        in.callId, committed, gap, pct);
    calls.event(in.callId, "", "aggregateLiquidity", sprintf("Committed $%.0f of $%.0f — gap $%.0f (%.2f%%)", committed, in.targetAmountUSD, gap, pct));
    Liquidity out = new Liquidity();
    out.committed = committed;
    out.gap = gap;
    out.pct = pct;
    return out;
  }

  // ─── 6. predictDefaultRisk ─────────────────────────────────────────────

  /** Scores every LP; updates {@code in.lpResponses} in place and returns the high-risk committed LPs. */
  public List<LPResponse> predictDefaultRisk(CallState in) throws Exception {
    List<RiskScorerClient.Score> scores = riskScorer.score(in.callId, in.lpResponses);
    Map<String, Double> sc = new LinkedHashMap<>();
    for (RiskScorerClient.Score s : nz(scores)) {
      sc.put(s.lpId, s.riskScore);
    }
    List<LPResponse> high = new ArrayList<>();
    List<LPResponse> responses = in.lpResponses;
    for (LPResponse r : nz(responses)) {
      r.riskScore = sc.getOrDefault(r.lpId, 0.0);
      db.exec("UPDATE capital_call_lps SET risk_score=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, r.lpId, r.riskScore);
      if (r.status.equals("committed") && r.riskScore > Models.HIGH_RISK_THRESHOLD) {
        high.add(r.copy());
      }
    }
    calls.setCallStatus(in.callId, "risk_scored");
    calls.event(in.callId, "", "predictDefaultRisk", sprintf("Scored %d LPs; %d committed LP(s) above %.1f risk threshold",
        nz(responses).size(), high.size(), Models.HIGH_RISK_THRESHOLD));
    return high;
  }

  // ─── 6b. scoreLPPortfolio ──────────────────────────────────────────────

  public PortfolioRisk scoreLPPortfolio(CallState in) throws Exception {
    List<LPResponse> rs = nz(in.lpResponses);
    double total = 0.0;
    for (LPResponse r : rs) {
      total += r.amountUSD;
    }
    double hhi = 0.0, sumRisk = 0.0, el = 0.0;
    for (LPResponse r : rs) {
      if (total > 0) {
        double s = r.amountUSD / total;
        hhi += s * s;
      }
      sumRisk += r.riskScore;
      el += r.amountUSD * r.riskScore;
    }
    // sort.Slice by descending risk (insertion sort for <= 12 elements in Go, i.e. stable).
    List<LPResponse> sorted = new ArrayList<>(rs);
    sorted.sort((a, b) -> Double.compare(b.riskScore, a.riskScore));
    List<String> top = new ArrayList<>();
    for (int i = 0; i < sorted.size() && i < 3; i++) {
      if (sorted.get(i).riskScore > 0.5) {
        top.add(sorted.get(i).lpId);
      }
    }
    PortfolioRisk pr = new PortfolioRisk();
    pr.concentrationScore = GoFmt.round(hhi * 10000) / 10000;
    pr.topRiskyLPs = top;
    pr.averageRiskScore = GoFmt.round(sumRisk / Math.max(1, rs.size()) * 10000) / 10000;
    pr.expectedLossUSD = round2(el);
    db.exec("UPDATE capital_calls SET concentration_hhi=$2 WHERE call_id=$1", in.callId, pr.concentrationScore);
    calls.event(in.callId, "", "scoreLPPortfolio", sprintf("HHI %.4f, top risky %v", pr.concentrationScore, top));
    return pr;
  }

  // ─── 7. triggerBridge ──────────────────────────────────────────────────

  public BridgeResult triggerBridge(CallState in) throws Exception {
    BridgeResult br = creditFacility.drawdown(in.callId, in.gapUSD);
    if (br == null) {
      br = new BridgeResult();
    }
    db.exec("UPDATE capital_calls SET bridge_triggered=TRUE, bridge_confirmation=$2, bridge_amount_usd=$3, bridge_fee_usd=$4 WHERE call_id=$1",
        in.callId, br.confirmationId, br.drawdownUSD, br.feeUSD);
    calls.event(in.callId, "", "triggerBridge", sprintf("Credit line drawdown $%.0f confirmed %s (fee $%.0f)", br.drawdownUSD, br.confirmationId, br.feeUSD));
    return br;
  }

  // ─── 8. escalateToGP / gpApprovalGate ──────────────────────────────────

  public void escalateToGP(GpScope in) throws Exception {
    LPResponse lp = in.highRiskLp;
    notifications.email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[ESCALATION] High-risk LP %s on call %s", lp.lpId, in.callId),
        "body", sprintf("LP %s committed $%.2f but has default risk %.2f (> %.1f). Decide WAIVE or ENFORCE in Tasklist (%s/tasklist) or via POST /api/capital-calls/%s/gp-decision.",
            lp.lpId, lp.amountUSD, lp.riskScore, Models.HIGH_RISK_THRESHOLD, camundaUrl, in.callId),
        "callId", in.callId, "lpId", lp.lpId, "type", "escalation"));
    db.exec("INSERT INTO gp_escalations (call_id, lp_id, risk_score) VALUES ($1,$2,$3) ON CONFLICT (call_id, lp_id) DO NOTHING",
        in.callId, lp.lpId, lp.riskScore);
    calls.setCallStatus(in.callId, "awaiting_gp");
    calls.event(in.callId, lp.lpId, "escalateToGP", sprintf("Escalated to GP (risk %.2f); awaiting approval gate", lp.riskScore));
  }

  /**
   * Runs after the GP approval gate (Tasklist user task or the gpDecision message).
   * "enforce" issues a compliance warning; the LP's commitment stays recorded either way.
   * Normalises {@code in.gpAction} and {@code in.gpName} in place.
   */
  public void applyGPDecision(GpScope in) throws Exception {
    if (!in.gpAction.equals("enforce")) {
      in.gpAction = "waive";
    }
    if (in.gpName.isEmpty()) {
      in.gpName = "GP Admin";
    }
    LPResponse lp = in.highRiskLp;
    if (in.gpAction.equals("enforce")) {
      notifications.email(m(
          "to", lp.email,
          "subject", sprintf("[COMPLIANCE WARNING] Capital Call %s — Enforcement Notice", in.callId),
          "body", sprintf("The General Partner (%s) has issued an enforcement notice on your $%.2f commitment. Your contribution remains recorded and is subject to compliance review.", in.gpName, lp.amountUSD),
          "callId", in.callId, "lpId", lp.lpId, "type", "enforcement_warning"));
    }
    notifications.email(m(
        "to", "#gp-investment-committee (Slack)",
        "subject", sprintf("[GP DECISION] %s %s on call %s", in.gpName, in.gpAction, in.callId),
        "body", sprintf("Decision for LP %s: %s. Comment: %s", lp.lpId, in.gpAction, in.gpComment),
        "callId", in.callId, "lpId", lp.lpId, "type", "gp_decision"));
    db.exec("UPDATE gp_escalations SET status='decided', action=$3, gp_name=$4, decided_at=NOW() WHERE call_id=$1 AND lp_id=$2",
        in.callId, lp.lpId, in.gpAction, in.gpName);
    db.exec("UPDATE capital_call_lps SET gp_action=$3 WHERE call_id=$1 AND lp_id=$2", in.callId, lp.lpId, in.gpAction);
    calls.event(in.callId, lp.lpId, "gpApprovalGate", sprintf("GP %s decided: %s", in.gpName, in.gpAction));
  }

  // ─── 9. settleAndReconcile ─────────────────────────────────────────────

  public int settleAndReconcile(CallState in) throws Exception {
    int wires = 0;
    try (Connection c = db.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Idempotent on job retry: rebuild this call's ledger.
        Db.exec(c, "DELETE FROM ledger_entries WHERE call_id=$1", in.callId);
        for (LPResponse r : nz(in.lpResponses)) {
          Db.exec(c, "UPDATE capital_call_lps SET status=$3, committed_usd=$4 WHERE call_id=$1 AND lp_id=$2",
              in.callId, r.lpId, r.status, r.amountUSD);
          if (!r.status.equals("committed") || r.amountUSD <= 0) {
            continue;
          }
          String wire = sprintf("WIRE-%s-%s", in.callId, r.lpId);
          // Double entry: debit fund cash (asset up), credit LP capital account (contributed capital).
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, lp_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,$2,'fund/cash',$3,$4,'capital_contribution',$5)""",
              in.callId, r.lpId, "lp/" + r.lpId + "/capital", r.amountUSD, wire);
          wires++;
        }
        if (in.bridgeResult != null && in.bridgeResult.drawdownUSD > 0) {
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,'fund/cash','liability/credit-facility',$2,'bridge_drawdown',$3)""",
              in.callId, in.bridgeResult.drawdownUSD, in.bridgeResult.confirmationId);
          Db.exec(c, """
              INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
              			VALUES ($1,'expense/financing-fees','fund/cash',$2,'bridge_fee',$3)""",
              in.callId, in.bridgeResult.feeUSD, in.bridgeResult.confirmationId);
        }
        Db.exec(c, "UPDATE capital_calls SET status='settled', updated_at=NOW() WHERE call_id=$1", in.callId);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    }
    calls.event(in.callId, "", "settleAndReconcile", sprintf("%d wire transfers booked; double-entry ledger reconciled", wires));
    return wires;
  }

  // ─── 10. emitLiquidityReport ───────────────────────────────────────────

  /** Writes reports/{callId}.json and .pdf; returns {jsonUrlPath, pdfUrlPath}. */
  public String[] emitLiquidityReport(CallState in, String processInstanceKey) throws Exception {
    String[] paths = reportWriter.writeReport(in, processInstanceKey);
    db.exec("UPDATE capital_calls SET status='completed', report_path=$2, updated_at=NOW() WHERE call_id=$1",
        in.callId, "/reports/" + in.callId + ".pdf");
    calls.event(in.callId, "", "emitLiquidityReport", "Regulatory liquidity report emitted (PDF + JSON)");
    return paths;
  }

  // ─── cancel path ───────────────────────────────────────────────────────

  public void markCallCancelled(CallState in) throws Exception {
    db.exec("UPDATE capital_call_lps SET status='cancelled' WHERE call_id=$1", in.callId);
    calls.setCallStatus(in.callId, "cancelled");
    for (LP lp : nz(in.lpList)) {
      notifications.email(m("to", lp.email, "subject", sprintf("Capital Call %s — Cancelled", in.callId),
          "body", "The General Partner has cancelled this capital call. No action is required.",
          "callId", in.callId, "lpId", lp.lpId, "type", "cancellation"));
    }
    calls.event(in.callId, "", "markCallCancelled", "Capital call cancelled by GP; LP response processes terminated");
  }
}
