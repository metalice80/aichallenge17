PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS weather_schedule (
    id TEXT PRIMARY KEY,
    city TEXT NOT NULL,
    normalized_city TEXT NOT NULL,
    collection_interval_minutes INTEGER NOT NULL CHECK (collection_interval_minutes BETWEEN 1 AND 1440),
    summary_interval_minutes INTEGER NOT NULL CHECK (summary_interval_minutes BETWEEN 5 AND 10080),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    next_collection_at TEXT NOT NULL,
    next_summary_at TEXT NOT NULL,
    last_collection_at TEXT NULL,
    lease_owner TEXT NULL,
    lease_until TEXT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_error TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_weather_schedule_due
    ON weather_schedule(status, next_collection_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_weather_schedule_active_definition
    ON weather_schedule(normalized_city, collection_interval_minutes, summary_interval_minutes)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS weather_observation (
    id TEXT PRIMARY KEY,
    schedule_id TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    temperature_celsius REAL NOT NULL,
    apparent_temperature_celsius REAL NOT NULL,
    wind_speed_kmh REAL NOT NULL,
    weather_code INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY(schedule_id) REFERENCES weather_schedule(id),
    UNIQUE(schedule_id, observed_at)
);

CREATE INDEX IF NOT EXISTS idx_weather_observation_schedule_time
    ON weather_observation(schedule_id, observed_at);

CREATE TABLE IF NOT EXISTS weather_summary (
    id TEXT PRIMARY KEY,
    schedule_id TEXT NOT NULL,
    period_started_at TEXT NOT NULL,
    period_ended_at TEXT NOT NULL,
    sample_count INTEGER NOT NULL CHECK (sample_count > 0),
    min_temperature_celsius REAL NOT NULL,
    max_temperature_celsius REAL NOT NULL,
    avg_temperature_celsius REAL NOT NULL,
    max_wind_speed_kmh REAL NOT NULL,
    latest_weather_code INTEGER NOT NULL,
    delivery_status TEXT NOT NULL CHECK (delivery_status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED')),
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    claimed_by TEXT NULL,
    claimed_at TEXT NULL,
    rendered_text TEXT NULL,
    last_delivery_error TEXT NULL,
    created_at TEXT NOT NULL,
    delivered_at TEXT NULL,
    FOREIGN KEY(schedule_id) REFERENCES weather_schedule(id),
    UNIQUE(schedule_id, period_started_at, period_ended_at)
);

CREATE INDEX IF NOT EXISTS idx_weather_summary_delivery
    ON weather_summary(delivery_status, created_at);
CREATE INDEX IF NOT EXISTS idx_weather_summary_schedule_period
    ON weather_summary(schedule_id, period_ended_at);
