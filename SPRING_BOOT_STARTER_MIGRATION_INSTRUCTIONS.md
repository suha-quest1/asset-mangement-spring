# Agent instructions: move `cmn-springboot-asstmgt` from `camunda-client-java` to `camunda-spring-boot-starter` (`@JobWorker`)

**Audience:** a coding agent that will make the changes. **Author's role:** assessment only. Nothing in the repository was changed to produce this file.

**Repository to change:** `/Users/suhan/Desktop/repos/asst-mgmt/cmn-springboot-asstmgt`
**Reference (read-only, never modify):** `/Users/suhan/Desktop/repos/shinro-app/shinro`, specifically `tools/camunda/code-analyzer` (the Camunda→Temporal code analyser).

Evidence labels used below: **[Code-read]** = read in source in this assessment; **[Web]** = from Camunda documentation/forum search results; **[Unverified]** = not checked, the agent must check it before relying on it.

---

## 0. Hard requirements (from the owner)

1. Library: `io.camunda:camunda-spring-boot-starter` (8.8+). Job workers use the starter's `@JobWorker` annotation.
2. One `@JobWorker` method per job type, on a `@Component` bean.
3. No dispatch map, no registration loop, no method references.
4. Job types are string literals or `static final String` constants. Never a variable, never built at runtime.
5. Keep the BPMN files in the repository (`bpmn/*.bpmn`, `bpmn/gp-approval.form`).
6. The generated code must be in a shape the Shinro code analyser can parse deterministically (section 3).
7. Preserve runtime behaviour (section 6 defines how to prove it).

Example target shape given by the owner:

```java
@JobWorker(type = ProcessConstants.JOB_VALIDATE_ORDER)
public Map<String, Object> validateOrder(ActivatedJob job) { ... }
```

## 1. Assessment summary

### 1.1 What the repository is today

**[Code-read]** It is a plain Java 21 program (no Spring). One shaded jar, `Main` dispatches on `worker|apiserver|mockses|mockcreditfacility|demodriver`. Camunda 8.10.9 server; client is `io.camunda:camunda-client-java:${camunda.version}` (8.10.9).

Camunda-client usage, exhaustively:

| Where | What it does |
|---|---|
| `camunda/Camunda.java` | `newClient` (REST-only client, basic auth from env, 60 s timeout, virtual-thread job executor), `waitReady` (topology loop, 3 s), `deploy` (BPMN + form), `status/isNotFound/message` (error helpers) |
| `camunda/JobWorkers.java` | **A hand-written dispatch map and registration loop** (`handle(type, handler)` → `handlers` map → `start()` loop calling `client.newWorker().jobType(jobType)…open()`), plus the `execute` wrapper: `retries = max(0, retries-1)`, 5 s retry back-off, `"panic: "` prefix for unchecked exceptions, complete with returned variables. Also a `Job` facade (`bind`, `header`, `processInstanceKey`) |
| `handlers/Handlers.java` | One class, 14 handler methods registered by `register(JobWorkers)` with **method references** (`w.handle("issueCapitalCall", this::issueCapitalCall)` …). Dependencies are public fields set by hand in `WorkerMain` |
| `cmd/WorkerMain.java` | Builds the client, waits ready, deploys BPMN/forms with 20 retries, connects Postgres, wires `Handlers`, starts workers, shutdown hook |
| `cmd/ApiServerMain.java` | Uses a `CamundaClient` for `newCreateInstanceCommand`, `newPublishMessageCommand`, `newCorrelateMessageCommand` (lines ~255, ~313, ~383) |
| `cmd/DemoDriverMain.java`, `MockSes*`, `MockCreditFacility*` | **Do not use Camunda classes** (HTTP only). Leave them alone |

The 14 job types (identical in `bpmn/capital-call.bpmn` and `bpmn/lp-response.bpmn`, each `retries="3"`): `issueCapitalCall, notifyLPs, autoFollowUp, recordLPResponse, finalizeResponses, aggregateLiquidity, predictDefaultRisk, scoreLPPortfolio, triggerBridge, escalateToGP, applyGPDecision, settleAndReconcile, emitLiquidityReport, markCallCancelled`. There is also a user task with form `gp-approval-form` (`bpmn/gp-approval.form`). Processes: `capital-call`, `lp-response`.

### 1.2 What the current code looks like to the Shinro analyser

**[Code-read, analyser not run]** The analyser (`internal/dialect/java.go`, `internal/analyzer/analyzer.go`) would report problems on the *current* code:

* `JobWorkers.java` contains `.newWorker(` and its Javadoc mentions it. `rawWorkerAnchors` counts the literal text `.newWorker(` (and the literal text `@JobWorker`) anywhere in any scanned `.java` file (`analyzer.go`, function `rawWorkerAnchors`). The plain-client extractor then sees `.jobType(jobType)` with a **variable**, so the job type resolves to `dynamic-unresolved`, and the dispatch happens through a method reference passed as `h`.
* The 14 handlers carry no registration annotation, so zero annotation workers are found.
* Result: a completeness gap (`coverage-gap`) and exit code 3 from the analyser. After the migration this must be exit 0 with 14 workers.

