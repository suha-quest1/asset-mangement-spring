// Command apiserver exposes the capital-call REST/webhook endpoints (LP portal
// webhook, GP decision, force settlement, cancel) on top of Camunda 8, plus a
// read-only live dashboard backed by the fund ledger database.
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/vishworks/camunda-assetmgmt/internal/camunda"
	"github.com/vishworks/camunda-assetmgmt/internal/db"
	"github.com/vishworks/camunda-assetmgmt/internal/models"
)

//go:embed dashboard.html
var dashboardHTML []byte

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

type server struct {
	db        *pgxpool.Pool
	cam       *camunda.Client
	camPublic string
	sesPublic string
}

func main() {
	ctx := context.Background()
	pool, err := db.Connect(ctx, env("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	s := &server{
		db:        pool,
		cam:       camunda.New(env("CAMUNDA_REST_ADDRESS", "http://localhost:8080")),
		camPublic: env("CAMUNDA_PUBLIC_URL", "http://localhost:8080"),
		sesPublic: env("SES_PUBLIC_URL", "http://localhost:8081"),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(dashboardHTML)
	})
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, map[string]string{"status": "ok"}) })
	mux.HandleFunc("GET /api/config", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]string{"camundaUrl": s.camPublic, "sesUrl": s.sesPublic})
	})
	mux.Handle("GET /reports/", http.StripPrefix("/reports/", http.FileServer(http.Dir(env("REPORT_DIR", "./reports")))))

	mux.HandleFunc("GET /api/lps", s.listLPs)
	mux.HandleFunc("GET /api/capital-calls", s.listCalls)
	mux.HandleFunc("POST /api/capital-calls", s.startCall)
	mux.HandleFunc("GET /api/capital-calls/{callId}", s.getCall)
	mux.HandleFunc("POST /api/capital-calls/{callId}/lp-response", s.lpResponse)
	mux.HandleFunc("POST /api/capital-calls/{callId}/gp-decision", s.gpDecision)
	mux.HandleFunc("POST /api/capital-calls/{callId}/force-settlement", s.simpleMessage(models.MsgForceSettlement))
	mux.HandleFunc("POST /api/capital-calls/{callId}/cancel-call", s.simpleMessage(models.MsgCancelCall))

	addr := env("LISTEN_ADDR", ":8090")
	log.Printf("API server listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, logReq(mux)))
}

func logReq(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			log.Printf("%s %s", r.Method, r.URL.Path)
		}
		h.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func apiErr(w http.ResponseWriter, err error) {
	code := http.StatusBadGateway
	if ae, ok := err.(*camunda.APIError); ok && ae.Status < 500 {
		code = ae.Status
	}
	writeJSON(w, code, map[string]string{"error": err.Error()})
}

// ─── queries ────────────────────────────────────────────────────────────────

