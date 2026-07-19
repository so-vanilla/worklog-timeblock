CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  legacy_key TEXT UNIQUE,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'normal',
  parent_id INTEGER,
  position INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (parent_id) REFERENCES categories(id)
);

CREATE TABLE IF NOT EXISTS title_mappings (
  title TEXT PRIMARY KEY,
  state TEXT NOT NULL,
  category_id INTEGER,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (category_id) REFERENCES categories(id)
);

CREATE TABLE IF NOT EXISTS work_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  date TEXT NOT NULL,
  title TEXT NOT NULL,
  start_minute INTEGER NOT NULL,
  end_minute INTEGER NOT NULL,
  state TEXT NOT NULL,
  category_id INTEGER,
  source_id TEXT,
  external_id TEXT,
  source_updated_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (start_minute >= 0),
  CHECK (end_minute > start_minute),
  CHECK (state IN ('confirmed', 'uncategorized', 'excluded'))
);

CREATE INDEX IF NOT EXISTS idx_work_logs_date ON work_logs(date);
CREATE UNIQUE INDEX IF NOT EXISTS idx_work_logs_source_event
  ON work_logs(source_id, external_id);

CREATE TABLE IF NOT EXISTS import_sources (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  uri TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  fetch_interval_minutes INTEGER NOT NULL DEFAULT 60,
  last_fetched_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (kind IN ('ical')),
  CHECK (fetch_interval_minutes > 0)
);

CREATE TABLE IF NOT EXISTS import_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  import_source_id INTEGER NOT NULL,
  started_at TEXT NOT NULL,
  finished_at TEXT,
  status TEXT NOT NULL,
  fetched_count INTEGER NOT NULL DEFAULT 0,
  work_logs_created_count INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (import_source_id) REFERENCES import_sources(id),
  CHECK (status IN ('running', 'success', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_import_runs_source
  ON import_runs(import_source_id, started_at);

CREATE TABLE IF NOT EXISTS source_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  import_source_id INTEGER,
  source_id TEXT NOT NULL,
  external_id TEXT NOT NULL,
  date TEXT NOT NULL,
  title TEXT NOT NULL,
  starts_at TEXT NOT NULL,
  ends_at TEXT NOT NULL,
  timezone TEXT,
  source_updated_at TEXT,
  sequence INTEGER,
  status TEXT NOT NULL DEFAULT 'candidate',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (import_source_id) REFERENCES import_sources(id),
  CHECK (status IN ('candidate'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_source_events_identity
  ON source_events(source_id, external_id);

CREATE INDEX IF NOT EXISTS idx_source_events_date
  ON source_events(date);
