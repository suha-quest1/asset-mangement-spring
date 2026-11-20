# Migration audit: Camunda Java client (Folder 1) → Camunda Spring Boot Starter (Folder 2)

| | |
|---|---|
| Folder 1 (original) | `/Users/suhan/Desktop/repos/asst-mgmt/cmn-assetmgmt`. Plain Java 21, `io.camunda:camunda-client-java` 8.10.9, hand-written worker registration |
| Folder 2 (migrated) | `/Users/suhan/Desktop/repos/asst-mgmt/cmn-springboot-asstmgt`. Spring Boot 4.1.2, `io.camunda:camunda-spring-boot-starter` 8.10.9, `@JobWorker` methods |
| Date | 2026-11-20 |
| Codebases modified by this audit | None. Both folders were only read; all builds and runs used copies in a sandbox |

> **Independence note.** The migration in Folder 2 was carried out in an earlier step of this same session. This audit re-checks it from scratch: identity of both trees, a fresh comparison of every file, and new runtime tests that were not part of the migration's own verification. Treat it as a self-audit.

## Evidence labels

* **Verified**: shown by running both builds (same Camunda 8.10.9 cluster, same Postgres 16, same inputs) or by a byte-level comparison.
* **Code-reviewed**: established by reading the code of both folders (and, for the starter, its 8.10.9 source jar). Not exercised at runtime.
* **Unverified**: not checked; the reason is given.

---

## 1. Executive summary

**Verdict: Folder 2 preserves all functionality of Folder 1.** Every API route, all 14 job types, BPMN deployment, Camunda messaging, failure/incident handling, reporting, the mock services and the demo driver are present and behave the same in every scenario that was run. No function is missing.

The following **behavioural differences** exist. Each is minor, and each is backed by evidence (details in §9):

| ID | Difference | Severity | Evidence |
|---|---|---|---|
| D1 | A `SIGTERM` sent while the worker or API server waits for Postgres at start-up is not honoured until the wait ends (up to ~2 min); Folder 1 exits at once | **Medium** (operational) | Verified |
| D2 | Start-up order changed: Postgres is connected and the job workers start polling *before* Camunda is reachable and the BPMN is deployed | Low | Verified |
| D3 | While Camunda is unreachable at start-up, Folder 2 logs one warning per job type per poll (84 lines in 15 s); Folder 1 logs only "waiting for Camunda" | Low (log noise) | Verified |
| D4 | The per-job `[worker] completed …` log line is gone; job completion is sent asynchronously by the starter | Low | Verified (log), Code-reviewed (async) |
| D5 | `CAMUNDA_USERNAME` set with an empty `CAMUNDA_PASSWORD`: Folder 1 crashes at start-up, Folder 2 sends `Basic user:` (the original Go behaviour) | Intentional fix | Verified |
| D6 | Third-party library versions under the Camunda client changed (gRPC, Netty, Guava, Gson, SnakeYAML), plus Spring Boot on the classpath | Low | Verified |
| D7 | Higher footprint: worker RSS +22 MB, metaspace +13 MB, jar 34 → 43 MB, 2-5 s Spring start-up per role | Low | Verified |
| D8 | Spring's relaxed binding means environment variables named `CAMUNDA_CLIENT_*` can now change client settings | Low | Code-reviewed |
| D9 | Cosmetic log differences: Spring start-up lines, and job types are listed in a different order | Cosmetic | Verified |

Not verified, because Docker Hub is blocked in the sandbox: the Docker image build and `make up`, and clicking through the UI (Tasklist and dashboard).

---

## 2. Method and baseline identity

**Folder identity (Verified).**

* Folder 1's tracked files are byte-identical to commit `c60c9b3` ("Go to Java backend change"), and its working tree is clean apart from the untracked `GO_TO_JAVA_MIGRATION_AUDIT.md`. The MD5 of all tracked sources is `cf99c223…` in both Folder 1 and the sandbox copy of `c60c9b3`.
* Folder 2 is the same commit `c60c9b3` plus uncommitted changes. All 74 files under `pom.xml`, `src/`, `bpmn/`, the docs and the build files hash to `d8d341f3…` in both the device folder and the tree that was built and tested.
* So the "Folder 1 build" below is exactly Folder 1, and the "Folder 2 build" is exactly Folder 2.

**Runtime set-up.**

