package com.vishworks.assetmgmt.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vishworks.assetmgmt.service.HttpJson;
import com.vishworks.assetmgmt.worker.JobValues;
import org.junit.jupiter.api.Test;

/** The helpers of the former Handlers class, now in JobValues and HttpJson. */
class HandlersTest {
  @Test
  void isoSecondsHasAOneSecondFloor() {
    assertEquals("PT15S", JobValues.isoSeconds(15));
    assertEquals("PT1S", JobValues.isoSeconds(0));
    assertEquals("PT1S", JobValues.isoSeconds(-50));
    assertEquals("PT259200S", JobValues.isoSeconds(3 * 86400));
  }

  @Test
  void transportErrorsAreWordedLikeGoNetHttp() {
    assertEquals("dial tcp ml-scorer:5001: connect: connection refused",
        HttpJson.netReason("http://ml-scorer:5001/score", new java.net.ConnectException()));
    assertEquals("dial tcp: lookup nohost: no such host",
        HttpJson.netReason("http://nohost:8082/drawdown", new java.io.IOException(new java.nio.channels.UnresolvedAddressException())));
    assertEquals("context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
        HttpJson.netReason("http://x/y", new java.net.http.HttpTimeoutException("request timed out")));
  }

  @Test
  void atoiIgnoresErrors() {
    assertEquals(2, JobValues.atoi("2"));
    assertEquals(0, JobValues.atoi(""));
    assertEquals(0, JobValues.atoi("x"));
  }
}
