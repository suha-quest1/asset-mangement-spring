// Command worker deploys the BPMN/form resources to Camunda and runs the job
// workers that implement every service task of the capital-call processes.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/vishworks/camunda-assetmgmt/internal/camunda"
	"github.com/vishworks/camunda-assetmgmt/internal/db"
	"github.com/vishworks/camunda-assetmgmt/internal/handlers"
)

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client := camunda.New(env("CAMUNDA_REST_ADDRESS", "http://localhost:8080"))
	log.Printf("waiting for Camunda at %s ...", client.BaseURL)
	if err := client.WaitReady(ctx); err != nil {
		log.Fatal(err)
	}

	// Deploy process models + form (Camunda de-duplicates unchanged resources).
	if env("DEPLOY_ON_START", "true") == "true" {
		dir := env("BPMN_DIR", "./bpmn")
		files, _ := filepath.Glob(filepath.Join(dir, "*.bpmn"))
		forms, _ := filepath.Glob(filepath.Join(dir, "*.form"))
		files = append(files, forms...)
		for attempt := 1; ; attempt++ {
			res, err := client.Deploy(ctx, files...)
			if err == nil {
				log.Printf("deployed %v (deploymentKey=%v)", files, res["deploymentKey"])
				break
			}
			if attempt >= 20 {
				log.Fatalf("deploy failed: %v", err)
			}
			log.Printf("deploy attempt %d failed: %v", attempt, err)
			time.Sleep(3 * time.Second)
		}
	}

	pool, err := db.Connect(ctx, env("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable"))
	if err != nil {
		log.Fatal(err)
	}
	defer pool.Close()

	h := &handlers.Handlers{
		DB:          pool,
		SESURL:      env("SES_URL", "http://localhost:8081"),
		MLScorerURL: env("ML_SCORER_URL", "http://localhost:5001"),
		CreditURL:   env("CREDIT_FACILITY_URL", "http://localhost:8082"),
		ReportDir:   env("REPORT_DIR", "./reports"),
		PortalURL:   env("PORTAL_PUBLIC_URL", "http://localhost:8090"),
		CamundaURL:  env("CAMUNDA_PUBLIC_URL", "http://localhost:8080"),
		HTTP:        &http.Client{Timeout: 20 * time.Second},
	}
	w := camunda.NewWorker(client, env("WORKER_NAME", "capital-call-go-worker"))
	h.Register(w)
	log.Printf("job workers started")
	w.Run(ctx)
}