### 1.3 What the starter changes, and what has to be decided (see section 7 for owners)

1. The brief says `io.camunda.spring.client.annotation.JobWorker`. **[Web]** A forum post about starter 8.8.x shows `import io.camunda.client.annotation.JobWorker;`. The analyser accepts both spellings, because it matches the annotation by simple name and lists both package markers in `discover.go` (`io.camunda.spring.client.annotation.JobWorker`, `io.camunda.client.annotation`). **The agent must open the real starter jar and use the package that actually exists**, then record it in the summary. Do not guess.
2. **[Web]** The default starter flavour changes with the release line: in 8.8 `camunda-spring-boot-starter` bundles Spring Boot 3.5; the documentation says the default starter moves to Spring Boot 4.0 from 8.9 on, and offers `camunda-spring-boot-3-starter` / `camunda-spring-boot-4-starter` aliases. The cluster here is 8.10.9. **[Unverified]** whether `io.camunda:camunda-spring-boot-starter:8.10.9` is published; the agent must resolve it (`mvn dependency:resolve`) and fall back to the newest 8.10.x only if 8.10.9 does not exist. Record the choice.
3. Logging, fat-jar packaging, Jackson and error/retry semantics all change when Spring is introduced (sections 4.1, 4.9, 4.10).

---

## 2. The analyser's contract: code shapes it expects

All paths below are inside `shinro/tools/camunda/code-analyzer/` and are **[Code-read]**. The analyser is deterministic (tree-sitter), offline, and uses no LLM. These are the rules the new code must follow.

| # | Rule | Evidence |
|---|---|---|
| A1 | A worker is a **method** carrying `@JobWorker` (simple name match; also `@ZeebeWorker`). The annotation must be written directly on the method inside a `class` declaration. | `java.go` `jobWorkerAnnotation`, `directMethods` |
| A2 | Job type comes from `type = …`. Resolved forms: string literal; same-file `static final String` literal; cross-file constant referenced as `Class.CONST` or bare `CONST`; value-bearing enum constant; `${property}` resolved from `.properties`/`.yaml`; concatenation of literals/constants. Anything else is `dynamic-unresolved` and the worker is escalated. | `resolve.go` `resolveJobType`, `resolveConcat` |
| A3 | Constants are indexed **only** when declared in a `class_declaration` as `String NAME = "literal"` (fields in interfaces, records or initialisers like `"a" + b()` are not indexed). Two files defining the same simple constant name with different values make it *ambiguous* and unresolved. | `resolve.go` `indexConstants`, `record` |
| A4 | Omitting `type` makes the analyser use the **method name** as job type. Do not rely on this here: always give `type`. | `java.go` lines ~1360–1380 |
| A5 | `autoComplete` defaults to true for `@JobWorker`. Only the literal `autoComplete = true/false` is read. | `jobWorkerAnnotation` |
| A6 | **Variables read** are recognised from: `@Variable` / `@VariablesAsType` parameters; `job.getVariable("x")`; `job.getVariablesAsType(X.class)`; `.get("x")` on a local assigned from `getVariablesAsMap()`. A `@VariablesAsType`/`getVariablesAsType` DTO is expanded to its **field names**, so the DTO must be a `class` with declared fields (records and Lombok-only types are not expanded). | `extractReads`, `extractParamVars`, `dtoFieldVars` |
| A7 | **Variables written** are recognised from: `Map.of(...)`, `Map.ofEntries(entry(...))`, `.put("k", v)` on a local declared with type `Map…`, and `return new Dto(...)` / returning a local typed as an in-repo DTO class. A helper such as the current `Handlers.m(...)` is **not** recognised. | `extractWrites` |
| A8 | **Call graph** is built from method invocations whose receiver is a field of the worker class (constructor-injected `private final Service service;`), including fields inherited from in-repo superclasses, and from locals/params typed to in-repo classes. Unqualified calls to the worker's own helper methods are **not** followed. | `extractCalls`, `fieldsWithInherited` |
| A9 | **I/O sinks** are detected on the *service* classes the workers call (imports such as `java.sql`, `java.net.http`, `org.postgresql`, `com.zaxxer.hikari`, JDBC/HTTP/messaging libraries; methods propagated transitively; interfaces resolved to in-repo implementations). A worker that reaches a sink, or a call to a non-in-repo, non-known-pure type, is **escalated** into `classificationNeeded[]` for later LLM judgement (that is expected for these workers, they write to Postgres and call HTTP services). The analyser also treats methods whose names start with verbs such as `save`, `send`, `create`, `update`, `post`, `process`, `notify`, `email`, `store` on unresolved receivers as side-effect-suspected. | `java.go` `ioSinkTypeMarkers`, `mutatingVerbs`, `dataflow.go` `assess` |
| A10 | `newThrowErrorCommand`, `newFailCommand`, `newCompleteCommand`, `newSetVariablesCommand` inside a worker body are recorded as engine calls. | `extractCamunda` |
| A11 | Start points (`newCreateInstanceCommand`, `newPublishMessageCommand`) are found in any file. The process id / message name is read **only as a string literal** in `.bpmnProcessId("…")` / `.messageName("…")`; a constant reference yields an empty value. Start variables are recovered from `.variables(Map.of(...))` or from `.variables(dto)` where `dto` is a local/param of an in-repo class (or from an `@RequestBody` DTO). | `entryPoints`, `mapKeysIn`, `startVarsFromDTO` |
| A12 | **Completeness guard:** the analyser counts the raw text `@JobWorker`, `@ZeebeWorker` and `.newWorker(` in every scanned source file (main **and** test, comments and Javadoc included) and compares it with the number of workers it parsed. Any extra mention yields a `coverage-gap` and exit code 3. | `analyzer.go` `rawWorkerAnchors`; `output.go` coverage-gap checks; `main.go` exit 3 |
| A13 | Classes are indexed by **simple name**. Two in-repo classes with the same simple name in different packages produce an `ambiguous-class-name` gap. | `java.go` `ambiguousClassGap` |
| A14 | The `bpmn/` directory is picked up wherever it is (the scan walks the whole input directory, skipping `.git target build dist out node_modules .idea bin .venv`), `.bpmn`/`.form` files are parsed for the process model and the job-type join. Note: a directory called `bin` or `build` anywhere is skipped. | `discover.go` |
| A15 | `.yaml/.yml/.properties` files are all merged into one flat config table (used only for `${…}` placeholders). The existing `camunda/application.yaml` (the *cluster* config) will be merged with the new Spring `application.yaml`; keys do not clash today, keep it that way. | `discover.go`, `resolve.go` `buildConfigTable` |

