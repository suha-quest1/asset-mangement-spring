# Change report: `camunda-client-java` → `camunda-spring-boot-starter` (`@JobWorker`)

Executed from `SPRING_BOOT_STARTER_MIGRATION_INSTRUCTIONS.md` on 2026-11-20. Changes are in the working tree of `cmn-springboot-asstmgt` only; nothing was committed (as requested). The Shinro repository was only read.

## 1. Result against the definition of done

| # | Item | Status |
|---|---|---|
| 1 | 14 `@JobWorker(type = ProcessConstants.JOB_…)` methods on `@Component` beans; no dispatch map, loop or method references; `JobWorkers.java` deleted | Done. `worker/CapitalCallWorkers` (12) and `worker/LpResponseWorkers` (2) |
| 2 | BPMN untouched and still the deployed source | Done. `bpmn/` unchanged, deployed by `camunda/BpmnDeployer` |
| 3 | `mvn -B test` green; Docker image builds; `make up` + demo run | `mvn -B package`: 32 tests pass. Docker image / `make up` **not verified** (Docker Hub is blocked in my sandbox) |
| 4 | Parity evidence vs baseline | Done (section 4) |
| 5 | Analyser: exit 0, 14 workers, 0 unresolved job types, 0 coverage gaps | Done (section 5) |
| 6 | This report | Done |

## 2. Versions and decisions

| Item | Decision |
|---|---|
| Starter | `io.camunda:camunda-spring-boot-starter:8.10.9` (exists on Maven Central; matches the 8.10.9 cluster). Brings `camunda-client-java` 8.10.9 |
| Spring Boot | 4.1.2, the version the starter's POM declares; `spring-boot-dependencies` BOM imported |
| Q1 annotation package | The jar contains `io.camunda.client.annotation.JobWorker` (there is no `io.camunda.spring.client.annotation` package in 8.10.9). Used that |
| Q2 starter flavour | Default `camunda-spring-boot-starter` (Spring Boot 4) |
| Q3 retry/back-off/message | Preserved: `retries - 1` (min 0), 5 s back-off (`camunda.client.worker.defaults.retry-backoff: PT5S`), message = exception message with `panic: ` prefix for unchecked exceptions, same `[worker] job … failed, retries left …` log line. Implemented as a `JobExceptionHandlerSupplier` bean in `worker/WorkerConfiguration`. The starter default would have sent the full stack trace as the message and a 0 s back-off |
| Q4 API server on the starter | Yes. `api/ApiApplication` + `api/ApiServerRunner` inject the starter's `CamundaClient`; `Router`/`FileServer`/`Http` untouched |
| Q5 deployment | Explicit runner `camunda/BpmnDeployer` (wait for topology, one deployment of `BPMN_DIR/*.bpmn` + `*.form`, 20 attempts × 3 s, `DEPLOY_ON_START`) |
| BOM versions (your answer: "most similar to the OMS sample") | The OMS sample imports the Spring Boot BOM and lets it manage versions, so gRPC 1.84.1, Netty 4.2.19, Guava 33.6.0-android, Gson 2.13.2, SnakeYAML 2.6 are Spring-Boot-managed. The project's existing pins (Jackson 2.22.4, Log4j 2.26.1, JUnit 5.13.4) are kept by importing their BOMs before Spring Boot's, so each resolves to one version |
| Logging | Log4j 2 stays (`log4j2.xml` unchanged). The starter pulls no Logback; `spring-boot-starter-logging` is excluded anyway. One SLF4J binding (`log4j-slf4j2-impl`) |
| Packaging | `maven-shade-plugin` replaced by `spring-boot-maven-plugin` (repackage, `Start-Class` = `Main`); jar name unchanged, so `Dockerfile`/`Makefile` need no change |
| R1 worker name | `camunda.client.worker.defaults.name: ${WORKER_NAME:capital-call-java-worker}`; verified on jobs in Camunda (`worker = capital-call-java-worker`) |
| R2 variable serialization | The client's JSON mapper bean is built on a copy of `GoJson.MAPPER` (`config/CommonConfiguration`), so variables are written and read exactly as before. Proven by the variable diff (section 4). With that in place the API start call uses `.variables(in)` (DTO) |
| R4 memory | Measured with `-XX:+UseSerialGC -Xmx256m`: worker RSS 181 MB idle → 194 MB after the scenarios (baseline 159 → 170 MB); heap used < 40 MB, metaspace 45 MB (was 32 MB). `-Xmx256m` kept |

## 3. Things done beyond, or differently from, the instruction file

