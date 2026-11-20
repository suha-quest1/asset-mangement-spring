package com.vishworks.assetmgmt.util;

import java.util.List;

/** Go nil-slice helpers. */
public final class Lists {
  private Lists() {}

  /** A nil slice ranges like an empty one. */
  public static <T> List<T> nz(List<T> l) {
    return l == null ? List.of() : l;
  }
}
