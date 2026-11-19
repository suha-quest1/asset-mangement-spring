package com.vishworks.assetmgmt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Expected values were produced with Go 1.24 (fmt, strconv, math, time). */
class GoFmtTest {

  @Test
  void fixedRoundsTheExactBinaryValueHalfToEven() {
    assertEquals("0.12", GoFmt.fixed(0.125, 2)); // exact tie → even
    assertEquals("0.14", GoFmt.fixed(0.135, 2)); // 0.135 is slightly above the tie in binary
    assertEquals("2", GoFmt.fixed(2.5, 0));
    assertEquals("4", GoFmt.fixed(3.5, 0));
    assertEquals("44250000", GoFmt.fixed(44250000.0, 0));
    assertEquals("-0.00", GoFmt.fixed(-0.001, 2));
    assertEquals("11.49", GoFmt.fixed(11.49, 2));
  }

  @Test
  void roundIsHalfAwayFromZero() {
    assertEquals(3.0, GoFmt.round(2.5));
    assertEquals(-3.0, GoFmt.round(-2.5));
    assertEquals(4310344.83, GoFmt.round2(50_000_000.0 * 15_000_000 / 174_000_000));
    assertEquals(86250.0, GoFmt.round(5_750_000 * 0.015 * 100) / 100);
  }

  @Test
  void jsonFloatMatchesEncodingJson() {
    assertEquals("5000000", GoFmt.jsonFloat(5_000_000.0));
    assertEquals("0.82", GoFmt.jsonFloat(0.82));
    assertEquals("4310344.83", GoFmt.jsonFloat(4310344.83));
    assertEquals("0", GoFmt.jsonFloat(0.0));
    assertEquals("1e-7", GoFmt.jsonFloat(1e-7));
    assertEquals("1e+21", GoFmt.jsonFloat(1e21));
    assertEquals("100000000000000000000", GoFmt.jsonFloat(1e20));
    assertEquals("0.000001", GoFmt.jsonFloat(1e-6));
  }

  @Test
  void sprintfSubset() {
    assertEquals("Call issued for $50000000 across 10 LPs (deadline 10 days)",
        GoFmt.sprintf("Call issued for $%.0f across %d LPs (deadline %d days)", 50_000_000.0, 10, 10));
    assertEquals("Committed $44250000 of $50000000 — gap $5750000 (11.50%)",
        GoFmt.sprintf("Committed $%.0f of $%.0f — gap $%.0f (%.2f%%)", 44_250_000.0, 50_000_000.0, 5_750_000.0, 11.5));
    assertEquals("[worker] subscribed to job type \"notifyLPs\"", GoFmt.sprintf("[worker] subscribed to job type %q", "notifyLPs"));
    assertEquals("x                      |", GoFmt.sprintf("%-22s |", "x"));
    assertEquals("0000004346 00000 n ", GoFmt.sprintf("%010d 00000 n ", 4346));
    assertEquals("  0.82", GoFmt.sprintf("%6.2f", 0.82));
    assertEquals("HHI 0.1719, top risky [lp-08 lp-09]", GoFmt.sprintf("HHI %.4f, top risky %v", 0.1719, List.of("lp-08", "lp-09")));
    assertEquals("[MOCK-SES] <nil>                to=<nil> subject=<nil>",
        GoFmt.sprintf("[MOCK-SES] %-20v to=%v subject=%v", null, null, null));
    assertEquals("above 0.7 risk", GoFmt.sprintf("above %.1f risk", 0.7));
    assertEquals("%%EOF", GoFmt.sprintf("%%%%EOF"));
    assertEquals("[]", GoFmt.slice(null));
  }

  @Test
  void timeLayouts() {
    assertEquals("2026-11-18T19:27:03Z", GoFmt.rfc3339Utc(ZonedDateTime.of(2026, 11, 18, 19, 27, 3, 999_000_000, ZoneOffset.UTC).toInstant()));
    assertEquals("2026-11-18T19:27:03.12Z", GoFmt.rfc3339Nano(LocalDateTime.of(2026, 11, 18, 19, 27, 3, 120_000_000)));
    assertEquals("2026-11-18T19:27:03Z", GoFmt.rfc3339Nano(LocalDateTime.of(2026, 11, 18, 19, 27, 3)));
    assertEquals("2026-11-18T19:27:03.000001+05:30",
        GoFmt.rfc3339Nano(ZonedDateTime.of(2026, 11, 18, 19, 27, 3, 1000, ZoneOffset.ofHoursMinutes(5, 30))));
  }

  @Test
  void durationString() {
    assertEquals("57s", GoFmt.duration(Duration.ofSeconds(57)));
    assertEquals("1m3s", GoFmt.duration(Duration.ofSeconds(63)));
    assertEquals("2m0s", GoFmt.duration(Duration.ofSeconds(120)));
    assertEquals("1h0m5s", GoFmt.duration(Duration.ofSeconds(3605)));
    assertEquals("0s", GoFmt.duration(Duration.ZERO));
    assertEquals(Duration.ofSeconds(58), GoFmt.roundToSecond(Duration.ofMillis(57_500)));
    assertEquals(Duration.ofSeconds(57), GoFmt.roundToSecond(Duration.ofMillis(57_499)));
  }
}
