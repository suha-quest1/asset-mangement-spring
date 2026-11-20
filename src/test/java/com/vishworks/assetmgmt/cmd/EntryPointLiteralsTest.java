package com.vishworks.assetmgmt.cmd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vishworks.assetmgmt.models.Models;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * ApiServerMain uses string literals for the process id and the published message name (so
 * static analysis can read them); they must stay equal to the Models constants.
 */
class EntryPointLiteralsTest {
  @Test
  void literalsMatchModelsConstants() throws Exception {
    assertEquals("capital-call", Models.PROCESS_CAPITAL_CALL);
    assertEquals("lpCommitment", Models.MSG_LP_COMMITMENT);
    String src = Files.readString(Path.of("src/main/java/com/vishworks/assetmgmt/cmd/ApiServerMain.java"));
    assertTrue(src.contains(".bpmnProcessId(\"capital-call\")"));
    assertTrue(src.contains(".messageName(\"lpCommitment\")"));
  }
}
