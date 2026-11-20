package com.vishworks.assetmgmt.worker;

import io.camunda.client.api.response.ActivatedJob;
import java.util.Map;

/** Small value helpers shared by the job worker components. */
public final class JobValues {
  private JobValues() {}

  /** ISO-8601 duration in whole seconds with a one-second floor (BPMN timer expressions). */
  public static String isoSeconds(long sec) {
    if (sec < 1) {
      sec = 1;
    }
    return "PT" + sec + "S";
  }

  /** strconv.Atoi with the error ignored (0 on failure). */
  public static int atoi(String s) {
    try {
      return Integer.parseInt(s.startsWith("+") ? s.substring(1) : s);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** A custom header of the job's service task as string ("" when absent). */
  public static String header(ActivatedJob job, String name) {
    Map<String, String> h = job.getCustomHeaders();
    String v = h == null ? null : h.get(name);
    return v == null ? "" : v;
  }

  /** Process instance key as a string (the v2 API represents keys as strings). */
  public static String processInstanceKey(ActivatedJob job) {
    return Long.toString(job.getProcessInstanceKey());
  }
}
