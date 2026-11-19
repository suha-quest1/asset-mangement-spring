package com.vishworks.assetmgmt.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Helpers to turn Java exceptions into the error strings Go's err.Error() produced. */
public final class Errors {
  private Errors() {}

  /** Removes CompletionException/ExecutionException wrappers added by futures. */
  public static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  /** A human readable message for an exception (never null). */
  public static String message(Throwable t) {
    t = unwrap(t);
    String m = t.getMessage();
    if (m == null || m.isBlank()) {
      Throwable c = t.getCause();
      if (c != null && c != t) {
        return t.getClass().getSimpleName() + ": " + message(c);
      }
      return t.getClass().getSimpleName();
    }
    return m;
  }

  /** Finds the first cause of the given type in the chain, or null. */
  public static <T extends Throwable> T find(Throwable t, Class<T> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) return type.cast(c);
      if (c.getCause() == c) break;
    }
    return null;
  }
}