* Both jars ran against the same Camunda 8.10.9 orchestration cluster (H2), Postgres 16 with `db/schema.sql` + `db/seed.sql` re-applied before every run, the mock SES and mock credit facility from each build, and the Flask ML scorer from `services/ml-scorer`.
* The scorer ran with `PYTHONHASHSEED=0`. Its risk score for defaulted LPs uses Python's randomised `hash()`, which otherwise varies between runs regardless of the build.

**Comparisons used.**

| Comparison | What was compared |
|---|---|
| Unit tests | `mvn -B test` on each tree |
| Static analysis | The Shinro code analyser on each tree |
| Process variables | The variables of every process instance, read from the engine (`/v2/variables/search`) |
| Database | The DB rows of each scenario |
| Reports | The JSON and PDF reports |
| API | A 32-request API replay |
| Demo | A full 10-LP demo driver run |
| Failure | ML scorer down |
| Start-up and shutdown | Postgres down at start-up, Camunda down at start-up, `SIGTERM` timing |
| Configuration | Environment-variable options and credentials on the wire |

---

## 3. File inventory (Verified: full-tree hash comparison)

| Category | Count | Files |
|---|---|---|
| Identical in both folders | 50 | All of `bpmn/`, `db/`, `services/`, `tools/`, `camunda/application.yaml`, `Dockerfile`, `Makefile`, `docker-compose.yml`, `.gitignore`, `.dockerignore`. Source unchanged: `cmd/DemoDriverMain`, `cmd/MockSesMain`, `cmd/MockCreditFacilityMain`, `db/Db`, `http/Router`, `http/FileServer`, `http/Http`, `json/GoJson`, `report/Pdf`, all 8 `models/*`, `util/GoFmt`, `util/Env`, `util/Log`, `util/Errors`, all 3 resources, golden files. Tests unchanged: `GoJsonTest`, `GoFmtTest`, `RouterTest`, `DbTest`, `MockServicesTest`, `ReportWriterGoldenTest` |
| Modified | 8 | `pom.xml`, `Main.java`, `camunda/Camunda.java`, `cmd/ApiServerMain.java`, `handlers/ReportWriter.java`, `handlers/HandlersTest.java`, `README.md`, `demo_camunda_assetmgmt.md` |
| Removed | 4 | `camunda/JobWorkers.java`, `cmd/WorkerMain.java`, `handlers/Handlers.java`, test `camunda/JobWorkersTest.java` |
| Added | 29 | `api/` (2), `camunda/ProcessConstants`, `camunda/BpmnDeployer`, `config/` (3), `models/LpScope`, `models/GpScope`, `service/` (7), `util/Lists`, `worker/` (6), `src/main/resources/application.yaml`, 5 test classes |

Since 50 of the 62 tracked source/config files are byte-identical, the audit concentrates on the 12 changed or removed files and the 29 additions.

---

## 4. 1:1 component mapping

Mapping types:

* **Same**: byte-identical.
* **Moved**: code relocated without logic changes.
* **Replaced**: a hand-written mechanism replaced by the starter.
* **Adapted**: same behaviour, different implementation.
* **New**: no counterpart in Folder 1.

### 4.1 Worker bootstrap (`cmd/WorkerMain.java`, removed)

