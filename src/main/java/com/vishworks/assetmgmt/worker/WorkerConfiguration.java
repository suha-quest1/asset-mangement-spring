package com.vishworks.assetmgmt.worker;

import com.vishworks.assetmgmt.camunda.Camunda;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Log;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.BackoffSupplier;
import io.camunda.client.api.worker.JobExceptionHandler;
import io.camunda.client.jobhandling.CamundaClientExecutorService;
import io.camunda.client.jobhandling.JobExceptionHandlerSupplier;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Job-worker runtime settings that are not plain properties (see application.yaml for the
 * worker defaults: 16 jobs, 60 s lock, 20 s long poll, 200 ms poll interval, 5 s retry back-off).
 */
@Configuration(proxyBeanMethods = false)
public class WorkerConfiguration {

  /** Outbound HTTP client of the job workers (20 s connect timeout). */
  @Bean
  public HttpClient httpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  }

  /**
   * Job handlers run on virtual threads: one per activated job, like the goroutine per job
   * in the Go worker loop. Scheduling (polling) keeps a single platform thread.
   */
  @Bean
  public CamundaClientExecutorService camundaClientExecutorService() {
    return new CamundaClientExecutorService(
        Executors.newScheduledThreadPool(1), true, Executors.newVirtualThreadPerTaskExecutor(), true);
  }

  /** Fixed 2 s pause after a failed poll (the Go worker slept 2 s after an activation error). */
  @Bean("backoffSupplier")
  public BackoffSupplier backoffSupplier() {
    return previousDelay -> 2000L;
  }

  /**
   * Failure handling of a job whose method threw: fail it with {@code retries - 1} (never
   * below 0) and the configured retry back-off (5 s), so Camunda raises an incident once the
   * retries are exhausted. The error message is the exception message, prefixed with
   * "panic: " for unexpected runtime failures, as before.
   */
  @Bean
  public JobExceptionHandlerSupplier jobExceptionHandlerSupplier() {
    return supplierContext -> (JobExceptionHandler) context -> {
      ActivatedJob job = context.getActivatedJob();
      String msg = errorText(context.getException());
      int retries = Math.max(0, job.getRetries() - 1);
      Log.printf("[worker] job %s (%s/%s) failed, retries left %d: %v", Long.toString(job.getKey()), job.getType(),
          job.getElementId(), retries, msg);
      try {
        context.getJobClient().newFailCommand(job.getKey())
            .retries(retries)
            .errorMessage(msg)
            .retryBackoff(supplierContext.retryBackoff())
            .send()
            .join();
      } catch (RuntimeException fe) {
        Log.printf("[worker] fail job %s: %v", Long.toString(job.getKey()), Camunda.message(fe));
      }
    };
  }

  /** err.Error() for returned errors; "panic: …" for unexpected runtime failures (safeCall). */
  static String errorText(Throwable t) {
    t = Errors.unwrap(t);
    // The starter wraps an Error thrown by a job method in a RuntimeException.
    if (t instanceof RuntimeException && t.getCause() instanceof Error
        && String.valueOf(t.getMessage()).startsWith("Failed to invoke method: ")) {
      t = t.getCause();
    }
    if (t instanceof RuntimeException || t instanceof Error) {
      return "panic: " + Errors.message(t);
    }
    return Errors.message(t);
  }
}
