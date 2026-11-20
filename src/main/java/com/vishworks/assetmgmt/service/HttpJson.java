package com.vishworks.assetmgmt.service;

import static com.vishworks.assetmgmt.util.GoFmt.sprintf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.vishworks.assetmgmt.json.GoJson;
import com.vishworks.assetmgmt.util.Errors;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Outbound JSON-over-HTTP calls to the external services (SES, ML scorer, credit facility). */
@Component
public class HttpJson {
  private final HttpClient http;
  /** Timeout of every outbound HTTP call (http.Client{Timeout: 20s}). */
  private final Duration httpTimeout = Duration.ofSeconds(20);

  public HttpJson(HttpClient http) {
    this.http = http;
  }

  public <T> T postJSON(String url, Object in, TypeReference<T> out) throws IOException, InterruptedException {
    byte[] b = GoJson.marshal(in).getBytes(StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(httpTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(b))
        .build();
    HttpResponse<String> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // Same shape as Go's *url.Error: Post "<url>": <reason>
      throw new IOException("Post \"" + url + "\": " + netReason(url, e), e);
    }
    if (resp.statusCode() >= 300) {
      throw new JobErrorIO(sprintf("POST %s: HTTP %d: %s", url, resp.statusCode(), resp.body()));
    }
    if (out != null) {
      return GoJson.MAPPER.readValue(resp.body(), out);
    }
    return null;
  }

  /** The reason text Go's net/http client gives for common transport failures. */
  public static String netReason(String url, IOException e) {
    URI u = URI.create(url);
    String host = u.getHost() == null ? "" : u.getHost();
    int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
    if (Errors.find(e, java.nio.channels.UnresolvedAddressException.class) != null
        || Errors.find(e, java.net.UnknownHostException.class) != null) {
      return "dial tcp: lookup " + host + ": no such host";
    }
    if (Errors.find(e, java.net.http.HttpConnectTimeoutException.class) != null) {
      return "dial tcp " + host + ":" + port + ": i/o timeout";
    }
    if (Errors.find(e, java.net.http.HttpTimeoutException.class) != null) {
      return "context deadline exceeded (Client.Timeout exceeded while awaiting headers)";
    }
    if (Errors.find(e, java.net.ConnectException.class) != null) {
      return "dial tcp " + host + ":" + port + ": connect: connection refused";
    }
    return Errors.message(e);
  }

  /** A returned (non-panic) error raised from an I/O path. */
  static final class JobErrorIO extends IOException {
    JobErrorIO(String m) {
      super(m);
    }
  }
}
