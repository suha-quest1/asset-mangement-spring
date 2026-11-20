package com.vishworks.assetmgmt.worker;

import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.annotation.value.JobWorkerValue;
import io.camunda.client.jobhandling.JobWorkerManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Logs the job types the starter opened job workers for (one line per job type, as the
 * worker did before), once the BPMN resources are deployed.
 */
@Component
@Order(2)
public class WorkerStartupLog implements ApplicationRunner {
  private final JobWorkerManager jobWorkerManager;

  public WorkerStartupLog(JobWorkerManager jobWorkerManager) {
    this.jobWorkerManager = jobWorkerManager;
  }

  @Override
  public void run(ApplicationArguments args) {
    for (JobWorkerValue w : jobWorkerManager.getJobWorkers().values()) {
      Log.printf("[worker] subscribed to job type %q", w.getType().value());
    }
    Log.printf("job workers started");
  }
}
