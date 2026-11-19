package com.vishworks.assetmgmt.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Equivalent of Go's standard "log" package as used by the original services:
 * log.Printf / log.Fatal. Output format (date time message, on stderr) is configured
 * in log4j2.xml; format strings use Go fmt semantics via {@link GoFmt#sprintf}.
 */
public final class Log {
  private static final Logger LOG = LoggerFactory.getLogger("app");

  private Log() {}

  public static void printf(String format, Object... args) {
    LOG.info(GoFmt.sprintf(format, args));
  }

  /** log.Fatal: print and exit with status 1. */
  public static void fatal(Object err) {
    LOG.error(GoFmt.v(err instanceof Throwable t ? Errors.message(t) : err));
    org.apache.logging.log4j.LogManager.shutdown();
    Runtime.getRuntime().halt(1);
  }

  public static void fatalf(String format, Object... args) {
    fatal(GoFmt.sprintf(format, args));
  }
}