func rowsToMaps(ctx context.Context, pool *pgxpool.Pool, sql string, args ...any) ([]map[string]any, error) {
	rows, err := pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []map[string]any{}
	fields := rows.FieldDescriptions()
	for rows.Next() {
		vals, err := rows.Values()
		if err != nil {
			return nil, err
		}
		m := map[string]any{}
		for i, f := range fields {
			m[f.Name] = vals[i]
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *server) listLPs(w http.ResponseWriter, r *http.Request) {
	rows, err := rowsToMaps(r.Context(), s.db, `SELECT lp_id, name, commitment_usd::float8 AS commitment_usd, email, demo_behavior FROM lps ORDER BY lp_id`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, rows)
}

func (s *server) listCalls(w http.ResponseWriter, r *http.Request) {
	rows, err := rowsToMaps(r.Context(), s.db, `
		SELECT c.call_id, c.fund_id, c.process_instance_key, c.status, c.target_amount_usd::float8 AS target_amount_usd,
		       c.received_amount_usd::float8 AS received_amount_usd, c.gap_percent::float8 AS gap_percent, c.bridge_triggered,
		       c.report_path, c.created_at,
		       (SELECT COUNT(*) FROM capital_call_lps l WHERE l.call_id=c.call_id AND l.status='committed') AS committed_count,
		       (SELECT COUNT(*) FROM capital_call_lps l WHERE l.call_id=c.call_id) AS lp_count
		FROM capital_calls c ORDER BY c.created_at DESC LIMIT 50`)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, rows)
}

func (s *server) getCall(w http.ResponseWriter, r *http.Request) {
	ctx, id := r.Context(), r.PathValue("callId")
	call, err := rowsToMaps(ctx, s.db, `SELECT call_id, fund_id, process_instance_key, status, target_amount_usd::float8 AS target_amount_usd,
		received_amount_usd::float8 AS received_amount_usd, gap_usd::float8 AS gap_usd, gap_percent::float8 AS gap_percent, bridge_triggered,
		bridge_confirmation, bridge_amount_usd::float8 AS bridge_amount_usd, bridge_fee_usd::float8 AS bridge_fee_usd,
		concentration_hhi::float8 AS concentration_hhi, report_path, deadline_days, seconds_per_day, deadline_date, created_at
		FROM capital_calls WHERE call_id=$1`, id)
	if err != nil || len(call) == 0 {
		writeJSON(w, 404, map[string]string{"error": "capital call not found (it appears once issueCapitalCall has run)"})
		return
	}
	lps, _ := rowsToMaps(ctx, s.db, `SELECT l.lp_id, p.name, l.commitment_usd::float8 AS commitment_usd, l.draw_amount_usd::float8 AS draw_amount_usd,
		l.committed_usd::float8 AS committed_usd, l.status, l.followup_stage, l.risk_score::float8 AS risk_score, l.gp_action, p.demo_behavior
		FROM capital_call_lps l LEFT JOIN lps p ON p.lp_id=l.lp_id WHERE l.call_id=$1 ORDER BY l.lp_id`, id)
	esc, _ := rowsToMaps(ctx, s.db, `SELECT lp_id, risk_score::float8 AS risk_score, status, action, gp_name, created_at, decided_at FROM gp_escalations WHERE call_id=$1 ORDER BY lp_id`, id)
	ledger, _ := rowsToMaps(ctx, s.db, `SELECT lp_id, debit_account, credit_account, amount_usd::float8 AS amount_usd, entry_type, wire_reference FROM ledger_entries WHERE call_id=$1 ORDER BY id`, id)
	events, _ := rowsToMaps(ctx, s.db, `SELECT lp_id, step, detail, created_at FROM call_events WHERE call_id=$1 ORDER BY id`, id)
	writeJSON(w, 200, map[string]any{"call": call[0], "lps": lps, "escalations": esc, "ledger": ledger, "events": events})
}

// ─── commands ───────────────────────────────────────────────────────────────

type startReq struct {
	CallID          string      `json:"callId"`
	FundID          string      `json:"fundId"`
	TargetAmountUSD float64     `json:"targetAmountUSD"`
	DeadlineDays    int         `json:"deadlineDays"`
	SecondsPerDay   int         `json:"secondsPerDay"`
	LPIDs           []string    `json:"lpIds"`
	LPList          []models.LP `json:"lpList"`
}

func (s *server) startCall(w http.ResponseWriter, r *http.Request) {
	var req startReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, map[string]string{"error": "invalid JSON: " + err.Error()})
		return
	}
	if req.CallID == "" {
		req.CallID = fmt.Sprintf("CC-%d-%04d", time.Now().Year(), rand.Intn(9000)+1000)
	}
	if req.FundID == "" {
		req.FundID = "FUND-GROWTH-IV"
	}
	if req.TargetAmountUSD <= 0 {
		req.TargetAmountUSD = 50_000_000
	}
	if req.DeadlineDays <= 0 {
		req.DeadlineDays = 10
	}
	if len(req.LPList) == 0 {
		rows, err := s.db.Query(r.Context(), `SELECT lp_id, commitment_usd::float8, email FROM lps WHERE COALESCE(cardinality($1::text[]),0)=0 OR lp_id = ANY($1) ORDER BY lp_id`, req.LPIDs)
		if err != nil {
			writeJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		for rows.Next() {
			var lp models.LP
			rows.Scan(&lp.LPID, &lp.CommitmentUSD, &lp.Email)
			req.LPList = append(req.LPList, lp)
		}
		rows.Close()
	}
	if len(req.LPList) == 0 {
		writeJSON(w, 400, map[string]string{"error": "no LPs selected"})
		return
	}
	in := models.CapitalCallInput{CallID: req.CallID, FundID: req.FundID, TargetAmountUSD: req.TargetAmountUSD,
		LPList: req.LPList, DeadlineDays: req.DeadlineDays, SecondsPerDay: req.SecondsPerDay}
	res, err := s.cam.CreateProcessInstance(r.Context(), models.ProcessCapitalCall, in)
	if err != nil {
		apiErr(w, err)
		return
	}
	writeJSON(w, 201, map[string]any{
		"callId":             in.CallID,
		"processInstanceKey": res.ProcessInstanceKey,
		"operateUrl":         fmt.Sprintf("%s/operate/processes/%s", s.camPublic, res.ProcessInstanceKey),
		"lpCount":            len(in.LPList),
	})
}

