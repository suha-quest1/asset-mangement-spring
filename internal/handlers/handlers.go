// Package handlers implements the Camunda job workers (the BPMN service
// tasks) for the Intelligent Capital Call & Liquidity Orchestration use case.
//
// Each exported job type maps 1:1 to a service task in bpmn/*.bpmn:
//
//	issueCapitalCall, notifyLPs, autoFollowUp, recordLPResponse,
//	finalizeResponses, aggregateLiquidity, predictDefaultRisk,
//	scoreLPPortfolio, triggerBridge, escalateToGP, applyGPDecision,
//	settleAndReconcile, emitLiquidityReport, markCallCancelled
package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"sort"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/vishworks/camunda-assetmgmt/internal/camunda"
	"github.com/vishworks/camunda-assetmgmt/internal/models"
)

type Handlers struct {
	DB          *pgxpool.Pool
	SESURL      string
	MLScorerURL string
	CreditURL   string
	ReportDir   string
	PortalURL   string // public URL of the API server / LP portal
	CamundaURL  string // public URL of Operate/Tasklist
	HTTP        *http.Client
}

// Register wires all job types onto the worker.
func (h *Handlers) Register(w *camunda.Worker) {
	w.Handle("issueCapitalCall", h.IssueCapitalCall)
	w.Handle("notifyLPs", h.NotifyLPs)
	w.Handle("autoFollowUp", h.AutoFollowUp)
	w.Handle("recordLPResponse", h.RecordLPResponse)
	w.Handle("finalizeResponses", h.FinalizeResponses)
	w.Handle("aggregateLiquidity", h.AggregateLiquidity)
	w.Handle("predictDefaultRisk", h.PredictDefaultRisk)
	w.Handle("scoreLPPortfolio", h.ScoreLPPortfolio)
	w.Handle("triggerBridge", h.TriggerBridge)
	w.Handle("escalateToGP", h.EscalateToGP)
	w.Handle("applyGPDecision", h.ApplyGPDecision)
	w.Handle("settleAndReconcile", h.SettleAndReconcile)
	w.Handle("emitLiquidityReport", h.EmitLiquidityReport)
	w.Handle("markCallCancelled", h.MarkCallCancelled)
}

// ─── helpers ────────────────────────────────────────────────────────────────

func round2(f float64) float64 { return math.Round(f*100) / 100 }

func isoSeconds(sec int) string {
	if sec < 1 {
		sec = 1
	}
	return fmt.Sprintf("PT%dS", sec)
}

func (h *Handlers) event(ctx context.Context, callID, lpID, step, detail string) {
	var lp any
	if lpID != "" {
		lp = lpID
	}
	if _, err := h.DB.Exec(ctx, `INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)`, callID, lp, step, detail); err != nil {
		log.Printf("event insert: %v", err)
	}
}

func (h *Handlers) postJSON(ctx context.Context, url string, in, out any) error {
	b, _ := json.Marshal(in)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := h.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("POST %s: HTTP %d: %s", url, resp.StatusCode, body)
	}
	if out != nil {
		return json.Unmarshal(body, out)
	}
	return nil
}

// email sends via mock SES. Notification failures are logged, not fatal.
func (h *Handlers) email(ctx context.Context, payload map[string]any) {
	if err := h.postJSON(ctx, h.SESURL+"/send", payload, nil); err != nil {
		log.Printf("[ses] send failed: %v", err)
	}
}

func (h *Handlers) refreshAggregates(ctx context.Context, callID string) error {
	_, err := h.DB.Exec(ctx, `
		UPDATE capital_calls SET
		  received_amount_usd = (SELECT COALESCE(SUM(committed_usd),0) FROM capital_call_lps WHERE call_id=$1 AND status='committed'),
		  updated_at = NOW()
		WHERE call_id=$1`, callID)
	return err
}

func (h *Handlers) setCallStatus(ctx context.Context, callID, status string) {
	if _, err := h.DB.Exec(ctx, `UPDATE capital_calls SET status=$2, updated_at=NOW() WHERE call_id=$1`, callID, status); err != nil {
		log.Printf("set status: %v", err)
	}
}

// ─── 1. issueCapitalCall ───────────────────────────────────────────────────

