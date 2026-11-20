package com.vishworks.assetmgmt.service;

import com.vishworks.assetmgmt.db.Db;
import com.vishworks.assetmgmt.util.Errors;
import com.vishworks.assetmgmt.util.Log;
import java.sql.SQLException;
import org.springframework.stereotype.Repository;

/** Shared writes to the fund admin tables (call status, aggregates, audit events). */
@Repository
public class CallRepository {
  private final Db db;

  public CallRepository(Db db) {
    this.db = db;
  }

  public void event(String callId, String lpId, String step, String detail) {
    Object lp = (lpId == null || lpId.isEmpty()) ? null : lpId;
    try {
      db.exec("INSERT INTO call_events (call_id, lp_id, step, detail) VALUES ($1,$2,$3,$4)", callId, lp, step, detail);
    } catch (SQLException e) {
      Log.printf("event insert: %v", Errors.message(e));
    }
  }

  public void refreshAggregates(String callId) throws SQLException {
    db.exec("""

        		UPDATE capital_calls SET
        		  received_amount_usd = (SELECT COALESCE(SUM(committed_usd),0) FROM capital_call_lps WHERE call_id=$1 AND status='committed'),
        		  updated_at = NOW()
        		WHERE call_id=$1""", callId);
  }

  public void setCallStatus(String callId, String status) {
    try {
      db.exec("UPDATE capital_calls SET status=$2, updated_at=NOW() WHERE call_id=$1", callId, status);
    } catch (SQLException e) {
      Log.printf("set status: %v", Errors.message(e));
    }
  }
}
