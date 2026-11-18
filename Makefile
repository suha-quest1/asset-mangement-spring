.PHONY: up down clean status logs demo demo-auto deploy bpmn test

up:            ## build & start Camunda 8.10 + all services
	docker compose up -d --build
	@echo "Waiting for Camunda to become healthy..."
	@until [ "$$(docker inspect -f '{{.State.Health.Status}}' camunda-orchestration 2>/dev/null)" = "healthy" ]; do sleep 3; done
	@echo "Ready:  Dashboard http://localhost:8090 | Operate/Tasklist http://localhost:8080 (demo/demo) | Emails http://localhost:8081"

down:          ## stop everything (keeps data)
	docker compose down

clean:         ## stop everything and wipe Camunda + Postgres data
	docker compose down -v
	rm -f reports/*.json reports/*.pdf

status:
	docker compose ps

logs:          ## follow worker + api logs
	docker compose logs -f worker api-server

demo:          ## scripted demo; GP decision left to Tasklist
	go run ./cmd/demodriver

demo-auto:     ## scripted demo; driver auto-waives the GP gate
	go run ./cmd/demodriver -gp-decision waive

deploy:        ## redeploy BPMN + form without restarting
	curl -s -X POST http://localhost:8080/v2/deployments \
	  -F resources=@bpmn/capital-call.bpmn -F resources=@bpmn/lp-response.bpmn -F resources=@bpmn/gp-approval.form | python3 -m json.tool

bpmn:          ## regenerate BPMN files from tools/gen_bpmn.py
	python3 tools/gen_bpmn.py

test:
	go vet ./... && go build ./...
