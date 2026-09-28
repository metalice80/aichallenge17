PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS pipeline_run (
  id TEXT PRIMARY KEY,
  request_text TEXT NOT NULL,
  status TEXT NOT NULL,
  current_step TEXT,
  model_id TEXT,
  created_at TEXT NOT NULL,
  started_at TEXT,
  updated_at TEXT NOT NULL,
  finished_at TEXT,
  result_file TEXT,
  error_code TEXT,
  error_message TEXT,
  version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pipeline_step (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  sequence_number INTEGER NOT NULL,
  tool_name TEXT NOT NULL,
  status TEXT NOT NULL,
  input_artifact_id TEXT,
  output_artifact_id TEXT,
  request_fingerprint TEXT,
  started_at TEXT,
  finished_at TEXT,
  duration_ms INTEGER,
  attempt INTEGER NOT NULL DEFAULT 1,
  error_code TEXT,
  error_message TEXT,
  FOREIGN KEY(run_id) REFERENCES pipeline_run(id),
  UNIQUE(run_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS pipeline_artifact (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  type TEXT NOT NULL,
  source_artifact_id TEXT,
  media_type TEXT NOT NULL,
  content TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES pipeline_run(id),
  FOREIGN KEY(source_artifact_id) REFERENCES pipeline_artifact(id)
);

CREATE TABLE IF NOT EXISTS pipeline_event (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  step_id TEXT,
  sequence_number INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  FOREIGN KEY(run_id) REFERENCES pipeline_run(id),
  FOREIGN KEY(step_id) REFERENCES pipeline_step(id),
  UNIQUE(run_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_run_status_updated ON pipeline_run(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_pipeline_event_run_sequence ON pipeline_event(run_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_pipeline_artifact_run ON pipeline_artifact(run_id);