// IssueCapitalCall creates the call record in the fund admin system, computes
// each LP's pro-rata draw amount and derives the (demo-compressible) timers.
func (h *Handlers) IssueCapitalCall(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CapitalCallInput
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	if in.CallID == "" || len(in.LPList) == 0 || in.TargetAmountUSD <= 0 {
		return nil, fmt.Errorf("invalid capital call input: callId, lpList and targetAmountUSD are required")
	}
	if in.DeadlineDays <= 0 {
		in.DeadlineDays = 10
	}
	spd := in.SecondsPerDay
	if spd <= 0 {
		spd = 86400
	}

	total := 0.0
	for _, lp := range in.LPList {
		total += lp.CommitmentUSD
	}
	draws := map[string]float64{}
	for i := range in.LPList {
		d := round2(in.TargetAmountUSD * in.LPList[i].CommitmentUSD / total)
		in.LPList[i].DrawAmountUSD = d
		draws[in.LPList[i].LPID] = d
	}

	deadline := time.Now().Add(time.Duration(in.DeadlineDays*spd) * time.Second)
	tx, err := h.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `
		INSERT INTO capital_calls (call_id, fund_id, process_instance_key, target_amount_usd, status, deadline_days, seconds_per_day, deadline_date)
		VALUES ($1,$2,$3,$4,'issued',$5,$6,$7)
		ON CONFLICT (call_id) DO UPDATE SET process_instance_key=EXCLUDED.process_instance_key`,
		in.CallID, in.FundID, string(job.ProcessInstanceKey), in.TargetAmountUSD, in.DeadlineDays, in.SecondsPerDay, deadline); err != nil {
		return nil, fmt.Errorf("insert capital_calls: %w", err)
	}
	for _, lp := range in.LPList {
		if _, err := tx.Exec(ctx, `
			INSERT INTO capital_call_lps (call_id, lp_id, email, commitment_usd, draw_amount_usd, status)
			VALUES ($1,$2,$3,$4,$5,'issued') ON CONFLICT (call_id, lp_id) DO NOTHING`,
			in.CallID, lp.LPID, lp.Email, lp.CommitmentUSD, lp.DrawAmountUSD); err != nil {
			return nil, fmt.Errorf("insert capital_call_lps: %w", err)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "issueCapitalCall", fmt.Sprintf("Call issued for $%.0f across %d LPs (deadline %d days)", in.TargetAmountUSD, len(in.LPList), in.DeadlineDays))

	// Follow-up schedule: D+3 email, D+7 call, D+10 legal notice, then deadline.
	final := (in.DeadlineDays - 10) * spd
	return map[string]any{
		"lpList":          in.LPList,
		"drawAmounts":     draws,
		"deadlineDays":    in.DeadlineDays,
		"waitStage1":      isoSeconds(3 * spd),
		"waitStage2":      isoSeconds(4 * spd),
		"waitStage3":      isoSeconds(3 * spd),
		"waitFinal":       isoSeconds(final),
		"callDeadline":    isoSeconds((in.DeadlineDays + 3) * spd),
		"deadlineAt":      deadline.UTC().Format(time.RFC3339),
		"bridgeTriggered": false,
		"bridgeResult":    nil,
		"highRiskLPs":     []any{},
		"gpDecisions":     []any{},
		"settlementMode":  "normal",
	}, nil
}

// ─── 2. notifyLPs ──────────────────────────────────────────────────────────