| Folder 1 (file:line) | Responsibility | Folder 2 | Type | Parity |
|---|---|---|---|---|
| `WorkerMain.main` 28 | Entry for `worker` role | `Main.runSpring(WorkerApplication)` + `worker/WorkerApplication` (scans `worker, service, handlers, camunda, config`) | Replaced | Verified |
| `Camunda.newClient` call 30 | Build client | Starter-created `CamundaClient` bean (`application.yaml`) | Replaced | Verified |
| `Camunda.waitReady` 32 | Wait for topology, 3 s | `camunda/BpmnDeployer.waitReady` (same loop) | Moved | Verified (logs "waiting for Camunda at …") |
| deploy loop 35-52 (`DEPLOY_ON_START`, `BPMN_DIR`, `*.bpmn`+`*.form`, 20×3 s) | Deploy BPMN/forms | `camunda/BpmnDeployer.run/deploy/glob` (same code) | Moved | Verified (same "deployed [...]" line; `DEPLOY_ON_START=false` honoured by both) |
| `Db.connect` 57 | Postgres pool, 40×3 s | `config/CommonConfiguration.db` bean, same `Db.connect` | Adapted | Verified; **order changed (D2) and SIGTERM during the wait is delayed (D1)** |
| `new Handlers()` + public field wiring 63-71 | Dependencies | Constructor injection; `config/AppProperties` reads the same env vars and defaults | Adapted | Verified |
| `HttpClient` 20 s connect 71 | Outbound HTTP | `worker/WorkerConfiguration.httpClient` | Moved | Code-reviewed |
| `new JobWorkers(client, WORKER_NAME)` 73 | Worker name | `camunda.client.worker.defaults.name: ${WORKER_NAME:capital-call-java-worker}` | Replaced | Verified (jobs show `worker=audit-worker-…` when set, default otherwise) |
| `h.register(w); w.start()` 74-75 | Registration | Starter scans the job-worker annotations | Replaced | Verified (14 workers) |
| "subscribed to job type" / "job workers started" logs | Start-up log | `worker/WorkerStartupLog` | Adapted | Verified (order differs, D9) |
| shutdown hook 79 (close workers, client, pool) | Shutdown | Spring context close (`Db` destroyMethod, starter closes workers/client) | Replaced | Verified for a healthy worker (exit in 1.2 s vs 1.5 s); **D1** during the DB wait |

### 4.2 Job worker runtime (`camunda/JobWorkers.java`, removed)

