package com.vishworks.assetmgmt.api;

import com.vishworks.assetmgmt.cmd.ApiServerMain;
import com.vishworks.assetmgmt.db.Db;
import io.camunda.client.CamundaClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Starts the API server with the Spring-managed database pool and Camunda client. */
@Component
public class ApiServerRunner implements ApplicationRunner {
  private final Db db;
  private final CamundaClient camundaClient;

  public ApiServerRunner(Db db, CamundaClient camundaClient) {
    this.db = db;
    this.camundaClient = camundaClient;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    ApiServerMain.serve(db, camundaClient);
  }
}
