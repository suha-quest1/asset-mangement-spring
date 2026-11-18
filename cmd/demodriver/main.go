// Command demodriver runs the scripted end-to-end demo against the API server:
//
//   - issues a capital call to the 10 seeded LPs (compressed time: N s = 1 day)
//   - lp-01..07 commit promptly on a staggered timer
//   - lp-08 commits but is high-risk  -> GP approval gate (Tasklist / API)
//   - lp-09 misses D+3, gets the email follow-up, then commits late ("deferred")
//   - lp-10 never responds -> email D+3, call D+7, legal notice D+10 -> default
//     -> liquidity gap > 10% -> bridge credit-line drawdown
//
// Flags let the presenter leave the GP decision to Tasklist (default) or make
// the driver take it via the gp-decision endpoint.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"
)

var api string

func call(method, path string, in any, out any) error {
	var body io.Reader
	if in != nil {
		b, _ := json.Marshal(in)
		body = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, api+path, body)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s %s: HTTP %d %s", method, path, resp.StatusCode, data)
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

type lpRow struct {
	LPID     string  `json:"lp_id"`
	Draw     float64 `json:"draw_amount_usd"`
	Status   string  `json:"status"`
	Behavior string  `json:"demo_behavior"`
}
type detail struct {
	Call struct {
		Status string `json:"status"`
		PIK    string `json:"process_instance_key"`
		Report string `json:"report_path"`
	} `json:"call"`
	LPs         []lpRow `json:"lps"`
	Escalations []struct {
		LPID   string `json:"lp_id"`
		Status string `json:"status"`
	} `json:"escalations"`
}

func main() {
	def := os.Getenv("API_URL")
	if def == "" {
		def = "http://localhost:8090"
	}
	flag.StringVar(&api, "api", def, "API server base URL")
	spd := flag.Int("seconds-per-day", 5, "demo time compression: seconds per business day")
	target := flag.Float64("target", 50_000_000, "capital call target (USD)")
	deadline := flag.Int("deadline-days", 10, "LP response deadline in days")
	gp := flag.String("gp-decision", "", `auto-answer the GP approval gate: "waive" or "enforce" (default: leave it for Tasklist)`)
	flag.Parse()

	var started struct {
		CallID     string `json:"callId"`
		PIK        string `json:"processInstanceKey"`
		OperateURL string `json:"operateUrl"`
	}
	if err := call("POST", "/api/capital-calls", map[string]any{
		"fundId": "FUND-GROWTH-IV", "targetAmountUSD": *target, "deadlineDays": *deadline, "secondsPerDay": *spd,
	}, &started); err != nil {
		log.Fatal(err)
	}
	log.Printf("▶ capital call %s started (process instance %s)", started.CallID, started.PIK)
	log.Printf("  Operate:   %s", started.OperateURL)
	log.Printf("  Dashboard: %s/?call=%s", api, started.CallID)

	var d detail
	for i := 0; ; i++ {
		if err := call("GET", "/api/capital-calls/"+started.CallID, nil, &d); err == nil && len(d.LPs) > 0 {
			break
		}
		if i > 60 {
			log.Fatal("capital call never appeared — is the worker running?")
		}
		time.Sleep(time.Second)
	}
	day := time.Duration(*spd) * time.Second
	commit := func(lp lpRow) {
		if err := call("POST", "/api/capital-calls/"+started.CallID+"/lp-response", map[string]any{"lpId": lp.LPID, "amount": lp.Draw}, nil); err != nil {
			log.Printf("  ✗ %s: %v", lp.LPID, err)
			return
		}
		log.Printf("  ✓ %s committed $%.0f", lp.LPID, lp.Draw)
	}

	start := time.Now()
	at := func(offset time.Duration, lp lpRow, note string) {
		go func() {
			time.Sleep(time.Until(start.Add(offset)))
			if note != "" {
				log.Printf("  … %s", note)
			}
			commit(lp)
		}()
	}
	k := 0
	for _, lp := range d.LPs {
		switch lp.Behavior {
		case "prompt":
			at(time.Duration(2+k)*time.Second, lp, "")
			k++
		case "highrisk":
			at(day*2, lp, lp.LPID+" (flagged high-risk by the ML model) responds")
		case "late":
			at(day*5, lp, lp.LPID+" responds late, after the D+3 email follow-up")
		case "silent":
			log.Printf("  • %s will stay silent → follow-ups at D+3/D+7/D+10, then default", lp.LPID)
		}
	}

	log.Printf("⏱  %d s per day: follow-ups at ~%ds/%ds/%ds, deadline at ~%ds", *spd, 3**spd, 7**spd, 10**spd, *deadline**spd)
	gpDone := map[string]bool{}
	last := ""
	for {
		time.Sleep(2 * time.Second)
		if err := call("GET", "/api/capital-calls/"+started.CallID, nil, &d); err != nil {
			continue
		}
		if d.Call.Status != last {
			log.Printf("● call status: %s", d.Call.Status)
			last = d.Call.Status
		}
		for _, e := range d.Escalations {
			if e.Status != "pending" || gpDone[e.LPID] {
				continue
			}
			gpDone[e.LPID] = true
			if *gp == "" {
				log.Printf("⚑ GP approval gate open for %s → complete it in Tasklist (http://localhost:8080/tasklist, demo/demo)", e.LPID)
				log.Printf("   or: curl -XPOST %s/api/capital-calls/%s/gp-decision -H 'Content-Type: application/json' -d '{\"lpId\":\"%s\",\"action\":\"waive\",\"gpName\":\"Jane Doe\"}'", api, started.CallID, e.LPID)
				continue
			}
			time.Sleep(3 * time.Second)
			if err := call("POST", "/api/capital-calls/"+started.CallID+"/gp-decision", map[string]any{"lpId": e.LPID, "action": *gp, "gpName": "Demo Driver GP"}, nil); err != nil {
				log.Printf("  ✗ gp decision: %v", err)
			} else {
				log.Printf("⚑ GP decision for %s: %s", e.LPID, *gp)
			}
		}
		if d.Call.Status == "completed" || d.Call.Status == "cancelled" {
			log.Printf("■ done in %s. Report: %s/reports/%s.pdf", time.Since(start).Round(time.Second), api, started.CallID)
			return
		}
	}
}
