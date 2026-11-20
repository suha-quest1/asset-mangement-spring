package com.vishworks.assetmgmt.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vishworks.assetmgmt.service.ServiceException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class WorkerConfigurationTest {
  @Test
  void errorTextDistinguishesReturnedErrorsFromPanics() {
    assertEquals("ml-scorer: boom", WorkerConfiguration.errorText(new ServiceException("ml-scorer: boom")));
    assertEquals("ERROR: x", WorkerConfiguration.errorText(new SQLException("ERROR: x")));
    assertEquals("panic: oops", WorkerConfiguration.errorText(new IllegalStateException("oops")));
    assertEquals("panic: NullPointerException", WorkerConfiguration.errorText(new NullPointerException()));
  }

  @Test
  void anErrorWrappedByTheStarterIsReportedAsAPanic() {
    RuntimeException wrapped = new RuntimeException("Failed to invoke method: x", new StackOverflowError("deep"));
    assertEquals("panic: deep", WorkerConfiguration.errorText(wrapped));
  }
}