Consequences that drive the plan:

* Never write the text `@JobWorker` or `.newWorker(` in a comment, string, Javadoc or test. (Use "job-worker annotation" in prose.) Refer to the annotation class in tests as `JobWorker.class`, never as `@JobWorker`.
* Keep DTOs as classes with fields (the repo already does; do **not** convert `models/*` to records).
* Put I/O behind separately injected service classes so the call graph is visible (A8, A9).
* Return variables with `Map.of`/`Map.ofEntries`, or a local `Map<String,Object>` with `.put("literal", …)` (A7).

---

## 3. Target design

```
com.vishworks.assetmgmt
  Main                                  (unchanged dispatcher; worker and apiserver roles now start Spring)
  camunda/
    ProcessConstants                    NEW  final class: 14 job-type constants (and nothing else)
    CamundaErrors                       trimmed Camunda.java: status/isNotFound/message only
    BpmnDeployer                        NEW  @Component ApplicationRunner: wait ready + deploy with retry
  worker/
    CapitalCallWorkers                  NEW  @Component: the 12 capital-call job types
    LpResponseWorkers                   NEW  @Component: autoFollowUp, recordLPResponse
  service/                              NEW  @Service classes holding the DB / HTTP / report logic moved out of Handlers
  config/                               NEW  @Configuration: Db, HttpClient, AppProperties
  worker/WorkerApplication              NEW  @SpringBootApplication(scanBasePackages = {worker, service, config, camunda})
  api/ApiApplication                    NEW  @SpringBootApplication limited to the API role
```

(Package names are suggestions. The only hard rules are in sections 0 and 2. Avoid duplicate simple class names, A13. Note `Handlers.Job`, `Handlers.LpScope`, `Handlers.GpScope` are nested classes; `Job` disappears, the other two should move to `models/` as top-level classes with unique names.)

Role isolation (important): `@SpringBootApplication` scans from its own package by default. If one scan root covers the whole project, the **API** role would also start the job workers. Give each role its own application class and restrict `scanBasePackages` (or use `@Profile`) so the `apiserver` role never registers `@JobWorker` beans and the `worker` role never starts the HTTP server.

---

## 4. Step-by-step changes

Work on a new git branch. Make one commit per step. Run `mvn -B test` after each step that compiles.

### 4.0 Baseline (before any edit)

1. Record `git rev-parse HEAD`. Run `mvn -B test` and save the result (the 24 unit tests must keep passing in some form).
2. Capture parity baselines from the **current** code against a running stack, exactly as listed in section 6.2, so the migrated code can be compared with them.
3. Do not edit `bpmn/*`, `db/*`, `services/*`, `tools/gen_bpmn.py`, golden test resources, `camunda/application.yaml`.

### 4.1 `pom.xml`

