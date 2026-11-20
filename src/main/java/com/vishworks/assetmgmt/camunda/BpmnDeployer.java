package com.vishworks.assetmgmt.camunda;

import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.response.DeploymentEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Worker start-up: waits for Camunda, then deploys the process models and the form from
 * BPMN_DIR (default ./bpmn) in one deployment, with up to 20 attempts 3 s apart. Camunda
 * de-duplicates unchanged resources. Disabled with DEPLOY_ON_START=false.
 */
@Component
@Order(1)
public class BpmnDeployer implements ApplicationRunner {
  private final CamundaClient client;
  private final AppProperties props;

  public BpmnDeployer(CamundaClient client, AppProperties props) {
    this.client = client;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    Log.printf("waiting for Camunda at %s ...", props.camundaRestAddress().replaceAll("/+$", ""));
    waitReady();
    if (!props.deployOnStart()) {
      return;
    }
    Path dir = Path.of(props.bpmnDir());
    List<Path> files = new ArrayList<>(glob(dir, ".bpmn"));
    files.addAll(glob(dir, ".form"));
    List<String> names = files.stream().map(Path::toString).toList();
    for (int attempt = 1; ; attempt++) {
      try {
        DeploymentEvent res = deploy(files);
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

  /** Blocks until the cluster answers the topology endpoint (WaitReady). */
  void waitReady() throws InterruptedException {
    while (true) {
      try {
        client.newTopologyRequest().send().join();
        return;
      } catch (RuntimeException e) {
        Thread.sleep(3000);
      }
    }
  }

  /** Uploads BPMN / DMN / form files in a single deployment (Deploy). */
  DeploymentEvent deploy(List<Path> files) throws Exception {
    if (files.isEmpty()) {
      throw new IllegalArgumentException("no resources to deploy");
    }
    DeployResourceCommandStep1 step = client.newDeployResourceCommand();
    DeployResourceCommandStep1.DeployResourceCommandStep2 cmd = null;
    for (Path f : files) {
      byte[] data = Files.readAllBytes(f);
      String name = f.getFileName().toString();
      cmd = cmd == null ? step.addResourceBytes(data, name) : cmd.addResourceBytes(data, name);
    }
    return cmd.send().join();
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