func (h *Handlers) NotifyLPs(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CapitalCallInput
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	for _, lp := range in.LPList {
		h.email(ctx, map[string]any{
			"to":      lp.Email,
			"subject": fmt.Sprintf("Capital Call %s — Action Required", in.CallID),
			"body": fmt.Sprintf("Dear %s, fund %s has issued capital call %s. Your pro-rata draw is $%.2f. Confirm via the secure portal: %s/?call=%s&lp=%s",
				lp.LPID, in.FundID, in.CallID, lp.DrawAmountUSD, h.PortalURL, in.CallID, lp.LPID),
			"callId": in.CallID, "lpId": lp.LPID, "type": "notification",
		})
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET status='notified', updated_at=NOW() WHERE call_id=$1 AND status='issued'`, in.CallID); err != nil {
		return nil, err
	}
	h.setCallStatus(ctx, in.CallID, "awaiting_lps")
	h.event(ctx, in.CallID, "", "notifyLPs", fmt.Sprintf("Batch notice sent to %d LPs via SES", len(in.LPList)))
	return nil, nil
}

// ─── child process: lp-response ────────────────────────────────────────────

type lpScope struct {
	CallID         string    `json:"callId"`
	LP             models.LP `json:"lp"`
	AmountUSD      *float64  `json:"amountUSD"`
	ResponseStatus string    `json:"responseStatus"`
	FollowUps      int       `json:"followUps"`
}

var stageNames = map[int]string{1: "email reminder (D+3)", 2: "phone call (D+7)", 3: "legal notice (D+10)"}

// AutoFollowUp sends a tiered escalation communication to an overdue LP.
func (h *Handlers) AutoFollowUp(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in lpScope
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	stage, _ := strconv.Atoi(job.Header("stage"))
	name := stageNames[stage]
	h.email(ctx, map[string]any{
		"to":      in.LP.Email,
		"subject": fmt.Sprintf("Capital Call %s — Follow-up %d: %s", in.CallID, stage, name),
		"body":    fmt.Sprintf("LP %s has not confirmed its $%.2f draw. Escalation stage %d (%s).", in.LP.LPID, in.LP.DrawAmountUSD, stage, name),
		"callId":  in.CallID, "lpId": in.LP.LPID, "stage": stage, "type": "followup",
	})
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET status='overdue', followup_stage=$3, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2`, in.CallID, in.LP.LPID, stage); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, in.LP.LPID, "autoFollowUp", "Sent "+name)
	return map[string]any{"followUps": stage}, nil
}

// RecordLPResponse finalises one LP: committed / deferred (from the
// lpCommitment message) or defaulted (deadline passed). Its output
// `lpResponse` is collected by the multi-instance call activity.
func (h *Handlers) RecordLPResponse(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in lpScope
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	status := job.Header("status")
	amount := 0.0
	if status == "committed" {
		if in.ResponseStatus == "deferred" {
			status = "deferred"
		} else {
			amount = in.LP.DrawAmountUSD
			if in.AmountUSD != nil && *in.AmountUSD > 0 {
				amount = *in.AmountUSD
			}
		}
	}
	if status == "" {
		status = "defaulted"
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET status=$3, committed_usd=$4, updated_at=NOW() WHERE call_id=$1 AND lp_id=$2`,
		in.CallID, in.LP.LPID, status, amount); err != nil {
		return nil, err
	}
	if err := h.refreshAggregates(ctx, in.CallID); err != nil {
		return nil, err
	}
	detail := fmt.Sprintf("LP %s -> %s ($%.0f)", in.LP.LPID, status, amount)
	if in.FollowUps > 0 {
		detail += fmt.Sprintf(" after %d follow-up(s)", in.FollowUps)
	}
	h.event(ctx, in.CallID, in.LP.LPID, "lpResponse", detail)
	return map[string]any{"lpResponse": models.LPResponse{
		LPID: in.LP.LPID, Email: in.LP.Email, CommitmentUSD: in.LP.CommitmentUSD, DrawAmountUSD: in.LP.DrawAmountUSD,
		Status: status, AmountUSD: amount, FollowUps: in.FollowUps,
	}}, nil
}

// FinalizeResponses runs when the response window is closed early (GP
// force-settlement message) or the hard deadline timer fires on the
// multi-instance: it rebuilds lpResponses from the fund ledger, defaulting
// every LP that has not committed yet.
func (h *Handlers) FinalizeResponses(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	reason := job.Header("reason")
	rows, err := h.DB.Query(ctx, `SELECT lp_id, COALESCE(email,''), commitment_usd::float8, draw_amount_usd::float8, committed_usd::float8, status, followup_stage FROM capital_call_lps WHERE call_id=$1 ORDER BY lp_id`, in.CallID)
	if err != nil {
		return nil, err
	}
	var out []models.LPResponse
	for rows.Next() {
		var r models.LPResponse
		if err := rows.Scan(&r.LPID, &r.Email, &r.CommitmentUSD, &r.DrawAmountUSD, &r.AmountUSD, &r.Status, &r.FollowUps); err != nil {
			rows.Close()
			return nil, err
		}
		if r.Status != "committed" && r.Status != "deferred" {
			r.Status, r.AmountUSD = "defaulted", 0
		}
		out = append(out, r)
	}
	rows.Close()
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET status='defaulted', committed_usd=0 WHERE call_id=$1 AND status NOT IN ('committed','deferred')`, in.CallID); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "finalizeResponses", "Response window closed early ("+reason+"); outstanding LPs marked defaulted")
	return map[string]any{"lpResponses": out, "settlementMode": reason}, nil
}

