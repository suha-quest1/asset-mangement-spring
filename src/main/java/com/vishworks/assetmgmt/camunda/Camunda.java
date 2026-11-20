package com.vishworks.assetmgmt.camunda;

import com.vishworks.assetmgmt.json.GoJson;
import com.vishworks.assetmgmt.util.Errors;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ProblemException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error helpers for Camunda client calls (HTTP status, 404 detection, Go-style error text).
 * The {@link io.camunda.client.CamundaClient} itself is created and configured by the
 * Camunda Spring Boot Starter (see application.yaml).
 */
public final class Camunda {
  private Camunda() {}

  // ─── errors ───────────────────────────────────────────────────────────

  /** HTTP status of a failed Camunda API call, or -1 when it did not get an HTTP response. */
  public static int status(Throwable t) {
    ClientHttpException he = Errors.find(t, ClientHttpException.class);
    return he == null ? -1 : he.code();
  }

  /** IsNotFound: the API answered 404 (e.g. no message subscription to correlate with). */
  public static boolean isNotFound(Throwable t) {
    return status(t) == 404;
  }

  /**
   * Error text in the format of the Go APIError ("camunda api: HTTP 404: {body}"). For
   * RFC 9457 problem responses the body is re-serialised from the parsed problem detail
   * with the field order Camunda uses (type, title, status, detail, instance).
   */
  public static String message(Throwable t) {
    ProblemException pe = Errors.find(t, ProblemException.class);
    if (pe != null) {
      ProblemDetail d = pe.details();
      Map<String, Object> body = new LinkedHashMap<>();
      if (d != null) {
        if (d.getType() != null) body.put("type", d.getType().toString());
        if (d.getTitle() != null) body.put("title", d.getTitle());
        if (d.getStatus() != null) body.put("status", d.getStatus());
        if (d.getDetail() != null) body.put("detail", d.getDetail());
        if (d.getInstance() != null) body.put("instance", d.getInstance().toString());
      }
      return "camunda api: HTTP " + pe.code() + ": " + rawJson(body);
    }
    ClientHttpException he = Errors.find(t, ClientHttpException.class);
    if (he != null) {
      return "camunda api: HTTP " + he.code() + ": " + (he.reason() == null ? "" : he.reason());
    }
    return Errors.message(t);
  }

  /** Compact JSON without Go's HTML escaping (the Go client embedded the raw response body). */
  private static String rawJson(Map<String, Object> body) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    } catch (Exception e) {
      return GoJson.marshal(body);
    }
  }
}
