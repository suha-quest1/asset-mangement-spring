package com.vishworks.assetmgmt.camunda;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class JobWorkersTest {
  @Test
  void errorTextDistinguishesReturnedErrorsFromPanics() {
    assertEquals("ml-scorer: boom", JobWorkers.errorText(new JobWorkers.JobException("ml-scorer: boom")));
    assertEquals("ERROR: x", JobWorkers.errorText(new SQLException("ERROR: x")));
    assertEquals("panic: oops", JobWorkers.errorText(new IllegalStateException("oops")));
    assertEquals("panic: NullPointerException", JobWorkers.errorText(new NullPointerException()));
  }
}
