(ns worklog-timeblock.db.migration
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc.result-set :as rs]
            [next.jdbc :as jdbc]))

(def builder rs/as-unqualified-lower-maps)

(defn- sql-statements [resource-name]
  (->> (slurp (io/resource resource-name))
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)
       (map #(str % ";"))))

(defn- table-info [ds table-name]
  (jdbc/execute! ds [(str "PRAGMA table_info(" table-name ")")]
                 {:builder-fn builder}))

(defn- column-type [ds table-name column-name]
  (some (fn [column]
          (when (= column-name (:name column))
            (:type column)))
        (table-info ds table-name)))

(defn- legacy-category-schema? [ds]
  (= "TEXT" (str/upper-case (or (column-type ds "categories" "id") ""))))

(defn- execute-schema! [ds]
  (doseq [sql (sql-statements "migrations/001_initial.up.sql")]
    (jdbc/execute! ds [sql])))

(defn- ensure-column! [ds table-name column-name definition]
  (when-not (column-type ds table-name column-name)
    (jdbc/execute! ds [(str "ALTER TABLE " table-name
                            " ADD COLUMN " column-name " " definition)])))

(defn- normalize-kind [kind]
  (or kind "normal"))

(defn- migrate-legacy-category-schema! [ds]
  (when (legacy-category-schema? ds)
    (let [categories (jdbc/execute! ds
                                    ["SELECT id, name, kind FROM categories ORDER BY id"]
                                    {:builder-fn builder})
          title-mappings (jdbc/execute! ds
                                        ["SELECT title, state, category_id FROM title_mappings ORDER BY title"]
                                        {:builder-fn builder})
          work-logs (jdbc/execute! ds
                                   ["SELECT * FROM work_logs ORDER BY id"]
                                   {:builder-fn builder})]
      (jdbc/execute! ds ["PRAGMA foreign_keys = OFF"])
      (doseq [sql ["DROP INDEX IF EXISTS idx_work_logs_source_event"
                   "DROP INDEX IF EXISTS idx_work_logs_date"
                   "ALTER TABLE categories RENAME TO categories_legacy_text"
                   "ALTER TABLE title_mappings RENAME TO title_mappings_legacy_text"
                   "ALTER TABLE work_logs RENAME TO work_logs_legacy_text"]]
        (jdbc/execute! ds [sql]))
      (execute-schema! ds)
      (doseq [[position category] (map-indexed vector categories)]
        (jdbc/execute! ds
                       ["INSERT INTO categories
                         (legacy_key, name, kind, parent_id, position, active)
                         VALUES (?, ?, ?, NULL, ?, 1)"
                        (:id category)
                        (:name category)
                        (normalize-kind (:kind category))
                        position]))
      (let [category-id-by-legacy-key
            (into {}
                  (map (juxt :legacy_key :id))
                  (jdbc/execute! ds
                                 ["SELECT id, legacy_key FROM categories"]
                                 {:builder-fn builder}))]
        (doseq [mapping title-mappings]
          (jdbc/execute! ds
                         ["INSERT INTO title_mappings
                           (title, state, category_id, created_at, updated_at)
                           VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
                          (:title mapping)
                          (:state mapping)
                          (get category-id-by-legacy-key (:category_id mapping))]))
        (doseq [log work-logs]
          (jdbc/execute! ds
                         ["INSERT INTO work_logs
                           (id, date, title, start_minute, end_minute, state,
                            category_id, source_id, external_id, source_updated_at,
                            created_at, updated_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                          (:id log)
                          (:date log)
                          (:title log)
                          (:start_minute log)
                          (:end_minute log)
                          (:state log)
                          (get category-id-by-legacy-key (:category_id log))
                          (:source_id log)
                          (:external_id log)
                          (:source_updated_at log)
                          (:created_at log)
                          (:updated_at log)])))
      (doseq [sql ["DROP TABLE categories_legacy_text"
                   "DROP TABLE title_mappings_legacy_text"
                   "DROP TABLE work_logs_legacy_text"]]
        (jdbc/execute! ds [sql]))
      (jdbc/execute! ds ["PRAGMA foreign_keys = ON"]))))

(defn migrate! [ds]
  (jdbc/execute! ds ["PRAGMA foreign_keys = ON"])
  (execute-schema! ds)
  (migrate-legacy-category-schema! ds)
  (ensure-column! ds "break_rules" "active" "INTEGER NOT NULL DEFAULT 1")
  :migrated)
