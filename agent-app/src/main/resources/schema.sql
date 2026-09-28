PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS orchestration_run (
    id TEXT PRIMARY KEY,
    request_text TEXT NOT NULL,
    status TEXT NOT NULL,
    model_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    started_at TEXT,
    updated_at TEXT NOT NULL,
    finished_at TEXT,
    final_answer TEXT,
    report_path TEXT,
    error_code TEXT,
    error_message TEXT,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tool_invocation (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence_number INTEGER NOT NULL,
    server_name TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    tool_call_id TEXT,
    status TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    duration_ms INTEGER,
    input_hash TEXT NOT NULL,
    output_hash TEXT,
    input_refs_json TEXT NOT NULL,
    output_refs_json TEXT,
    error_code TEXT,
    error_message TEXT,
    FOREIGN KEY(run_id) REFERENCES orchestration_run(id),
    UNIQUE(run_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS orchestration_event (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    sequence_number INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    server_name TEXT,
    tool_name TEXT,
    occurred_at TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    FOREIGN KEY(run_id) REFERENCES orchestration_run(id),
    UNIQUE(run_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_event_run_sequence ON orchestration_event(run_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_invocation_run_sequence ON tool_invocation(run_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_run_status_updated ON orchestration_run(status, updated_at);
