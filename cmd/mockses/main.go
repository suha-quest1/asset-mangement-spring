// Command mockses is a stand-in for AWS SES / Slack: it accepts POST /send and
// shows every message in an auto-refreshing log viewer.
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"
)

type entry struct {
	Timestamp string         `json:"timestamp"`
	Payload   map[string]any `json:"payload"`
}

var (
	mu     sync.Mutex
	emails []entry
)

const page = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Mock SES Log</title>
<style>body{font:13px -apple-system,Segoe UI,sans-serif;margin:0;background:#f5f6f8;color:#1d2433}h1{font-size:16px;margin:0;padding:14px 20px;background:#10141f;color:#fff}
.wrap{padding:16px;overflow-x:auto}table{border-collapse:collapse;width:100%;background:#fff}td,th{border-bottom:1px solid #e4e7ec;padding:7px 9px;text-align:left;vertical-align:top}
th{color:#667085;font-size:12px}.t{display:inline-block;padding:1px 8px;border-radius:99px;font-size:11px;font-weight:600;background:#eff8ff;color:#175cd3}
.t.followup{background:#fffaeb;color:#b54708}.t.escalation,.t.enforcement_warning{background:#fef3f2;color:#b42318}.t.gp_decision{background:#ecfdf3;color:#12805c}
select{margin-left:12px}</style></head><body><h1>Mock SES / Slack — outbound communications <span id="c" style="font-weight:400;color:#aab1c0"></span></h1>
<div class="wrap">Filter call: <select id="f"><option value="">all</option></select>
<table><thead><tr><th>Time</th><th>Type</th><th>To</th><th>Subject</th><th>Body</th></tr></thead><tbody id="log"></tbody></table></div>
<script>const esc=s=>String(s??'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function load(){const d=await (await fetch('/emails')).json();const f=document.getElementById('f'),cur=f.value;
const calls=[...new Set(d.map(e=>e.payload.callId).filter(Boolean))];f.innerHTML='<option value="">all</option>'+calls.map(c=>'<option'+(c===cur?' selected':'')+'>'+esc(c)+'</option>').join('');
const rows=d.filter(e=>!cur||e.payload.callId===cur).reverse();document.getElementById('c').textContent='('+rows.length+')';
document.getElementById('log').innerHTML=rows.map(e=>'<tr><td>'+new Date(e.timestamp).toLocaleTimeString()+'</td><td><span class="t '+esc(e.payload.type)+'">'+esc(e.payload.type||'notification')+'</span></td><td>'+esc(e.payload.to)+'</td><td>'+esc(e.payload.subject)+'</td><td>'+esc(e.payload.body)+'</td></tr>').join('');}
document.getElementById('f').onchange=load;setInterval(load,2000);load();</script></body></html>`

func main() {
	addr := ":8081"
	if v := os.Getenv("LISTEN_ADDR"); v != "" {
		addr = v
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /send", func(w http.ResponseWriter, r *http.Request) {
		var p map[string]any
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		mu.Lock()
		emails = append(emails, entry{Timestamp: time.Now().Format(time.RFC3339Nano), Payload: p})
		mu.Unlock()
		log.Printf("[MOCK-SES] %-20v to=%v subject=%v", p["type"], p["to"], p["subject"])
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "sent"})
	})
	mux.HandleFunc("GET /emails", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if emails == nil {
			w.Write([]byte("[]"))
			return
		}
		json.NewEncoder(w).Encode(emails)
	})
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(page))
	})
	log.Printf("Mock SES listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
