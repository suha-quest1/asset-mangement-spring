package com.vishworks.assetmgmt.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vishworks.assetmgmt.api.ApiApplication;
import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.handlers.ReportWriter;
import com.vishworks.assetmgmt.service.CallRepository;
import com.vishworks.assetmgmt.service.CapitalCallService;
import com.vishworks.assetmgmt.service.CreditFacilityClient;
import com.vishworks.assetmgmt.service.HttpJson;
import com.vishworks.assetmgmt.service.NotificationClient;
import com.vishworks.assetmgmt.service.RiskScorerClient;
import com.zaxxer.hikari.HikariDataSource;
import io.camunda.client.annotation.JobWorker;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Wiring checks of the two Spring roles without a Camunda cluster or a database: the worker
 * beans and their services are created from a plain Spring context, and the API role's
 * component scan contains no job-worker method.
 */
class RoleContextTest {

  /** A Db whose pool never connects (HikariDataSource() is lazy). */
  static Db lazyDb() throws Exception {
    Constructor<Db> c = Db.class.getDeclaredConstructor(HikariDataSource.class);
    c.setAccessible(true);
    return c.newInstance(new HikariDataSource());
  }

  static int jobWorkerMethods(Class<?> c) {
    int n = 0;
    for (Method m : c.getDeclaredMethods()) {
      if (m.isAnnotationPresent(JobWorker.class)) {
        n++;
      }
    }
    return n;
  }

  @Test
  void workerBeansAndServicesLoad() throws Exception {
    Db db = lazyDb();
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      ctx.registerBean(Db.class, () -> db);
      ctx.register(AppProperties.class, WorkerConfiguration.class, HttpJson.class, NotificationClient.class,
          RiskScorerClient.class, CreditFacilityClient.class, CallRepository.class, ReportWriter.class,
          CapitalCallService.class, CapitalCallWorkers.class, LpResponseWorkers.class);
      ctx.refresh();
      int methods = 0;
      for (String name : ctx.getBeanDefinitionNames()) {
        methods += jobWorkerMethods(ctx.getType(name));
      }
      assertEquals(14, methods);
      assertTrue(ctx.getBean(CapitalCallWorkers.class) != null);
      assertTrue(ctx.getBean(LpResponseWorkers.class) != null);
    } finally {
      db.close();
    }
  }

  @Test
  void apiRoleScanContainsNoJobWorkers() throws Exception {
    ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
    scan.addIncludeFilter(new AnnotationTypeFilter(Component.class));
    String[] packages = ApiApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages();
    List<String> components = new ArrayList<>();
    for (String pkg : packages) {
      for (BeanDefinition bd : scan.findCandidateComponents(pkg)) {
        components.add(bd.getBeanClassName());
        assertEquals(0, jobWorkerMethods(Class.forName(bd.getBeanClassName())), bd.getBeanClassName());
      }
    }
    assertTrue(components.contains("com.vishworks.assetmgmt.api.ApiServerRunner"), components.toString());
    for (String c : components) {
      assertTrue(!c.startsWith("com.vishworks.assetmgmt.worker.") && !c.startsWith("com.vishworks.assetmgmt.camunda."), c);
    }
  }
}