| Folder 1 | Responsibility | Folder 2 | Type | Parity |
|---|---|---|---|---|
| `handlers` map + `handle()` + `start()` loop 120-138 | Dispatch map and registration loop | Removed; one `@JobWorker(type = ProcessConstants.JOB_…)` method per type | Replaced | Verified (analyser: 14 annotation workers, 0 builder workers) |
| `.timeout(60 s)` | Job lock | `worker.defaults.timeout: PT60S` | Replaced | Verified (job deadline = creation + 60 s on both) |
| `.maxJobsActive(16)` 129 | Concurrency | `worker.defaults.max-jobs-active: 16` | Replaced | Code-reviewed |
| `.requestTimeout(20 s)` 130 | Long poll | `worker.defaults.request-timeout: PT20S` | Replaced | Code-reviewed |
| `.pollInterval(200 ms)` 131 | Idle pause | `worker.defaults.poll-interval: PT0.2S` | Replaced | Code-reviewed |
| `.backoffSupplier(2000 ms)` 132 | Pause after a failed poll | `WorkerConfiguration.backoffSupplier` bean | Adapted | Code-reviewed (D3 shows polls repeating) |
| `.streamEnabled(false)` 133 | No streaming | `worker.defaults.stream-enabled: false` | Replaced | Code-reviewed |
| `jobHandlingExecutor(virtual threads)` (`Camunda.java` 53) | One virtual thread per job | `WorkerConfiguration.camundaClientExecutorService` (the starter defaults to **1** thread) | Adapted | Code-reviewed |
| `execute` failure branch 156-170: `max(0, retries-1)`, 5 s back-off, message, log | Fail job | `WorkerConfiguration.jobExceptionHandlerSupplier` (same code) + `retry-backoff: PT5S` | Adapted | **Verified**: scorer-down gives 3 attempts 5 s apart, the same message, `JOB_NO_RETRIES`, the same log lines |
| `errorText` 188 (`panic:` prefix) | Error text | `WorkerConfiguration.errorText` (also unwraps the starter's wrapping of `Error`) | Moved | Verified (unit test + run) |
| `execute` complete branch 175-182: `newCompleteCommand(...).variables(GoJson.marshal(vars)).send().join()`, log line | Complete job | Starter auto-complete with the returned `Map`; serialised via the `camundaJsonMapper` bean built on `GoJson.MAPPER` | Replaced | **Verified**: 0 variable differences; **D4** (no log line, async) |
| `Job` facade (`bind`, `header`, `processInstanceKey`) 55-99 | Job helpers | `job.getVariablesAsType`, `worker/JobValues.header/processInstanceKey` | Adapted | Verified |
| `JobException` | "Returned error" type | `service/ServiceException` | Adapted | Verified |

### 4.3 The 14 job types (`handlers/Handlers.java`, removed)

Every handler body moved verbatim into `service/CapitalCallService`. **Code-reviewed:** all 168 string literals of `Handlers.java` (SQL, messages, formats, JSON names) are present in Folder 2. The worker method only reads the variables, calls the service and puts the result variables. **Verified:** process variables, DB rows and reports are identical.

| Job type (BPMN) | Folder 1 `Handlers.java` | Folder 2 worker method | Folder 2 service method |
|---|---|---|---|
| `issueCapitalCall` | `issueCapitalCall` 195 | `CapitalCallWorkers.issueCapitalCall` 38 | `CapitalCallService.issueCapitalCall` 77 |
| `notifyLPs` | 280 | `notifyLPs` 63 (void) | 150 |
| `autoFollowUp` (header `stage`) | 312 | `LpResponseWorkers.autoFollowUp` 24 | 171 |
| `recordLPResponse` (header `status`) | 340 | `LpResponseWorkers.recordLPResponse` 35 | 187 |
| `finalizeResponses` (header `reason`) | 381 | `finalizeResponses` 70 | 226 |
| `aggregateLiquidity` | 410 | 82 | 259 |
| `predictDefaultRisk` | 441 | 93 | 284 (via `RiskScorerClient.score`) |
| `scoreLPPortfolio` | 471 | 103 | 307 |
| `triggerBridge` | 508 | 112 | 343 (via `CreditFacilityClient.drawdown`) |
| `escalateToGP` | 536 | 122 (void) | 356 |
| `applyGPDecision` | 556 | 128 | 375 |
| `settleAndReconcile` | 586 | 138 | 403 |
| `emitLiquidityReport` | 633 | 147 | 450 (via `ReportWriter.writeReport`) |
| `markCallCancelled` | 645 | 157 (void) | 460 |

Helpers:

| Folder 1 helper | Folder 2 location |
|---|---|
| `event`, `setCallStatus`, `refreshAggregates` | `service/CallRepository` |
| `postJSON`, `netReason`, `JobErrorIO` | `service/HttpJson` |
| `email`, `m()` | `service/NotificationClient` |
| `Score` | `RiskScorerClient.Score` |
| `isoSeconds`, `atoi` | `worker/JobValues` |
| `nz` | `util/Lists` |
| `LpScope`, `GpScope` (nested classes) | `models/` |
| `STAGE_NAMES` | `CapitalCallService` |

### 4.4 Camunda client configuration (`camunda/Camunda.java`)

| Folder 1 `Camunda.newClient` 42-66 | Folder 2 | Parity |
|---|---|---|
| `restAddress(CAMUNDA_REST_ADDRESS)`, trailing `/` trimmed | `camunda.client.rest-address: ${CAMUNDA_REST_ADDRESS:http://localhost:8080}` | Verified, including a trailing `/` |
| `preferRestOverGrpc(true)` | `prefer-rest-over-grpc: true` | **Verified REST-only**: a full call completed with the gRPC address pointed at a closed port |
| `defaultRequestTimeout(60 s)` | `request-timeout: PT60S` | Code-reviewed |
| `applyEnvironmentVariableOverrides(false)` | No direct equivalent | D8 |
| Basic auth if `CAMUNDA_USERNAME` set | `config/CamundaAuthConfiguration` (replaces the starter's credentials factory) + `auth.method: none` | **Verified on the wire**: `admin/pw` gives `Basic YWRtaW46cHc=` and unset gives no header, on both builds; empty password differs, see D5 |
| `waitReady`, `deploy` | Moved to `BpmnDeployer` | Verified |
| `status`, `isNotFound`, `message` (error helpers) | Kept in `Camunda.java` | Verified (409/502 paths identical) |

### 4.5 API server (`cmd/ApiServerMain.java`)

| Folder 1 | Folder 2 | Type | Parity |
|---|---|---|---|
| `main` 53: `Db.connect`, `Camunda.newClient`, `Http.listen` | `Main.runSpring(ApiApplication)` → `api/ApiServerRunner` → `ApiServerMain.serve(db, cam)` 57 (rest of the body unchanged) | Adapted | Verified |
| Routes, handlers, `apiErr`, 404→409 mapping | Unchanged | Same | **Verified: 32/32 requests equivalent** (§8.2) |
| `.bpmnProcessId(Models.PROCESS_CAPITAL_CALL)` 256 | `.bpmnProcessId("capital-call")` 253 | Adapted (literal for static analysis; a test pins it to the constant) | Verified |
| `.variables(GoJson.marshal(in))` 258 | `.variables(in)` 255, serialised by the GoJson-based mapper | Adapted | Verified (start variables identical) |
| `.messageName(Models.MSG_LP_COMMITMENT)` 314 | `.messageName("lpCommitment")` 311 | Adapted | Verified |
| Scanning isolation | `ApiApplication` scans only `api`, `config` | New | Verified (unit test: no job-worker methods in the API role) |

### 4.6 Other components

| Folder 1 | Folder 2 | Type | Parity |
|---|---|---|---|
| `handlers/ReportWriter` (static `writeReport(Handlers h, …)`) | `@Component ReportWriter.writeReport(in, pik)`; render code unchanged | Adapted | Verified (golden test + reports identical) |
| `db/Db`, `json/GoJson`, `http/*`, `report/Pdf`, `util/*`, `models/*` | Same | Same | Verified (hash) |
| `cmd/MockSesMain`, `cmd/MockCreditFacilityMain` | Same; still plain Java | Same | Verified (hash + `MockServicesTest`) |
| `cmd/DemoDriverMain` | Same; still plain Java | Same | **Verified**: full 10-LP demo run on both builds, DB results identical |
| `Main` dispatcher | Same dispatch; `worker`/`apiserver` start Spring | Adapted | Verified |
| `pom.xml`: shade plugin, `camunda-client-java` | `spring-boot-maven-plugin` repackage, `camunda-spring-boot-starter`, BOMs | Replaced | Verified (jar runs every role; same jar name) |
| — | `ProcessConstants` (14 job types) | New | Verified (test: BPMN = constants = annotations) |
| — | `src/main/resources/application.yaml` | New | Verified |

---

## 5. Implementation differences in detail

1. **Registration.** Folder 1 builds a `Map<String, Handler>` with method references (`Handlers.register` 65) and opens one `client.newWorker()` per entry (`JobWorkers.start` 120). Folder 2 has no map or loop. The starter's `JobWorkerManager` discovers the 14 annotated methods and opens a worker for each, with defaults from `application.yaml`.
2. **Variable binding.** Folder 1 parses `job.getVariables()` with `GoJson.unmarshal`. Folder 2 uses `job.getVariablesAsType(X.class)`. The client's `JsonMapper` is replaced by `new CamundaObjectMapper(GoJson.MAPPER.copy())` (`config/CommonConfiguration` 25), so decoding (case-insensitive names, no scalar coercion) and encoding (Go-style numbers, HTML-safe escapes) are unchanged. *Verified* by 0 differences across 26 process instances.
3. **Completion.** Folder 1 sends the complete command and blocks (`send().join()`), logging failures and successes. Folder 2's starter sends it asynchronously through its callback wrapper (`camunda.client.worker.defaults.max-retries` = 0, so no extra retries). *Code-reviewed* (starter source `JobHandlerInvokingBeans.handle`).
4. **Failure.** Folder 2 replaces the starter default (whole stack trace as the message, 0 s back-off) with a `JobExceptionHandlerSupplier` that is a line-for-line copy of Folder 1's failure branch. *Verified.*
5. **Concurrency.** Folder 1: a virtual thread per job via `jobHandlingExecutor`. Folder 2: a `CamundaClientExecutorService` bean with a virtual-thread job executor and a 1-thread scheduler. Without it the starter would run every handler on one thread. *Code-reviewed.* Latencies looked similar: job lifecycle about 0.2 s from creation to completion on both.
6. **Credentials.** The starter derives credentials from `camunda.client.auth.*`. It treats the mere presence of a username/password property as "basic", defaults to `demo/demo`, and rejects empty passwords. Folder 2 therefore overrides its `CredentialsProviderConfiguration` bean and keeps the environment rule. *Verified.*
7. **Process roles.** Folder 1 has five plain `main`s. Folder 2 starts two of them (`worker`, `apiserver`) as Spring applications with role-specific component scans and `web-application-type: none` (no embedded web server). The other three are unchanged.

---

## 6. Dependency mapping (Verified with `mvn dependency:list` on both trees)

| Folder 1 | Folder 2 | Note |
|---|---|---|
| `io.camunda:camunda-client-java` 8.10.9 (direct) | `io.camunda:camunda-spring-boot-starter` 8.10.9 (brings `camunda-client-java` 8.10.9) | Same client version |
| — | Spring Framework 7.0.10, Spring Boot 4.1.2 (`spring-boot`, `-autoconfigure`, `-actuator`, `-health`), `spring-boot-dependencies` BOM | New. No web server or actuator endpoint is exposed |
| `jackson-databind` 2.22.4 | 2.22.4 (pinned via `jackson-bom`) | Single Jackson 2 version; Jackson 3 not on the classpath |
| `log4j-core`, `log4j-slf4j2-impl` 2.26.1 | 2.26.1 (pinned via `log4j-bom`) | One SLF4J binding; no Logback |
| `postgresql` 42.7.14, `HikariCP` 7.1.0 | Same | |
| `junit-jupiter` 5.13.4 | Same (pinned via `junit-bom`) | |
| `maven-shade-plugin` 3.6.0 | `spring-boot-maven-plugin` 4.1.2 | Same artifact name `camunda-assetmgmt.jar` |
| gRPC 1.83.1, Netty 4.2.15, Guava 33.7.2-jre, Gson 2.14.0, SnakeYAML 2.5 | gRPC 1.84.1, Netty 4.2.19, Guava 33.6.0-android, Gson 2.13.2, SnakeYAML 2.6 | **D6**: Spring Boot BOM manages them (your choice: "like the OMS sample") |

Overall 63 → 77 resolved artifacts; 14 added and none removed.

## 7. Configuration and environment variables

| Variable | Folder 1 reader | Folder 2 reader | Same default | Parity |
|---|---|---|---|---|
| `CAMUNDA_REST_ADDRESS` | `Camunda.newClient` | `application.yaml` + `AppProperties` (log line) | ✓ | Verified |
| `CAMUNDA_USERNAME` / `CAMUNDA_PASSWORD` | `Camunda.newClient` | `CamundaAuthConfiguration` | ✓ | Verified (D5 for an empty password) |
| `WORKER_NAME` | `WorkerMain` | `application.yaml` | ✓ | Verified |
| `DEPLOY_ON_START`, `BPMN_DIR` | `WorkerMain` | `BpmnDeployer` via `AppProperties` | ✓ | Verified |
| `DATABASE_URL` | `WorkerMain`, `ApiServerMain` | `CommonConfiguration.db` via `AppProperties` | ✓ | Verified |
| `SES_URL`, `ML_SCORER_URL`, `CREDIT_FACILITY_URL`, `REPORT_DIR`, `PORTAL_PUBLIC_URL`, `CAMUNDA_PUBLIC_URL` | `WorkerMain` | `AppProperties` → services | ✓ | Verified (scenarios use them) |
| `LISTEN_ADDR`, `SES_PUBLIC_URL`, `CAMUNDA_PUBLIC_URL` (API) | `ApiServerMain.main` | `ApiServerMain.serve` (unchanged reads) | ✓ | Verified (`LISTEN_ADDR=:8096` used in tests) |
| `SERVICE` | `Main` | `Main` | ✓ | Code-reviewed |
| `CAMUNDA_CLIENT_*` | Ignored | Bound by Spring relaxed binding | — | D8 |

`docker-compose.yml`, `Dockerfile` and `Makefile` are byte-identical, and every variable they pass is honoured. *Code-reviewed*; the image itself was not built.

---

## 8. Runtime verification results

### 8.1 Tests and static analysis

| Check | Folder 1 | Folder 2 |
|---|---|---|
| `mvn -B test` | 24/24 pass | 32/32 pass (6 unchanged classes + updated `HandlersTest` + 5 new) |
| Shinro code analyser | Exit 0; 1 worker, job type `dynamic-unresolved` (builder style) | Exit 0; **14 workers, 14 distinct job types, 0 unresolved, 0 coverage gaps**, all `static`/`annotation`/`autoComplete=true` |

### 8.2 API replay (Verified, 32 requests, fresh DB each)

The replay covered `GET /`, `HEAD /`, `/health`, `/api/config`, `/api/lps` and the capital-call list. It also covered start with bad JSON, an empty body and no LPs; an unknown call (404); start, detail and the response window; `lp-response` missing, OK and duplicate (409 `ALREADY_EXISTS` from Camunda); `gp-decision` invalid, no gate (409) and OK; `force-settlement` OK and again (409); cancel after completion (409); the report JSON, PDF, missing file, directory listing and redirect; 405s; and a 404.

The status code, Content-Type, `Allow` header and normalised body were identical for all 32 requests. Two apparent differences were checked by hand and are not real:

* `/api/lps` matched once the run label is no longer replaced inside the LP name "Harbor".
* The PDF differs only in the embedded process-instance key and the bridge confirmation timestamp.

### 8.3 Process scenarios (Verified, both builds, same inputs)

| Scenario | Result |
|---|---|
| Force settlement, cancel, GP enforce, nobody answers (ladder + default + bridge), API success paths | DB rows identical. The only differences are two pre-existing measurement races that also occur between two Folder 1 runs: the DB snapshot is taken before the cancel event row is written, and the per-LP deadline (≈11 s) races the parent hard deadline (13 s). Repeated 3× per build: the same path won on both |
| Process variables, all 26 instances | **0 differences** |
| JSON reports (6) | Identical |
| ML scorer down → incident → recovery | Identical (3 attempts 5 s apart, same message, `JOB_NO_RETRIES` on `Task_Predict`, status `aggregated`, completes after retry reset) |
| Demo driver, 10 LPs, `-seconds-per-day 2 -gp-decision waive` | Exit 0 on both, ~31 s; **all 49 DB rows identical**; the driver output differs only in the order of two status lines |
| GP user task | `UserTask_GPApproval` created with the deployed form on both |

### 8.4 Start-up, shutdown and configuration edge cases (Verified)

| Case | Folder 1 | Folder 2 |
|---|---|---|
| Postgres unreachable at start | Waits for Camunda, **deploys**, then logs "waiting for postgres" every 3 s | Logs "waiting for postgres" every 3 s **before** waiting for Camunda/deploying (D2); after ~2 min fails context start |
| `SIGTERM` during the Postgres wait | Exits immediately | **Keeps running until the wait ends (up to ~2 min)** (D1; reproduced twice) |
| Camunda unreachable at start | One "waiting for Camunda" line, no workers yet | Same line, plus job workers already polling: 84 "Failed to activate jobs …" warnings in 15 s (D3) |
| `SIGTERM` on a healthy, idle worker | Exits in 1.5 s | Exits in 1.2 s |
| `DEPLOY_ON_START=false` | No deployment, workers start | Same |
| API with Camunda unreachable | 502 with the transport error | Identical bodies and status |
| gRPC address pointing at a closed port | n/a | Full call completes (REST only) |
| `CAMUNDA_REST_ADDRESS` with trailing `/` | Trimmed | Works |
| Basic auth `admin/pw` / none | `Basic YWRtaW46cHc=` / no header | Same |
| Basic auth `admin/""` | **Start-up crash**: `IllegalArgumentException: Password cannot be null or empty` (`Camunda.newClient` 61) | `Basic YWRtaW46` (D5) |

### 8.5 Footprint (Verified, `-XX:+UseSerialGC -Xmx256m`)

| | Folder 1 | Folder 2 |
|---|---|---|
| Worker RSS idle → after scenarios | 159 → 170 MB | 181 → 194 MB |
| Heap used / metaspace | < 40 MB / 32 MB | < 40 MB / 45 MB |
| Jar size | 34 MB | 43 MB |
| Spring start-up of a role | n/a (plain `main`) | 2.3-5.3 s ("Started Main in …" in the logs) |

`-Xmx256m` (Dockerfile) is sufficient for the tested load; there is no OOM.

---

## 9. Discrepancies

**D1: `SIGTERM` is not honoured while waiting for Postgres at start-up.**

* Folder 1: `WorkerMain` and `ApiServerMain` register no shutdown hook before `Db.connect` returns, so the JVM exits on `SIGTERM`.
* Folder 2: `Db.connect` (40 × 3 s) runs inside Spring's context refresh (`CommonConfiguration.db`). Spring's shutdown hook waits for the refresh to finish, so the process stays alive until Postgres answers or the 2-minute wait ends.
* Impact: Ctrl-C or `docker compose stop` while Postgres is down hangs (Docker sends SIGKILL after 10 s).
* Classification: behavioural regression, Medium.
* Recommendation: do the Postgres wait outside the context refresh. For example, ping in `Main.runSpring` before starting Spring (reusing `Db.connect` logic), or make the `Db` bean lazy and connect in an `ApplicationRunner`.

**D2: Start-up order.**

* Folder 1: wait for Camunda → deploy → connect Postgres → start workers.
* Folder 2: connect Postgres → start workers → wait for Camunda → deploy.
* Effect: with Postgres down the BPMN is not deployed until Postgres is up, and workers poll before the process exists (harmless: no jobs).
* Classification: Low; adaptation inherent to Spring's lifecycle.
* Recommendation: only if the order matters, start the workers after deployment (`camunda.client.worker.defaults.enabled: false` plus enabling them from the deployer).

**D3: Log noise while Camunda is unreachable.**

* The starter logs a WARN per job type per failed poll (2 s back-off): about 7 lines/s for 14 types.
* Classification: Low.
* Recommendation: optionally raise the starter's job-poller logger to ERROR in `log4j2.xml`, at the cost of hiding real poll errors.

**D4: Completion is asynchronous and unlogged.**

* The `[worker] completed <type> element=… pi=…` lines (12 per call in Folder 1) no longer exist.
* Errors on the complete command are logged by the starter in its own format.
* Classification: Low (observability).
* Recommendation: if the line is needed, use a starter result-processor hook or an `ApplicationListener` on job metrics.

**D5: Empty password with a username.**

* Folder 1 crashes at start-up (Camunda client validation).
* Folder 2 sends `Basic user:`, matching the original Go client.
* Classification: intentional fix; documented so that nobody depends on the crash.

**D6: Library versions.**

* The Spring Boot BOM manages gRPC 1.84.1, Netty 4.2.19, Guava 33.6.0-android, Gson 2.13.2 and SnakeYAML 2.6, which differ from what Camunda 8.10.9 itself resolves.
* Every run in this audit works with them, but the combination is not what Camunda tested.
* Classification: Low risk, accepted by your choice.
* Recommendation: re-check on each starter or Spring Boot upgrade.

**D7: Footprint.** See §8.5. Low.

**D8: Environment overrides.**

* Spring relaxed binding lets variables such as `CAMUNDA_CLIENT_REST_ADDRESS` or `CAMUNDA_CLIENT_WORKER_DEFAULTS_TIMEOUT` change the client; Folder 1 ignored them (`applyEnvironmentVariableOverrides(false)`).
* Classification: Low. No such variables are set in `docker-compose.yml`.
* Code-reviewed: a run with `CAMUNDA_CLIENT_GRPCADDRESS` set completed normally, but whether the value was actually bound was not checked.

**D9: Cosmetic logs.** Spring start-up lines ("Starting Main …", "No active profile …", "Started Main in …") and the job types are listed in starter map order.

**Maintainability note (Code-reviewed, no behaviour impact).** `AppProperties` also exposes `listenAddr()` and `sesPublicUrl()`, but `ApiServerMain.serve` still reads those variables directly with `Env.get`. There are two places, with identical defaults.

**No missing functionality was found.** Every class, route, job type, message, SQL statement, configuration variable and resource of Folder 1 has a counterpart in Folder 2.

---

## 10. Unverified items

| Item | Reason |
|---|---|
| `docker build` / `make up` / `make demo` in Docker | Docker Hub is blocked in the sandbox; `Dockerfile`, `Makefile` and `docker-compose.yml` are unchanged and the jar name and entry point are the same |
| Tasklist UI click-through and dashboard in a browser | Not exercised; the user task, form deployment and dashboard HTML are verified at API/engine level |
| Load and long-running behaviour (16 concurrent jobs per type, hours of uptime) | Not tested |
| Camunda restart while the workers are running | Not re-tested for Folder 2 |
| An authenticated cluster (`unprotectedApi: false`) | The local cluster accepts any credentials; the headers were verified on the wire instead |

---

## 11. Conclusion

Folder 2 fulfils the migration goal: the Camunda Java client wiring was replaced by the Spring Boot Starter's `@JobWorker` model without losing any API, job, message, configuration or data behaviour. In every runtime comparison the two builds produced the same process variables, database rows, reports and API responses.

Fix D1 before relying on graceful shutdown when Postgres can be unavailable at start-up. D2–D4 and D6–D9 are adaptations with low impact, and D5 is a deliberate correction. Run `make up` and one `make demo` on your machine to close the remaining Docker gap.
