package com.vishworks.assetmgmt.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vishworks.assetmgmt.camunda.ProcessConstants;
import io.camunda.client.annotation.JobWorker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * The three sources of job types must agree: the BPMN service tasks, the ProcessConstants
 * values and the job-worker annotations on the worker components (one method per type).
 */
class JobWorkerTypesTest {

  static final List<Class<?>> WORKER_CLASSES = List.of(CapitalCallWorkers.class, LpResponseWorkers.class);

  static List<String> bpmnTypes() throws Exception {
    Pattern p = Pattern.compile("zeebe:taskDefinition type=\"([^\"]+)\"");
    List<String> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(Path.of("bpmn"))) {
      for (Path f : files.filter(x -> x.toString().endsWith(".bpmn")).toList()) {
        Matcher m = p.matcher(Files.readString(f));
        while (m.find()) {
          out.add(m.group(1));
        }
      }
    }
    return out;
  }

  static List<String> constantValues() throws Exception {
    List<String> out = new ArrayList<>();
    for (Field f : ProcessConstants.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        out.add((String) f.get(null));
      }
    }
    return out;
  }

  static List<String> annotatedTypes() {
    List<String> out = new ArrayList<>();
    for (Class<?> c : WORKER_CLASSES) {
      for (Method m : c.getDeclaredMethods()) {
        JobWorker w = m.getAnnotation(JobWorker.class);
        if (w != null) {
          out.add(w.type());
        }
      }
    }
    return out;
  }

  @Test
  void bpmnConstantsAndWorkersAgree() throws Exception {
    List<String> bpmn = bpmnTypes();
    List<String> constants = constantValues();
    List<String> workers = annotatedTypes();
    // 18 service tasks share 14 job types (autoFollowUp x3, recordLPResponse x2 and
    // finalizeResponses x2 differ only in their custom headers).
    assertEquals(18, bpmn.size(), "service tasks in bpmn/*.bpmn");
    assertEquals(14, new TreeSet<>(bpmn).size(), "distinct job types in bpmn/*.bpmn");
    assertEquals(14, constants.size());
    assertEquals(14, workers.size(), "one job worker method per job type");
    assertEquals(new TreeSet<>(bpmn), new TreeSet<>(constants));
    assertEquals(new TreeSet<>(bpmn), new TreeSet<>(workers));
    assertEquals(14, new TreeSet<>(workers).size(), "no job type is registered twice");
  }

  @Test
  void workerClassesAreComponents() {
    for (Class<?> c : WORKER_CLASSES) {
      assertTrue(c.isAnnotationPresent(Component.class), c.getName());
    }
  }
}
