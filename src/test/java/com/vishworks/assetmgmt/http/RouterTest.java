package com.vishworks.assetmgmt.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouterTest {
  @Test
  void cleanPath() {
    assertEquals("/", Router.cleanPath(""));
    assertEquals("/a/b", Router.cleanPath("/a//b"));
    assertEquals("/b", Router.cleanPath("/a/../b"));
    assertEquals("/reports/", Router.cleanPath("/reports/"));
    assertEquals("/a/", Router.cleanPath("/a/./"));
  }

  @Test
  void unescapeSegments() {
    assertEquals("CC-1", Router.unescape("CC%2D1"));
    assertEquals("a b", Router.unescape("a%20b"));
    assertEquals("a+b", Router.unescape("a+b"));
    assertEquals("100%", Router.unescape("100%"));
  }
}