1. **Job-type test (4.9).** The file says each type appears exactly once in the BPMN. It does not: 18 service tasks use 14 types (`autoFollowUp` ×3, `recordLPResponse` ×2, `finalizeResponses` ×2, differing only in headers). The test asserts 18 tasks, 14 distinct types, equal sets, and exactly one worker method per type. BPMN not changed.
2. **Worker executor.** The starter defaults to 1 job-handling thread (`camunda.client.execution-threads`). A `CamundaClientExecutorService` bean restores virtual-thread-per-job handling.
3. **Poll error back-off.** A `backoffSupplier` bean keeps the fixed 2 s pause after a failed poll (no property exists for it).
4. **Credentials.** The starter derives the provider from `camunda.client.auth.*`, defaults to `demo/demo` for basic auth, and its basic provider rejects an empty password (then silently uses no auth). `config/CamundaAuthConfiguration` replaces the starter's provider factory with the old rule: basic auth when `CAMUNDA_USERNAME` is set, password may be empty, otherwise none. Verified on the wire: `admin/pw` → `Basic YWRtaW46cHc=`, `admin/""` → `Basic YWRtaW46`, unset → no header.
5. **Start-up log.** The starter logs nothing at the configured level, so `worker/WorkerStartupLog` prints the former `[worker] subscribed to job type "…"` line for each opened worker (14) and `job workers started`. The per-job `[worker] completed …` line no longer exists (completion is done by the starter).
6. **`-parameters`** compiler flag added (the starter warns once per method without it).
7. **Domain split.** Handler bodies moved unchanged into `service/CapitalCallService`; HTTP into `service/HttpJson`, `NotificationClient`, `RiskScorerClient`, `CreditFacilityClient`; shared SQL into `service/CallRepository` (`@Repository`). All 168 string literals of the old `Handlers` (SQL, messages, formats) are present in the new code. `LpScope`/`GpScope` moved to `models/`. `ReportWriter` is now a `@Component` (logic unchanged).
8. **Docs.** README and `demo_camunda_assetmgmt.md` updated; the dangling link to `GO_TO_JAVA_MIGRATION_REPORT.md` now points to `GO_TO_JAVA_MIGRATION_AUDIT.md` (untracked file in the folder; commit it with the rest or drop the link).

Known behaviour differences (all minor): job completion is sent asynchronously by the starter (was `send().join()` with a log line); the starter adds its own command retry wrapper (disabled: `max-retries` default 0); environment variables named `CAMUNDA_CLIENT_*` can now override client settings through Spring's relaxed binding; a trailing `/` on `CAMUNDA_REST_ADDRESS` is no longer trimmed.

## 4. Behavioural parity (old build vs new build, same Camunda 8.10.9 + Postgres 16, fresh DB per run, ML scorer with `PYTHONHASHSEED=0`)

| Check | Result |
|---|---|
| `mvn -B test` before the change | 24/24 (first real Surefire run of this project) |
| `mvn -B package` after | 32/32 (new: job-type agreement, role wiring, error text, entry-point literals, credentials) |
| Process variables of all 26 instances (capital-call + lp-response), read from the engine | **0 differences** (new2 run), including numbers and nulls |
| JSON reports (6 calls) | Identical except `generatedAt`/`processInstanceKey` |
| API success/error responses (8 requests incl. 400/409 paths) | Identical |
| Failure path (ML scorer down) | Identical: 3 attempts 5 s apart, incident `JOB_NO_RETRIES` on `Task_Predict`, status `aggregated`, recovery after retry reset |
| DB rows of 4 scenarios | Identical apart from two measurement races that also occur between two baseline runs: the cancel scenario snapshot is taken before the `markCallCancelled` event row is written; and in "nobody answers" the per-LP deadline (≈11 s) and the parent hard deadline (13 s) are close, so either path can win. Repeated 3× on each build: hard-deadline path every time on both |
| GP user task | `UserTask_GPApproval` created with the deployed form on both builds (2 per run); in the scenarios it is decided through the API message, not clicked in Tasklist |

Not verified here: Docker image build and `make up` (Docker Hub blocked), clicking through Tasklist/dashboard, load behaviour. Please run `make up` and one `make demo` on your machine.

## 5. Shinro code analyser (built from `tools/camunda/code-analyzer`, run read-only)

| | Before | After |
|---|---|---|
| Exit code | 0 | 0 |
| Workers | 1 (the generic builder loop, job type `dynamic-unresolved`) | 14, all `static`, `annotation`, `autoComplete=true` |
| Distinct job types / unresolved | 1 / 1 | 14 / 0 |
| Coverage gaps | 0 | 0 |
| Entry points | 2 (empty process id/message name) | 2: `capital-call` (6 start variables) and `lpCommitment` |
| Gaps | `no-test-oracles` (info) | `no-test-oracles` (info) |

All 14 workers are queued for LLM classification because they reach Postgres/HTTP sinks, which is the expected outcome. Variables read/written are extracted for every worker.

## 6. Files

Added: `api/ApiApplication`, `api/ApiServerRunner`, `camunda/ProcessConstants`, `camunda/BpmnDeployer`, `config/AppProperties`, `config/CommonConfiguration`, `config/CamundaAuthConfiguration`, `models/LpScope`, `models/GpScope`, `service/*` (7), `util/Lists`, `worker/*` (6), `src/main/resources/application.yaml`, 5 test classes.
Modified: `pom.xml`, `Main`, `camunda/Camunda` (error helpers only), `cmd/ApiServerMain` (injected client, literal ids, DTO start variables), `handlers/ReportWriter`, `HandlersTest`, `README.md`, `demo_camunda_assetmgmt.md`.
Deleted: `camunda/JobWorkers.java`, `cmd/WorkerMain.java`, `handlers/Handlers.java`, `camunda/JobWorkersTest.java`.
Unchanged: `bpmn/*`, `db/*`, `services/*`, `tools/*`, `camunda/application.yaml`, golden files, `Dockerfile`, `Makefile`, `docker-compose.yml`, `GoJson`, `GoFmt`, `Router`, `FileServer`, `Http`, `Pdf`, `Db`, models' JSON fields, mocks, demo driver.