* Add `io.camunda:camunda-spring-boot-starter` at the version decided in 1.3 (keep a single `camunda.version` property). Keep `camunda-client-java` only if it is not already transitive; prefer removing the direct dependency so the starter owns it.
* Spring Boot version: do **not** invent one. Read the starter's POM (`mvn dependency:tree`) and import the matching `spring-boot-dependencies` BOM, or use the matching `spring-boot-starter-parent`.
* **Logging.** The project uses Log4j 2 through SLF4J (`log4j-slf4j2-impl`, `src/main/resources/log4j2.xml`, `util/Log.java`). Spring Boot defaults to Logback. Exclude `spring-boot-starter-logging` from the starter and add `spring-boot-starter-log4j2` (or equivalent) so there is exactly one SLF4J binding, and keep `log4j2.xml` so the log format `Log.printf` produces is unchanged. **[Unverified]** check `mvn dependency:tree` shows one binding.
* **Jackson.** `pom.xml` pins `jackson.version` 2.22.4 and the code imports `com.fasterxml.jackson.*` (Jackson 2). Spring Boot 4 also ships Jackson 3 (`tools.jackson`). Make sure Jackson 2 resolves to one version for both the app and the Camunda client (`mvn dependency:tree -Dincludes=com.fasterxml.jackson.core`), and do not switch `GoJson` to Jackson 3. **[Unverified]**
* **Fat jar.** Replace `maven-shade-plugin` with `spring-boot-maven-plugin` (`repackage`, `mainClass` = `com.vishworks.assetmgmt.Main`), keep `<finalName>camunda-assetmgmt</finalName>` so `Dockerfile` (`/src/target/camunda-assetmgmt.jar`) and `Makefile` (`JAR := target/camunda-assetmgmt.jar`) stay valid. If you keep shade instead, it must merge `META-INF/spring/*.imports` and `META-INF/spring.factories` with `AppendingTransformer`; the current transformers do not, which would silently disable the starter's auto-configuration.
* Keep `postgresql`, `HikariCP`, JUnit. Do not add `spring-boot-starter-web`, `spring-boot-starter-data-*`, or `spring-jdbc` (the API server stays on the JDK `HttpServer` + `Router`, the DB layer stays `Db`). If a `DataSource`/JDBC auto-configuration appears on the classpath, exclude it in `application.yaml`.

### 4.2 `ProcessConstants` (job types)

Create:

```java
package com.vishworks.assetmgmt.camunda;

public final class ProcessConstants {
  private ProcessConstants() {}

  public static final String JOB_ISSUE_CAPITAL_CALL   = "issueCapitalCall";
  public static final String JOB_NOTIFY_LPS           = "notifyLPs";
  public static final String JOB_AUTO_FOLLOW_UP       = "autoFollowUp";
  public static final String JOB_RECORD_LP_RESPONSE   = "recordLPResponse";
  public static final String JOB_FINALIZE_RESPONSES   = "finalizeResponses";
  public static final String JOB_AGGREGATE_LIQUIDITY  = "aggregateLiquidity";
  public static final String JOB_PREDICT_DEFAULT_RISK = "predictDefaultRisk";
  public static final String JOB_SCORE_LP_PORTFOLIO   = "scoreLPPortfolio";
  public static final String JOB_TRIGGER_BRIDGE       = "triggerBridge";
  public static final String JOB_ESCALATE_TO_GP       = "escalateToGP";
  public static final String JOB_APPLY_GP_DECISION    = "applyGPDecision";
  public static final String JOB_SETTLE_AND_RECONCILE = "settleAndReconcile";
  public static final String JOB_EMIT_LIQUIDITY_REPORT = "emitLiquidityReport";
  public static final String JOB_MARK_CALL_CANCELLED  = "markCallCancelled";
}
```

Rules: a `class` (not interface/enum/record), plain string literals, no concatenation, constant names unique repo-wide (A3). Leave `Models.PROCESS_*`/`MSG_*` where they are.

### 4.3 Move the logic out of `Handlers`, then delete the registration code

Today `Handlers` mixes four concerns: job entry points, SQL, outbound HTTP (`postJSON`, `email`, `netReason`), and shared helpers (`event`, `setCallStatus`, `refreshAggregates`, `m`, `isoSeconds`). The analyser only sees through injected fields (A8), so split:

* **Services** (`@Service`, constructor-injected `private final` fields, no public mutable fields): e.g. `CallRepository`/`CallService` (SQL: `event`, `setCallStatus`, `refreshAggregates`, the INSERT/UPDATE blocks), `NotificationClient` (`postJSON`, `email`, `netReason`, SES), `RiskScorerClient` (ML scorer), `CreditFacilityClient`, and the existing `ReportWriter` (inject it; today it takes `Handlers` as a parameter). Keep method bodies **byte-for-byte equal** wherever possible: same SQL strings, same arithmetic (`round2`), same `GoFmt` calls, same error messages. This is a move, not a rewrite.
* **Worker components**: `@Component` classes whose methods are thin: read input, call services, return variables. One `@JobWorker` method per job type, 14 in total.
* Delete `Handlers.register(...)`, delete `JobWorkers.java` entirely (the dispatch map, the loop, `Handler`, `JobException`, `Job`). Nothing in the repo may keep `.handle(...)`, `this::issueCapitalCall`, or a `Map<String, Handler>`.

