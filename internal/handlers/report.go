package handlers

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/vishworks/camunda-assetmgmt/internal/models"
	"github.com/vishworks/camunda-assetmgmt/internal/report"
)

func usd(f float64) string {
	s := fmt.Sprintf("%.2f", f)
	// thousands separators
	intPart, dec := s[:len(s)-3], s[len(s)-3:]
	neg := ""
	if len(intPart) > 0 && intPart[0] == '-' {
		neg, intPart = "-", intPart[1:]
	}
	out := ""
	for i, c := range intPart {
		if i > 0 && (len(intPart)-i)%3 == 0 {
			out += ","
		}
		out += string(c)
	}
	return neg + "$" + out + dec
}

func (h *Handlers) writeReport(ctx context.Context, in *models.CallState, pik string) (string, string, error) {
	type ledgerRow struct {
		LPID   string  `json:"lpId,omitempty"`
		Debit  string  `json:"debit"`
		Credit string  `json:"credit"`
		Amount float64 `json:"amountUSD"`
		Type   string  `json:"entryType"`
		Ref    string  `json:"wireReference"`
	}
	var ledger []ledgerRow
	rows, err := h.DB.Query(ctx, `SELECT COALESCE(lp_id,''), debit_account, credit_account, amount_usd::float8, entry_type, COALESCE(wire_reference,'') FROM ledger_entries WHERE call_id=$1 ORDER BY id`, in.CallID)
	if err != nil {
		return "", "", err
	}
	for rows.Next() {
		var r ledgerRow
		if err := rows.Scan(&r.LPID, &r.Debit, &r.Credit, &r.Amount, &r.Type, &r.Ref); err != nil {
			rows.Close()
			return "", "", err
		}
		ledger = append(ledger, r)
	}
	rows.Close()

	now := time.Now().UTC()
	doc := map[string]any{
		"reportType":         "LiquidityReport",
		"generatedAt":        now.Format(time.RFC3339),
		"orchestrator":       "Camunda 8",
		"processInstanceKey": pik,
		"callId":             in.CallID,
		"fundId":             in.FundID,
		"settlementMode":     in.SettlementMode,
		"summary": map[string]any{
			"targetAmountUSD": in.TargetAmountUSD,
			"totalCommitted":  in.TotalCommitted,
			"gapUSD":          in.GapUSD,
			"gapPercent":      in.GapPercent,
			"bridgeTriggered": in.BridgeTriggered,
		},
		"bridgeResult":  in.BridgeResult,
		"portfolioRisk": in.PortfolioRisk,
		"lpDetails":     in.LPResponses,
		"gpDecisions":   in.GPDecisions,
		"ledger":        ledger,
	}
	if err := os.MkdirAll(h.ReportDir, 0o755); err != nil {
		return "", "", err
	}
	jsonPath := filepath.Join(h.ReportDir, in.CallID+".json")
	b, _ := json.MarshalIndent(doc, "", "  ")
	if err := os.WriteFile(jsonPath, b, 0o644); err != nil {
		return "", "", err
	}

	p := &report.PDF{}
	p.Title("Capital Call Liquidity Report")
	p.Text(fmt.Sprintf("Call %s   |   Fund %s   |   Generated %s", in.CallID, in.FundID, now.Format("2006-01-02 15:04 MST")))
	p.Text(fmt.Sprintf("Orchestrated by Camunda 8 - process instance %s   |   settlement mode: %s", pik, in.SettlementMode))
	p.Heading("1. Liquidity summary")
	p.Mono(fmt.Sprintf("%-28s %20s", "Target capital call", usd(in.TargetAmountUSD)))
	p.Mono(fmt.Sprintf("%-28s %20s", "LP capital committed", usd(in.TotalCommitted)))
	p.Mono(fmt.Sprintf("%-28s %20s  (%.2f%%)", "Liquidity gap", usd(in.GapUSD), in.GapPercent))
	if in.BridgeResult != nil {
		p.Mono(fmt.Sprintf("%-28s %20s  fee %s", "Bridge drawdown", usd(in.BridgeResult.DrawdownUSD), usd(in.BridgeResult.FeeUSD)))
		p.Mono(fmt.Sprintf("%-28s %s", "Facility confirmation", in.BridgeResult.ConfirmationID))
	} else {
		p.Mono(fmt.Sprintf("%-28s %s", "Bridge drawdown", "not required (gap <= 10%)"))
	}
	p.Heading("2. LP responses and default-risk scores")
	p.Mono(fmt.Sprintf("%-7s %-10s %16s %16s %6s %9s", "LP", "Status", "Draw", "Funded", "Risk", "FollowUps"))
	for _, r := range in.LPResponses {
		p.Mono(fmt.Sprintf("%-7s %-10s %16s %16s %6.2f %9d", r.LPID, r.Status, usd(r.DrawAmountUSD), usd(r.AmountUSD), r.RiskScore, r.FollowUps))
	}
	if in.PortfolioRisk != nil {
		p.Heading("3. Portfolio risk")
		p.Mono(fmt.Sprintf("Concentration (HHI): %.4f   Avg risk: %.4f   Expected loss: %s", in.PortfolioRisk.ConcentrationScore, in.PortfolioRisk.AverageRiskScore, usd(in.PortfolioRisk.ExpectedLossUSD)))
		p.Mono(fmt.Sprintf("Top risky LPs: %v", in.PortfolioRisk.TopRiskyLPs))
	}
	p.Heading("4. GP escalations (human-in-the-loop)")
	if len(in.GPDecisions) == 0 {
		p.Text("No LP exceeded the 0.70 risk threshold.")
	}
	for _, d := range in.GPDecisions {
		p.Mono(fmt.Sprintf("%-7s risk %.2f -> %-8s by %s", d.LPID, d.RiskScore, d.Action, d.GPName))
	}
	p.Heading("5. Settlement ledger (double entry)")
	p.Mono(fmt.Sprintf("%-22s %-28s %16s  %s", "Debit", "Credit", "Amount", "Reference"))
	for _, l := range ledger {
		p.Mono(fmt.Sprintf("%-22s %-28s %16s  %s", l.Debit, l.Credit, usd(l.Amount), l.Ref))
	}
	p.Heading("Attestation")
	p.Text("This report was generated automatically at the end of the capital call process. Every step (notice,")
	p.Text("follow-ups, risk scoring, bridge financing, GP decisions, settlement) is auditable in Camunda Operate.")

	pdfPath := filepath.Join(h.ReportDir, in.CallID+".pdf")
	if err := os.WriteFile(pdfPath, p.Bytes(), 0o644); err != nil {
		return "", "", err
	}
	return "/reports/" + in.CallID + ".json", "/reports/" + in.CallID + ".pdf", nil
}
