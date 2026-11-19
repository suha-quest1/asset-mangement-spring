package com.vishworks.assetmgmt.util;

/** Environment lookup with the same semantics as the Go helper env(k, d): empty counts as unset. */
public final class Env {
  private Env() {}

  public static String get(String key, String def) {
    String v = System.getenv(key);
    return (v != null && !v.isEmpty()) ? v : def;
  }
}
