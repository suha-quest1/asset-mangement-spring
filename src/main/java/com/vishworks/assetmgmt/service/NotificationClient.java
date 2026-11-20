package com.vishworks.assetmgmt.service;

import com.vishworks.assetmgmt.config.AppProperties;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Log;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Notifications via the mock SES. */
@Service
public class NotificationClient {
  private final HttpJson httpJson;
  private final String sesUrl;

  public NotificationClient(HttpJson httpJson, AppProperties props) {
    this.httpJson = httpJson;
    this.sesUrl = props.sesUrl();
  }

  /** Sends via mock SES. Notification failures are logged, not fatal. */
  public void email(Map<String, Object> payload) {
    try {
      httpJson.postJSON(sesUrl + "/send", payload, null);
    } catch (IOException e) {
      Log.printf("[ses] send failed: %v", Errors.message(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.printf("[ses] send failed: %v", "interrupted");
    }
  }

  /** map[string]any literal; keys sorted like encoding/json writes a Go map. */
  public static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new TreeMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put((String) kv[i], kv[i + 1]);
    }
    return out;
  }
}