// lpResponse is the LP-portal webhook. Body: {"lpId":"lp-01","amount":5000000}
// optional "status":"deferred". Published as a buffered message so an early
// response is not lost, and idempotent per (call, LP) via messageId.
func (s *server) lpResponse(w http.ResponseWriter, r *http.Request) {
	callID := r.PathValue("callId")
	var body struct {
		LPID      string   `json:"lpId"`
		Amount    *float64 `json:"amount"`
		AmountUSD *float64 `json:"amountUSD"`
		Status    string   `json:"status"`
		RunID     string   `json:"runId"` // accepted for API compatibility; unused with Camunda
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.LPID == "" {
		writeJSON(w, 400, map[string]string{"error": "lpId is required"})
		return
	}
	amount := body.Amount
	if amount == nil {
		amount = body.AmountUSD
	}
	status := "committed"
	if body.Status == "deferred" {
		status = "deferred"
	}
	vars := map[string]any{"responseStatus": status}
	if amount != nil {
		vars["amountUSD"] = *amount
	}
	key := callID + ":" + body.LPID
	if err := s.cam.PublishMessage(r.Context(), models.MsgLPCommitment, key, "lpCommitment-"+key, time.Hour, vars); err != nil {
		apiErr(w, err)
		return
	}
	writeJSON(w, 202, map[string]any{"message": models.MsgLPCommitment, "correlationKey": key, "variables": vars})
}

// gpDecision completes the GP approval gate from outside Tasklist (e.g. Slack
// button). Body: {"lpId":"lp-08","action":"waive"|"enforce","gpName":"..."}
func (s *server) gpDecision(w http.ResponseWriter, r *http.Request) {
	callID := r.PathValue("callId")
	var body struct {
		LPID    string `json:"lpId"`
		Action  string `json:"action"`
		GPName  string `json:"gpName"`
		Comment string `json:"comment"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.LPID == "" || (body.Action != "waive" && body.Action != "enforce") {
		writeJSON(w, 400, map[string]string{"error": `lpId and action ("waive"|"enforce") are required`})
		return
	}
	key := callID + ":" + body.LPID
	res, err := s.cam.CorrelateMessage(r.Context(), models.MsgGPDecision, key, map[string]any{
		"gpAction": body.Action, "gpName": body.GPName, "gpComment": body.Comment,
	})
	if err != nil {
		if camunda.IsNotFound(err) {
			writeJSON(w, 409, map[string]string{"error": "no GP approval gate is currently open for " + key})
			return
		}
		apiErr(w, err)
		return
	}
	writeJSON(w, 200, map[string]any{"correlated": true, "correlationKey": key, "result": res})
}

func (s *server) simpleMessage(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		callID := r.PathValue("callId")
		res, err := s.cam.CorrelateMessage(r.Context(), name, callID, map[string]any{})
		if err != nil {
			if camunda.IsNotFound(err) {
				writeJSON(w, 409, map[string]string{"error": "capital call " + callID + " is not in the LP response window"})
				return
			}
			apiErr(w, err)
			return
		}
		writeJSON(w, 200, map[string]any{"correlated": true, "message": name, "result": res})
	}
}
