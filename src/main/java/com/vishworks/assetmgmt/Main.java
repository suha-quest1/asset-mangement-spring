package com.vishworks.assetmgmt;

import com.vishworks.assetmgmt.api.ApiApplication;
import com.vishworks.assetmgmt.cmd.DemoDriverMain;
import com.vishworks.assetmgmt.cmd.MockCreditFacilityMain;
import com.vishworks.assetmgmt.cmd.MockSesMain;
import com.vishworks.assetmgmt.worker.WorkerApplication;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Single entry point of the executable jar. The first argument selects the service, mirroring
 * the former Go binaries cmd/worker, cmd/apiserver, cmd/mockses, cmd/mockcreditfacility and
 * cmd/demodriver; remaining arguments are passed through. The worker and apiserver roles run
 * as Spring Boot applications (Camunda Spring Boot Starter); the mocks and the demo driver
 * stay plain Java programs.
 *
 * <pre>java -jar camunda-assetmgmt.jar worker|apiserver|mockses|mockcreditfacility|demodriver [args]</pre>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    String service = args.length > 0 ? args[0] : System.getenv().getOrDefault("SERVICE", "");
    String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
    switch (service) {
      case "worker" -> runSpring(WorkerApplication.class, rest);
      case "apiserver" -> runSpring(ApiApplication.class, rest);
      case "mockses" -> MockSesMain.main(rest);
      case "mockcreditfacility" -> MockCreditFacilityMain.main(rest);
      case "demodriver" -> DemoDriverMain.main(rest);
      default -> {
        System.err.println("usage: java -jar camunda-assetmgmt.jar worker|apiserver|mockses|mockcreditfacility|demodriver [args]");
        System.exit(2);
      }
    }
  }

  /** Starts one Spring Boot role (no embedded web server; the JVM is kept alive). */
  static void runSpring(Class<?> application, String[] args) {
    new SpringApplicationBuilder(application)
        .web(WebApplicationType.NONE)
        .run(args);
  }
}