// ─── 5. aggregateLiquidity ─────────────────────────────────────────────────

func (h *Handlers) AggregateLiquidity(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	committed := 0.0
	for _, r := range in.LPResponses {
		if r.Status == "committed" {
			committed += r.AmountUSD
		}
	}
	gap := math.Max(0, in.TargetAmountUSD-committed)
	pct := 0.0
	if in.TargetAmountUSD > 0 {
		pct = round2(gap / in.TargetAmountUSD * 100)
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_calls SET status='aggregated', received_amount_usd=$2, gap_usd=$3, gap_percent=$4, updated_at=NOW() WHERE call_id=$1`,
		in.CallID, committed, gap, pct); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "aggregateLiquidity", fmt.Sprintf("Committed $%.0f of $%.0f — gap $%.0f (%.2f%%)", committed, in.TargetAmountUSD, gap, pct))
	return map[string]any{"totalCommitted": round2(committed), "gapUSD": round2(gap), "gapPercent": pct}, nil
}

// ─── 6. predictDefaultRisk ─────────────────────────────────────────────────

func (h *Handlers) PredictDefaultRisk(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	var scores []struct {
		LPID      string  `json:"lpId"`
		RiskScore float64 `json:"riskScore"`
	}
	if err := h.postJSON(ctx, h.MLScorerURL+"/score", map[string]any{"callId": in.CallID, "lpResponses": in.LPResponses}, &scores); err != nil {
		return nil, fmt.Errorf("ml-scorer: %w", err)
	}
	m := map[string]float64{}
	for _, s := range scores {
		m[s.LPID] = s.RiskScore
	}
	high := []models.LPResponse{}
	for i := range in.LPResponses {
		r := &in.LPResponses[i]
		r.RiskScore = m[r.LPID]
		if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET risk_score=$3 WHERE call_id=$1 AND lp_id=$2`, in.CallID, r.LPID, r.RiskScore); err != nil {
			return nil, err
		}
		if r.Status == "committed" && r.RiskScore > models.HighRiskThreshold {
			high = append(high, *r)
		}
	}
	h.setCallStatus(ctx, in.CallID, "risk_scored")
	h.event(ctx, in.CallID, "", "predictDefaultRisk", fmt.Sprintf("Scored %d LPs; %d committed LP(s) above %.1f risk threshold", len(in.LPResponses), len(high), models.HighRiskThreshold))
	return map[string]any{"lpResponses": in.LPResponses, "highRiskLPs": high}, nil
}

// ─── 6b. scoreLPPortfolio ──────────────────────────────────────────────────

func (h *Handlers) ScoreLPPortfolio(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	total := 0.0
	for _, r := range in.LPResponses {
		total += r.AmountUSD
	}
	hhi, sumRisk, el := 0.0, 0.0, 0.0
	for _, r := range in.LPResponses {
		if total > 0 {
			s := r.AmountUSD / total
			hhi += s * s
		}
		sumRisk += r.RiskScore
		el += r.AmountUSD * r.RiskScore
	}
	sorted := append([]models.LPResponse(nil), in.LPResponses...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].RiskScore > sorted[j].RiskScore })
	top := []string{}
	for i := 0; i < len(sorted) && i < 3; i++ {
		if sorted[i].RiskScore > 0.5 {
			top = append(top, sorted[i].LPID)
		}
	}
	pr := models.PortfolioRisk{
		ConcentrationScore: math.Round(hhi*10000) / 10000,
		TopRiskyLPs:        top,
		AverageRiskScore:   math.Round(sumRisk/float64(max(1, len(in.LPResponses)))*10000) / 10000,
		ExpectedLossUSD:    round2(el),
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_calls SET concentration_hhi=$2 WHERE call_id=$1`, in.CallID, pr.ConcentrationScore); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "scoreLPPortfolio", fmt.Sprintf("HHI %.4f, top risky %v", pr.ConcentrationScore, top))
	return map[string]any{"portfolioRisk": pr}, nil
}

// ─── 7. triggerBridge ──────────────────────────────────────────────────────

func (h *Handlers) TriggerBridge(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	var br models.BridgeResult
	if err := h.postJSON(ctx, h.CreditURL+"/drawdown", map[string]any{"callId": in.CallID, "amount": in.GapUSD}, &br); err != nil {
		return nil, fmt.Errorf("credit facility: %w", err)
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_calls SET bridge_triggered=TRUE, bridge_confirmation=$2, bridge_amount_usd=$3, bridge_fee_usd=$4 WHERE call_id=$1`,
		in.CallID, br.ConfirmationID, br.DrawdownUSD, br.FeeUSD); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "triggerBridge", fmt.Sprintf("Credit line drawdown $%.0f confirmed %s (fee $%.0f)", br.DrawdownUSD, br.ConfirmationID, br.FeeUSD))
	return map[string]any{"bridgeTriggered": true, "bridgeResult": br}, nil
}