Worker method contract for every handler:

| Job type | Current method (`Handlers.java` line) | Input | Output |
|---|---|---|---|
| `issueCapitalCall` | 195 | `CapitalCallInput`; also uses `job.processInstanceKey()` | variables map (built at lines ~240–275) |
| `notifyLPs` | 280 | `CapitalCallInput` | none (currently returns `null`) |
| `autoFollowUp` | 312 | `LpScope` + custom header `stage` | `followUps` |
| `recordLPResponse` | 340 | `LpScope` + custom header `status` | `lpResponse` |
| `finalizeResponses` | 381 | `CallState` + custom header `reason` | `lpResponses` (may be **null**), `settlementMode` |
| `aggregateLiquidity` | 410 | `CallState` | `totalCommitted`, `gapUSD`, `gapPercent` |
| `predictDefaultRisk` | 441 | `CallState` | `lpResponses`, `highRiskLPs` |
| `scoreLPPortfolio` | 471 | `CallState` | `portfolioRisk` |
| `triggerBridge` | 508 | `CallState` | `bridgeTriggered`, `bridgeResult` |
| `escalateToGP` | 536 | `GpScope` | none |
| `applyGPDecision` | 556 | `GpScope` | `gpAction`, `gpName` |
| `settleAndReconcile` | 586 | `CallState` | `wiresSettled` |
| `emitLiquidityReport` | 633 | `CallState`; uses `job.processInstanceKey()` | `reportPdf`, `reportJson` |
| `markCallCancelled` | 645 | `CallState` | none |

