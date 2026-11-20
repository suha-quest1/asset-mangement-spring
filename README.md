# Asset Management: Intelligent Capital Call & Liquidity Orchestration on Camunda 8 + Java

A Camunda 8.10 (self-managed, open source) implementation of `use_case.pdf`, ported from `../temporal-asset-management`.

```bash
make up      # start Camunda 8.10.9 + Postgres + mocks + Java worker/API
make demo    # run the scripted 10-LP capital call (GP decision in Tasklist)
make down    # stop  (make clean = stop + wipe data)
make test    # run the unit tests (JDK 21 + Maven)
```

* Dashboard: http://localhost:8090
* Operate / Tasklist: http://localhost:8080 (demo/demo)
* Email log: http://localhost:8081

See **[demo_camunda_assetmgmt.md](demo_camunda_assetmgmt.md)** for the architecture mapping, start/stop instructions and the full demo walkthrough.

The backend was migrated from Go to Java; see **[GO_TO_JAVA_MIGRATION_AUDIT.md](GO_TO_JAVA_MIGRATION_AUDIT.md)**.

## Camunda integration (Spring Boot)

The `worker` and `apiserver` roles are Spring Boot applications built on the **Camunda Spring Boot Starter** (`io.camunda:camunda-spring-boot-starter` 8.10.9, Spring Boot 4.1.2):

* **Job workers:** one method per BPMN job type, annotated with the starter's job-worker annotation (`io.camunda.client.annotation.JobWorker`) on two `@Component` beans: `worker/CapitalCallWorkers` (12 job types of `capital-call.bpmn`) and `worker/LpResponseWorkers` (2 job types of `lp-response.bpmn`). Each method reads the variables, delegates to `service/CapitalCallService` and returns the variables to complete the job with.
* **Job types:** `camunda/ProcessConstants` holds the 14 job types as `static final String` constants; a unit test checks that they match the BPMN service tasks and the annotated methods.
* **Configuration:** `src/main/resources/application.yaml` (REST address from `CAMUNDA_REST_ADDRESS`, worker defaults: 16 jobs, 60 s lock, 20 s long poll, 5 s retry back-off, worker name from `WORKER_NAME`). Basic auth is used when `CAMUNDA_USERNAME` is set. `camunda/application.yaml` is the cluster's own configuration and is unrelated.
* **BPMN:** the models stay in `bpmn/` and are deployed by the worker on start-up (`camunda/BpmnDeployer`, `BPMN_DIR`, `DEPLOY_ON_START`).
* The mock services and the demo driver are plain Java programs (no Spring).