// ─── 8. escalateToGP / gpApprovalGate ──────────────────────────────────────

type gpScope struct {
	CallID     string            `json:"callId"`
	HighRiskLP models.LPResponse `json:"highRiskLp"`
	GPAction   string            `json:"gpAction"`
	GPName     string            `json:"gpName"`
	GPComment  string            `json:"gpComment"`
}

func (h *Handlers) EscalateToGP(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in gpScope
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	lp := in.HighRiskLP
	h.email(ctx, map[string]any{
		"to":      "#gp-investment-committee (Slack)",
		"subject": fmt.Sprintf("[ESCALATION] High-risk LP %s on call %s", lp.LPID, in.CallID),
		"body": fmt.Sprintf("LP %s committed $%.2f but has default risk %.2f (> %.1f). Decide WAIVE or ENFORCE in Tasklist (%s/tasklist) or via POST /api/capital-calls/%s/gp-decision.",
			lp.LPID, lp.AmountUSD, lp.RiskScore, models.HighRiskThreshold, h.CamundaURL, in.CallID),
		"callId": in.CallID, "lpId": lp.LPID, "type": "escalation",
	})
	if _, err := h.DB.Exec(ctx, `INSERT INTO gp_escalations (call_id, lp_id, risk_score) VALUES ($1,$2,$3) ON CONFLICT (call_id, lp_id) DO NOTHING`, in.CallID, lp.LPID, lp.RiskScore); err != nil {
		return nil, err
	}
	h.setCallStatus(ctx, in.CallID, "awaiting_gp")
	h.event(ctx, in.CallID, lp.LPID, "escalateToGP", fmt.Sprintf("Escalated to GP (risk %.2f); awaiting approval gate", lp.RiskScore))
	return nil, nil
}

