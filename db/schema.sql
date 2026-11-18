-- Fund admin / ledger database (read model for the demo dashboard).
-- Camunda is the system of record for process state; this DB is the
-- business system the workers integrate with.

CREATE TABLE IF NOT EXISTS lps (
    lp_id          VARCHAR(64) PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    commitment_usd NUMERIC(18,2) NOT NULL,
    email          VARCHAR(256) NOT NULL,
    demo_behavior  VARCHAR(32)  NOT NULL DEFAULT 'prompt'
);

CREATE TABLE IF NOT EXISTS capital_calls (
    call_id              VARCHAR(64) PRIMARY KEY,
    fund_id              VARCHAR(64) NOT NULL,
    process_instance_key VARCHAR(32),
    target_amount_usd    NUMERIC(18,2) NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'issued',
    received_amount_usd  NUMERIC(18,2) NOT NULL DEFAULT 0,
    gap_usd              NUMERIC(18,2),
    gap_percent          NUMERIC(8,2),
    bridge_triggered     BOOLEAN NOT NULL DEFAULT FALSE,
    bridge_confirmation  VARCHAR(128),
    bridge_amount_usd    NUMERIC(18,2),
    bridge_fee_usd       NUMERIC(18,2),
    concentration_hhi    NUMERIC(8,4),
    report_path          VARCHAR(256),
    deadline_days        INT,
    seconds_per_day      INT,
    deadline_date        TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS capital_call_lps (
    call_id         VARCHAR(64) NOT NULL REFERENCES capital_calls(call_id) ON DELETE CASCADE,
    lp_id           VARCHAR(64) NOT NULL,
    email           VARCHAR(256),
    commitment_usd  NUMERIC(18,2) NOT NULL,
    draw_amount_usd NUMERIC(18,2) NOT NULL,
    committed_usd   NUMERIC(18,2) NOT NULL DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'notified',
    followup_stage  INT NOT NULL DEFAULT 0,
    risk_score      NUMERIC(8,4),
    gp_action       VARCHAR(16),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (call_id, lp_id)
);

CREATE TABLE IF NOT EXISTS gp_escalations (
    call_id     VARCHAR(64) NOT NULL,
    lp_id       VARCHAR(64) NOT NULL,
    risk_score  NUMERIC(8,4),
    status      VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending | decided
    action      VARCHAR(16),                            -- waive | enforce
    gp_name     VARCHAR(128),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    decided_at  TIMESTAMP,
    PRIMARY KEY (call_id, lp_id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id              SERIAL PRIMARY KEY,
    call_id         VARCHAR(64) NOT NULL,
    lp_id           VARCHAR(64),
    debit_account   VARCHAR(128) NOT NULL,
    credit_account  VARCHAR(128) NOT NULL,
    amount_usd      NUMERIC(18,2) NOT NULL,
    entry_type      VARCHAR(64) NOT NULL,
    wire_reference  VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS call_events (
    id         SERIAL PRIMARY KEY,
    call_id    VARCHAR(64) NOT NULL,
    lp_id      VARCHAR(64),
    step       VARCHAR(64) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_call_events_call ON call_events(call_id, id);
