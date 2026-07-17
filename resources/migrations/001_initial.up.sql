CREATE TABLE IF NOT EXISTS categories (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'normal',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS title_mappings (
  title TEXT PRIMARY KEY,
  state TEXT NOT NULL,
  category_id TEXT,
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
  category_id TEXT,
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