// ApplyGPDecision runs after the GP approval gate (Tasklist user task or the
// gpDecision message). "enforce" issues a compliance warning; the LP's
// commitment stays recorded either way.
func (h *Handlers) ApplyGPDecision(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in gpScope
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	if in.GPAction != "enforce" {
		in.GPAction = "waive"
	}
	if in.GPName == "" {
		in.GPName = "GP Admin"
	}
	lp := in.HighRiskLP
	if in.GPAction == "enforce" {
		h.email(ctx, map[string]any{
			"to":      lp.Email,
			"subject": fmt.Sprintf("[COMPLIANCE WARNING] Capital Call %s — Enforcement Notice", in.CallID),
			"body":    fmt.Sprintf("The General Partner (%s) has issued an enforcement notice on your $%.2f commitment. Your contribution remains recorded and is subject to compliance review.", in.GPName, lp.AmountUSD),
			"callId":  in.CallID, "lpId": lp.LPID, "type": "enforcement_warning",
		})
	}
	h.email(ctx, map[string]any{
		"to":      "#gp-investment-committee (Slack)",
		"subject": fmt.Sprintf("[GP DECISION] %s %s on call %s", in.GPName, in.GPAction, in.CallID),
		"body":    fmt.Sprintf("Decision for LP %s: %s. Comment: %s", lp.LPID, in.GPAction, in.GPComment),
		"callId":  in.CallID, "lpId": lp.LPID, "type": "gp_decision",
	})
	if _, err := h.DB.Exec(ctx, `UPDATE gp_escalations SET status='decided', action=$3, gp_name=$4, decided_at=NOW() WHERE call_id=$1 AND lp_id=$2`, in.CallID, lp.LPID, in.GPAction, in.GPName); err != nil {
		return nil, err
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET gp_action=$3 WHERE call_id=$1 AND lp_id=$2`, in.CallID, lp.LPID, in.GPAction); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, lp.LPID, "gpApprovalGate", fmt.Sprintf("GP %s decided: %s", in.GPName, in.GPAction))
	return map[string]any{"gpAction": in.GPAction, "gpName": in.GPName}, nil
}

// ─── 9. settleAndReconcile ─────────────────────────────────────────────────

func (h *Handlers) SettleAndReconcile(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	tx, err := h.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	// Idempotent on job retry: rebuild this call's ledger.
	if _, err := tx.Exec(ctx, `DELETE FROM ledger_entries WHERE call_id=$1`, in.CallID); err != nil {
		return nil, err
	}
	wires := 0
	for _, r := range in.LPResponses {
		if _, err := tx.Exec(ctx, `UPDATE capital_call_lps SET status=$3, committed_usd=$4 WHERE call_id=$1 AND lp_id=$2`, in.CallID, r.LPID, r.Status, r.AmountUSD); err != nil {
			return nil, err
		}
		if r.Status != "committed" || r.AmountUSD <= 0 {
			continue
		}
		wire := fmt.Sprintf("WIRE-%s-%s", in.CallID, r.LPID)
		// Double entry: debit fund cash (asset up), credit LP capital account (contributed capital).
		if _, err := tx.Exec(ctx, `INSERT INTO ledger_entries (call_id, lp_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
			VALUES ($1,$2,'fund/cash',$3,$4,'capital_contribution',$5)`, in.CallID, r.LPID, "lp/"+r.LPID+"/capital", r.AmountUSD, wire); err != nil {
			return nil, err
		}
		wires++
	}
	if in.BridgeResult != nil && in.BridgeResult.DrawdownUSD > 0 {
		if _, err := tx.Exec(ctx, `INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
			VALUES ($1,'fund/cash','liability/credit-facility',$2,'bridge_drawdown',$3)`, in.CallID, in.BridgeResult.DrawdownUSD, in.BridgeResult.ConfirmationID); err != nil {
			return nil, err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO ledger_entries (call_id, debit_account, credit_account, amount_usd, entry_type, wire_reference)
			VALUES ($1,'expense/financing-fees','fund/cash',$2,'bridge_fee',$3)`, in.CallID, in.BridgeResult.FeeUSD, in.BridgeResult.ConfirmationID); err != nil {
			return nil, err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE capital_calls SET status='settled', updated_at=NOW() WHERE call_id=$1`, in.CallID); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "settleAndReconcile", fmt.Sprintf("%d wire transfers booked; double-entry ledger reconciled", wires))
	return map[string]any{"wiresSettled": wires}, nil
}

// ─── 10. emitLiquidityReport ───────────────────────────────────────────────

func (h *Handlers) EmitLiquidityReport(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	jsonPath, pdfPath, err := h.writeReport(ctx, &in, string(job.ProcessInstanceKey))
	if err != nil {
		return nil, err
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_calls SET status='completed', report_path=$2, updated_at=NOW() WHERE call_id=$1`, in.CallID, "/reports/"+in.CallID+".pdf"); err != nil {
		return nil, err
	}
	h.event(ctx, in.CallID, "", "emitLiquidityReport", "Regulatory liquidity report emitted (PDF + JSON)")
	return map[string]any{"reportPdf": pdfPath, "reportJson": jsonPath}, nil
}

// ─── cancel path ───────────────────────────────────────────────────────────

func (h *Handlers) MarkCallCancelled(ctx context.Context, job *camunda.Job) (map[string]any, error) {
	var in models.CallState
	if err := job.Bind(&in); err != nil {
		return nil, err
	}
	if _, err := h.DB.Exec(ctx, `UPDATE capital_call_lps SET status='cancelled' WHERE call_id=$1`, in.CallID); err != nil {
		return nil, err
	}
	h.setCallStatus(ctx, in.CallID, "cancelled")
	for _, lp := range in.LPList {
		h.email(ctx, map[string]any{"to": lp.Email, "subject": fmt.Sprintf("Capital Call %s — Cancelled", in.CallID),
			"body": "The General Partner has cancelled this capital call. No action is required.", "callId": in.CallID, "lpId": lp.LPID, "type": "cancellation"})
	}
	h.event(ctx, in.CallID, "", "markCallCancelled", "Capital call cancelled by GP; LP response processes terminated")
	return nil, nil
}