Signature pattern (matches the owner's example and the analyser, A1/A6):

```java
@JobWorker(type = ProcessConstants.JOB_AGGREGATE_LIQUIDITY)
public Map<String, Object> aggregateLiquidity(ActivatedJob job) {
  CallState in = job.getVariablesAsType(CallState.class);
  ...
  Map<String, Object> vars = new LinkedHashMap<>();
  vars.put("totalCommitted", round2(committed));
  vars.put("gapUSD", round2(gap));
  vars.put("gapPercent", pct);
  return vars;
}
```

Rules for the body:

* **Input.** Use either `ActivatedJob job` + `job.getVariablesAsType(X.class)` or an `@VariablesAsType X in` parameter. Both are read by the analyser (A6). The first keeps the owner's example signature. Replace every `job.bind(X.class)`. For the three headers use the starter's custom-headers parameter annotation (`@CustomHeaders Map<String,String>`, **[Unverified]** exact annotation name/package: confirm in the jar) or `job.getCustomHeaders().get("stage")`, replacing `job.header(...)` (which returned `""` when absent; keep that default).
* **Process instance key.** `job.getProcessInstanceKey()` is a `long`; the old facade returned `Long.toString(...)`. Keep the string.
* **Output.** Replace the `m("k", v, ...)` helper (A7 does not see it) with a local `Map<String,Object>` + `put` as above, or `Map.of(...)` when no value can be null. `Map.of` throws on null values, and `finalizeResponses` deliberately returns a null value (`out.isEmpty() ? null : out`), so use the `put` form there. Handlers that returned `null` (no variables): declare `void`. **[Unverified]** confirm in the starter that a `void`/null return completes the job without variables.
* **Errors.** Throw (checked exceptions allowed: declare `throws Exception`). Do not catch-and-swallow around the whole body. See 4.8 for retry/back-off semantics.
* **Do not** call `jobClient.newCompleteCommand`/`newFailCommand` manually: the `@JobWorker` default `autoComplete = true` is what the analyser and the Shinro conversion assume for this style, and manual commands add `autoComplete=false` lifecycle flags (A5, A10).
* **No** `@JobWorker` text in comments (A12).

### 4.4 Spring bootstrap and roles

* `Main` keeps the dispatcher. `worker` → `new SpringApplicationBuilder(WorkerApplication.class).web(WebApplicationType.NONE).run(rest)`. `apiserver` → same for `ApiApplication`. `mockses`, `mockcreditfacility`, `demodriver` call their existing `main` unchanged (plain Java, no Spring, no Camunda).
* `spring.main.banner-mode=off`, `spring.main.web-application-type=none`.
* Beans: `Db` (`Db.connect(...)`, keeps the 40×3 s retry and the `close` as destroy method; the old shutdown hook is removed because Spring closes beans), `HttpClient` (20 s connect timeout as in `WorkerMain`), a typed `AppProperties` for the existing env variables (`SES_URL`, `ML_SCORER_URL`, `CREDIT_FACILITY_URL`, `REPORT_DIR`, `PORTAL_PUBLIC_URL`, `CAMUNDA_PUBLIC_URL`, `DATABASE_URL`, `BPMN_DIR`, `DEPLOY_ON_START`, `WORKER_NAME`). Defaults must equal today's (`WorkerMain`, `ApiServerMain`). Keep reading them from the same environment variable names so `docker-compose.yml` (`environment:` blocks) needs no change.
* `ApiApplication`: the `HttpServer`/`Router`/`Http`/`FileServer` code stays as is. Wrap its start in a `SmartLifecycle` or `ApplicationRunner` that receives the injected `CamundaClient` instead of calling `Camunda.newClient`. Do not touch `Router`, `FileServer`, `GoJson`, `GoFmt` (they exist to reproduce Go behaviour and have golden tests).
* `Log.fatal` (uses `Runtime.halt(1)`) stays for the same fatal paths; on Spring startup failure let the exception propagate (non-zero exit).

### 4.5 Camunda client configuration through the starter

Create `src/main/resources/application.yaml` (the existing `camunda/application.yaml` is the *cluster's* config, mounted by docker-compose; do not edit or rename it).

Map today's settings (from `Camunda.newClient`, `JobWorkers`) onto starter properties. The property names below are the intent; **[Unverified]** confirm every key against the starter's configuration metadata for the resolved version before use:

| Today | Starter configuration (verify keys) |
|---|---|
| `CAMUNDA_REST_ADDRESS` (default `http://localhost:8080`) | `camunda.client.rest-address: ${CAMUNDA_REST_ADDRESS:http://localhost:8080}` and a mode appropriate for a self-managed cluster with no gRPC; no gRPC address should be needed |
| `preferRestOverGrpc(true)` | the starter's REST-preference switch (REST is the documented default [Web]); confirm gRPC is not attempted |
| basic auth when `CAMUNDA_USERNAME` is set (password may be empty) | `camunda.client.auth.*` basic credentials from `CAMUNDA_USERNAME` / `CAMUNDA_PASSWORD`; the unauthenticated case (`unprotectedApi: true` in `camunda/application.yaml`) must still work |
| `defaultRequestTimeout` 60 s | the client request-timeout property |
| `applyEnvironmentVariableOverrides(false)` | The starter binds Spring properties; with Spring relaxed binding, environment variables such as `CAMUNDA_CLIENT_*` can override YAML. Accept this, and document it |
| Worker defaults: `maxJobsActive 16`, lock `timeout 60 s`, `requestTimeout 20 s`, `pollInterval 200 ms`, `streamEnabled false`, `backoffSupplier 2 s` | `camunda.client.worker.defaults.*` (max-jobs-active, timeout, request-timeout, poll-interval, stream-enabled) rather than annotation attributes, so every `@JobWorker` stays `@JobWorker(type = CONSTANT)` |
| Worker name `WORKER_NAME` (default `capital-call-java-worker`) | `camunda.client.worker.defaults.name` **[Unverified]** (the starter normally names a worker `beanName#methodName`; a single default name changes that unless overridden) |
| Job handler executor on virtual threads | Optional. If the starter exposes an executor/threads setting, use virtual threads; otherwise accept the starter default and note it |

Keep `status`, `isNotFound` and `message` from `Camunda.java` (renamed `CamundaErrors` if you like); `ApiServerMain` uses them for 404→409 mapping and `apiErr`. Remove `newClient`, `waitReady`, `deploy` from it.

### 4.6 BPMN deployment (keep the BPMN files where they are)

* Leave `bpmn/` in place: `Dockerfile` copies it to `/app/bpmn`, `Makefile deploy` and `tools/gen_bpmn.py` reference it, and the analyser finds it anywhere under the root (A14).
* Recommended: a `@Component` `BpmnDeployer implements ApplicationRunner` in the worker role that reproduces `WorkerMain`: honour `DEPLOY_ON_START` (default `true`), read `BPMN_DIR` (default `./bpmn`), deploy `*.bpmn` then `*.form` (sorted by name) in **one** deployment through the injected `CamundaClient`, up to 20 attempts 3 s apart, same log lines. It also keeps the "wait for Camunda" loop (the starter does not wait).
* Alternative: the starter's `@Deployment(resources = ...)` annotation. **[Unverified]** whether it supports `file:` patterns driven by an environment variable and whether it retries; if it does not, keep the runner. Do not move the BPMN into `src/main/resources` unless the Dockerfile/Makefile/gen script are changed too.

### 4.7 API server entry points (`ApiServerMain`)

* Inject the starter's `CamundaClient` (remove `Camunda.newClient`).
* Analyser-friendly (optional but recommended; affects only the Shinro output, not the behaviour):
  * Use literal ids at the three start sites: `.bpmnProcessId("capital-call")`, `.messageName("lpCommitment")`, and so on (A11). Keep the `Models.*` constants for the other uses and add one test asserting they are equal to the literals.
  * Pass a DTO: `.variables(in)` (a `CapitalCallInput` local) instead of `.variables(GoJson.marshal(in))` so start variables are discoverable. **Behaviour risk:** this swaps the serialization path from `GoJson` (Go-style numbers) to the client's mapper. Only do it if the variable diff in 6.2 shows no change; otherwise leave `GoJson.marshal(...)` and accept empty `startVariables` in the analyser output.
* Do not change the 12 routes, the status codes, or the error bodies.

### 4.8 Retry, back-off and error text (the one real semantic gap)

Today `JobWorkers.execute` guarantees: failure → `retries = max(0, job.retries()-1)`, `retryBackoff = 5 s`, error message = exception message with a `"panic: "` prefix for unchecked exceptions (`errorText`, `Errors.unwrap`), incident `JOB_NO_RETRIES` after the BPMN's 3 attempts. The starter's default exception handling is **[Unverified]** (the assessment found no authoritative source in documentation search): the agent must read the starter jar/source and determine, for the resolved version, what an exception thrown from a `@JobWorker` method does: retries, back-off, message.

Then:

1. If the defaults already give `retries-1` and the back-off/message difference is acceptable to the owner, delete `errorText`, `JobWorkersTest.errorTextDistinguishesReturnedErrorsFromPanics`, and record the difference in the final report.
2. Otherwise, register the starter's exception-handling extension point as a **bean** (the starter exposes a command-exception-handling strategy; confirm the exact interface name and package in the jar) that fails the job with `max(0, retries-1)`, a 5 s back-off and the same message rule. Keep this in one `@Configuration` class; handler methods must stay free of try/catch wrappers. Move the `errorText` test to that class.

Decision owner: the project owner (see section 7, Q3).

### 4.9 Tests

* `JobWorkersTest` (tests `JobWorkers.errorText`) is replaced per 4.8.
* Add a test that guarantees the three-way match **BPMN task types = `ProcessConstants` values = `@JobWorker` types**: parse `bpmn/*.bpmn` for `zeebe:taskDefinition type="…"`, reflect over the worker classes for methods carrying the annotation (use `JobWorker.class`, never the literal text `@JobWorker`, A12), assert the sets are equal and each type appears exactly once (14).
* Add a Spring context test (no Camunda connection, no database) that proves the worker beans load and that `apiserver` role contains no job-worker methods.
* Keep `HandlersTest`, `ReportWriterGoldenTest`, `DbTest`, `GoJsonTest`, `GoFmtTest`, `RouterTest`, `MockServicesTest` green. The golden files must not change.
* Test code must not contain `@JobWorker` or `.newWorker(` text (A12).

### 4.10 Docker, Makefile, compose, docs

* `Dockerfile`: jar name unchanged; entrypoint `java $JAVA_OPTS -jar /app/app.jar "$SERVICE" "$@"` still works with the Spring Boot repackaged jar. Re-evaluate `-Xmx256m` (Spring adds footprint; **[Unverified]**). Keep `COPY bpmn /app/bpmn`.
* `docker-compose.yml`: no env changes expected. If the starter needs an explicit mode/address variable, add it in the shared `app-env` block and nowhere else.
* `Makefile`: unchanged targets. `make demo` keeps working because the demodriver role does not start Spring.
* `README.md`: describe the starter, the roles, and the constants class. The README currently links to a non-existent `GO_TO_JAVA_MIGRATION_REPORT.md`; fix or remove that link.
* Do not commit `target/`, `reports/*` or `camunda-data/`.

---

## 5. Do not change

`bpmn/*`, `db/*`, `services/ml-scorer/*`, `tools/gen_bpmn.py`, `camunda/application.yaml`, `src/test/resources/golden/*`, `models/*` field names/annotations (JSON names and order are part of the contract), SQL text, `GoJson`, `GoFmt`, `Router`, `FileServer`, `Http`, `Pdf`, `ReportWriter` logic, mock services, demo driver, the public API routes and their responses. Never edit anything under `shinro-app/shinro`.

---

## 6. Verification (do all of it; report what could not be run)

### 6.1 Static checks

1. `mvn -B test` passes. `mvn -B -DskipTests package` produces `target/camunda-assetmgmt.jar`; `java -jar … worker` starts Spring and registers exactly 14 workers (log line per worker; no more, no fewer).
2. Text guards (all must return nothing in `src/`): `grep -rn "newWorker(" src`; `grep -rn "this::" src/main/java/com/vishworks/assetmgmt/handlers src/main/java/com/vishworks/assetmgmt/worker`; `grep -rn "Map<String, *Handler>" src`; every `@JobWorker` line must be `@JobWorker(type = ProcessConstants.JOB_…)` and the string `@JobWorker` must appear exactly 14 times in all `.java` files combined.
3. Dependency hygiene: a single SLF4J binding; a single Jackson 2 version; no Logback jar in the final jar.

### 6.2 Behavioural parity against the baseline (run on both the old and the new build, same Camunda 8.10.9 + Postgres)

Run a full capital call with the same input and compare: database rows (`capital_calls`, `capital_call_lps`, `call_events`, ledger tables) ignoring generated ids/timestamps; process variables read from the engine (Operate/`/v2/variables/search`), especially numeric values and `null` handling (starter serialization vs the former `GoJson.marshal`); the JSON report in `reports/` (must be byte-identical to the baseline for the same input; the golden test covers the writer); the 28-request API replay if the earlier script is available; failure path: stop the ML scorer, expect three attempts then an incident `JOB_NO_RETRIES` on `predictDefaultRisk`'s element, call status stuck at `aggregated`, recovery after resetting retries; message path: LP response before the process reaches the receive step still works (buffered publication), second GP decision → 409, cancel/force-settlement after completion → 409; the GP user task still appears in Tasklist with `gp-approval-form`.

### 6.3 Analyser acceptance (read-only use of the Shinro tool)

Build per its README (`cd shinro/tools/camunda/code-analyzer && make build`; needs Go 1.26 and a C compiler because the tree-sitter grammars use cgo; **[Unverified]** whether a prebuilt binary exists on the machine; do not install anything into the Shinro repo). Then:

```
camunda-code-analyzer --input /Users/suhan/Desktop/repos/asst-mgmt/cmn-springboot-asstmgt > /tmp/findings.json
```

Expected (derived from the analyser source, **not yet observed**): exit code `0`; `summary.workerCount = 14`; `distinctJobTypes = 14`; `unresolvedJobTypes = 0`; `coverageGapCount = 0`; each worker has `jobTypeResolution: "static"`, `registration.style: "annotation"`, `autoComplete: true`; `entryPoints` contains the capital-call start (REST) and the message publications; no `ambiguous-class-name` gap. Many workers will legitimately land in `classificationNeeded[]` (they reach Postgres/HTTP sinks); that is correct behaviour, not a failure. Attach the findings summary to the final report. If the exit code is 3, read `gaps[]` and fix the code shape (usually a stray `@JobWorker` or `.newWorker(` text, or a duplicate class simple name), not the analyser.

---

## 7. Open questions and risks (answers needed from the owner; agent proceeds with the stated default and records it)

| # | Question / risk | Default if no answer |
|---|---|---|
| Q1 | The brief names `io.camunda.spring.client.annotation.JobWorker`; documentation search suggests the 8.8+ package is `io.camunda.client.annotation`. | Use whatever the resolved jar contains. The analyser accepts both. |
| Q2 | Starter version/flavour for a 8.10.9 cluster (`camunda-spring-boot-starter` vs `-3-`/`-4-` aliases; Spring Boot 3.5 vs 4.x). | Newest published 8.10.x of the default starter, with the Spring Boot version the starter itself declares. |
| Q3 | Is the current 5 s back-off and `"panic: "` message prefix a requirement, or an artefact of the Go port? | Preserve both through a strategy bean (4.8, option 2). |
| Q4 | Should the API server also move to the starter (shared client configuration), or keep a hand-built client? | Move it (4.4, 4.7) so there is one client configuration path. |
| Q5 | `@Deployment` annotation vs the explicit deploy runner. | Explicit runner (keeps retry and `BPMN_DIR`). |
| R1 | Worker names change from one `WORKER_NAME` to per-method defaults unless configured. | Configure the default name. |
| R2 | Process-variable serialization now goes through the starter's mapper instead of `GoJson` (floating-point formatting, `null` handling). | Prove equality with the 6.2 variable diff; stop and report if different. |
| R3 | Jackson 2 vs 3 and Logback vs Log4j conflicts when Spring Boot 4 is introduced. | Section 4.1 steps, verified with `mvn dependency:tree`. |
| R4 | Memory: Spring plus the shaded dependency set under `-Xmx256m`. | Measure at idle and during a demo run; raise `JAVA_OPTS` only with evidence. |
| R5 | The analyser was not executed in this assessment; all expectations in 2 and 6.3 come from reading its Go source and fixtures (`testdata/java-springzeebe`, `jobtype-method-default-java`, `sink-chain-java`, `a1-local-sink-java`). | Run it (6.3) and treat its output as the authority. |

## 8. Definition of done

1. Exactly 14 `@JobWorker(type = ProcessConstants.JOB_…)` methods on `@Component` beans; no dispatch map, loop, or method references; `JobWorkers.java` deleted.
2. BPMN files untouched and still the deployed source.
3. `mvn -B test` green; Docker image builds; `make up` + a full demo run complete.
4. Parity evidence from 6.2 attached, with every difference listed and classified.
5. Analyser run from 6.3 attached: exit 0, 14 workers, 0 unresolved job types, 0 coverage gaps.
6. A short change report listing: starter version, annotation package actually used, how retry/back-off was handled, decisions taken for Q1–Q5, and everything not verified.
