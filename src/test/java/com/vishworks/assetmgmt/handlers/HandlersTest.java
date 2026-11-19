package com.vishworks.assetmgmt.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HandlersTest {
  @Test
  void isoSecondsHasAOneSecondFloor() {
    assertEquals("PT15S", Handlers.isoSeconds(15));
    assertEquals("PT1S", Handlers.isoSeconds(0));
    assertEquals("PT1S", Handlers.isoSeconds(-50));
    assertEquals("PT259200S", Handlers.isoSeconds(3 * 86400));
  }

  @Test
  void transportErrorsAreWordedLikeGoNetHttp() {
    assertEquals("dial tcp ml-scorer:5001: connect: connection refused",
        Handlers.netReason("http://ml-scorer:5001/score", new java.net.ConnectException()));
    assertEquals("dial tcp: lookup nohost: no such host",
        Handlers.netReason("http://nohost:8082/drawdown", new java.io.IOException(new java.nio.channels.UnresolvedAddressException())));
    assertEquals("context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
        Handlers.netReason("http://x/y", new java.net.http.HttpTimeoutException("request timed out")));
  }

  @Test
  void atoiIgnoresErrors() {
    assertEquals(2, Handlers.atoi("2"));
    assertEquals(0, Handlers.atoi(""));
    assertEquals(0, Handlers.atoi("x"));
  }
}
