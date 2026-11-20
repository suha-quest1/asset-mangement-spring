package com.vishworks.assetmgmt.config;

import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.json.GoJson;
import io.camunda.client.api.JsonMapper;
import io.camunda.client.impl.CamundaObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans shared by the worker and the API server roles. */
@Configuration(proxyBeanMethods = false)
public class CommonConfiguration {

  /** Postgres pool (40 connection attempts, 3 s apart, as before). Closed by Spring on shutdown. */
  @Bean(destroyMethod = "close")
  public Db db(AppProperties props) throws Exception {
    return Db.connect(props.databaseUrl());
  }

  /**
   * The Camunda client's JSON mapper, built on a copy of {@link GoJson#MAPPER}: process
   * variables are written and read exactly as before (Go-style numbers, case-insensitive
   * decoding, unknown fields ignored), both for job results and getVariablesAsType.
   */
  @Bean(name = "camundaJsonMapper")
  public JsonMapper camundaJsonMapper() {
    return new CamundaObjectMapper(GoJson.MAPPER.copy());
  }
}
