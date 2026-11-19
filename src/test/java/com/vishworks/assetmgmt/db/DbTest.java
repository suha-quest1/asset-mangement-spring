package com.vishworks.assetmgmt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

class DbTest {
  @Test
  void convertsLibpqUrlToJdbc() {
    HikariConfig c = Db.toHikari("postgres://assetmgmt:assetmgmt@postgres:5432/assetmgmt?sslmode=disable");
    assertEquals("jdbc:postgresql://postgres:5432/assetmgmt?sslmode=disable", c.getJdbcUrl());
    assertEquals("assetmgmt", c.getUsername());
    assertEquals("assetmgmt", c.getPassword());
    HikariConfig d = Db.toHikari("postgresql://u:p%40ss@localhost/db");
    assertEquals("jdbc:postgresql://localhost:5432/db", d.getJdbcUrl());
    assertEquals("p@ss", d.getPassword());
    assertEquals("jdbc:postgresql://h/x", Db.toHikari("jdbc:postgresql://h/x").getJdbcUrl());
  }
}
