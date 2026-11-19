package com.vishworks.assetmgmt.cmd;

import static com.vishworks.assetmgmt.util.Env.get;

import com.vishworks.assetmgmt.camunda.Camunda;
import com.vishworks.assetmgmt.camunda.JobWorkers;
import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.handlers.Handlers;
import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Deploys the BPMN/form resources to Camunda and runs the job workers that implement every
 * service task of the capital-call processes (port of cmd/worker).
 */
public final class WorkerMain {
  private WorkerMain() {}

  public static void main(String[] args) throws Exception {
    CountDownLatch stop = new CountDownLatch(1);
    CamundaClient client = Camunda.newClient(get("CAMUNDA_REST_ADDRESS", "http://localhost:8080"));
    Log.printf("waiting for Camunda at %s ...", get("CAMUNDA_REST_ADDRESS", "http://localhost:8080").replaceAll("/+$", ""));
    Camunda.waitReady(client);

    // Deploy process models + form (Camunda de-duplicates unchanged resources).
    if (get("DEPLOY_ON_START", "true").equals("true")) {
      Path dir = Path.of(get("BPMN_DIR", "./bpmn"));
      List<Path> files = new ArrayList<>(glob(dir, ".bpmn"));
      files.addAll(glob(dir, ".form"));
      List<String> names = files.stream().map(Path::toString).toList();
      for (int attempt = 1; ; attempt++) {
        try {
          DeploymentEvent res = Camunda.deploy(client, files);
          Log.printf("deployed %v (deploymentKey=%v)", names, Long.toString(res.getKey()));
          break;
        } catch (Exception e) {
          if (attempt >= 20) {
            Log.fatalf("deploy failed: %v", Camunda.message(e));
          }
          Log.printf("deploy attempt %d failed: %v", attempt, Camunda.message(e));
          Thread.sleep(3000);
        }
      }
    }

    Db db;
    try {
      db = Db.connect(get("DATABASE_URL", "postgres://assetmgmt:assetmgmt@localhost:5433/assetmgmt?sslmode=disable"));
    } catch (Exception e) {
      Log.fatal(e);
      return;
    }

    Handlers h = new Handlers();
    h.db = db;
    h.sesUrl = get("SES_URL", "http://localhost:8081");
    h.mlScorerUrl = get("ML_SCORER_URL", "http://localhost:5001");
    h.creditUrl = get("CREDIT_FACILITY_URL", "http://localhost:8082");
    h.reportDir = get("REPORT_DIR", "./reports");
    h.portalUrl = get("PORTAL_PUBLIC_URL", "http://localhost:8090");
    h.camundaUrl = get("CAMUNDA_PUBLIC_URL", "http://localhost:8080");
    h.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    JobWorkers w = new JobWorkers(client, get("WORKER_NAME", "capital-call-java-worker"));
    h.register(w);
    w.start();
    Log.printf("job workers started");

    // SIGINT/SIGTERM: stop polling, then release the client and the pool.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      w.close();
      client.close();
      db.close();
      stop.countDown();
    }, "shutdown"));
    stop.await();
  }

  /** filepath.Glob(dir/*ext): sorted lexically. */
  static List<Path> glob(Path dir, String ext) throws Exception {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().endsWith(ext))
          .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
          .map(p -> dir.normalize().resolve(p.getFileName().toString()))
          .toList();
    }
  }
}
