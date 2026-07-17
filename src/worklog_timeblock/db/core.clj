(ns worklog-timeblock.db.core
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def builder rs/as-unqualified-lower-maps)

(defn datasource [path]
  (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)}))

(defn- normalize-state [state]
  (cond
    (keyword? state) (name state)
    (string? state) state
    (nil? state) nil
    :else (str state)))

(defn- normalize-kind [kind]
  (cond
    (keyword? kind) (name kind)
    (string? kind) kind
    (nil? kind) "normal"
    :else (str kind)))

(defn- row->category [row]
  {:id (:id row)
   :name (:name row)
   :kind (keyword (:kind row))})

(defn- row->title-mapping [row]
  {:title (:title row)
   :state (keyword (:state row))
   :category-id (:category_id row)})

(defn- row->work-log [row]
  {:id (:id row)
   :date (:date row)
   :title (:title row)
   :start-minute (:start_minute row)
   :end-minute (:end_minute row)
   :state (keyword (:state row))
   :category-id (:category_id row)
   :source-id (:source_id row)
   :external-id (:external_id row)
   :source-updated-at (:source_updated_at row)})

(defn upsert-category! [ds category]
  (jdbc/execute! ds
                 ["INSERT INTO categories (id, name, kind)
                   VALUES (?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET
                     name = excluded.name,
                     kind = excluded.kind,
                     updated_at = CURRENT_TIMESTAMP"
                  (:id category)
                  (:name category)
                  (normalize-kind (:kind category))])
  category)

(defn list-categories [ds]
  (mapv row->category
        (jdbc/execute! ds
                       ["SELECT id, name, kind FROM categories ORDER BY id"]
                       {:builder-fn builder})))

(defn categories-by-id [ds]
  (into {} (map (juxt :id identity)) (list-categories ds)))

(defn upsert-title-mapping! [ds mapping]
  (jdbc/execute! ds
                 ["INSERT INTO title_mappings (title, state, category_id)
                   VALUES (?, ?, ?)
                   ON CONFLICT(title) DO UPDATE SET
                     state = excluded.state,
                     category_id = excluded.category_id,
                     updated_at = CURRENT_TIMESTAMP"
                  (:title mapping)
                  (normalize-state (:state mapping))
                  (:category-id mapping)])
  mapping)

(defn get-title-mapping [ds title]
  (some-> (jdbc/execute-one! ds
                             ["SELECT title, state, category_id
                               FROM title_mappings
                               WHERE title = ?"
                              title]
                             {:builder-fn builder})
          row->title-mapping))

(defn list-title-mappings [ds]
  (mapv row->title-mapping
        (jdbc/execute! ds
                       ["SELECT title, state, category_id FROM title_mappings ORDER BY title"]
                       {:builder-fn builder})))

(defn title-mappings-map [ds]
  (into {} (map (fn [mapping] [(:title mapping) (dissoc mapping :title)]))
        (list-title-mappings ds)))

(defn insert-work-log! [ds work-log]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx
                       ["INSERT INTO work_logs
                         (date, title, start_minute, end_minute, state, category_id,
                          source_id, external_id, source_updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        (:date work-log)
                        (:title work-log)
                        (:start-minute work-log)
                        (:end-minute work-log)
                        (normalize-state (:state work-log))
                        (:category-id work-log)
                        (:source-id work-log)
                        (:external-id work-log)
                        (:source-updated-at work-log)])
    (:id (jdbc/execute-one! tx ["SELECT last_insert_rowid() AS id"]
                            {:builder-fn builder}))))

(defn upsert-work-log-by-source! [ds work-log]
  (jdbc/execute! ds
                 ["INSERT INTO work_logs
                   (date, title, start_minute, end_minute, state, category_id,
                    source_id, external_id, source_updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(source_id, external_id) DO UPDATE SET
                     date = excluded.date,
                     title = excluded.title,
                     start_minute = excluded.start_minute,
                     end_minute = excluded.end_minute,
                     state = excluded.state,
                     category_id = excluded.category_id,
                     source_updated_at = excluded.source_updated_at,
                     updated_at = CURRENT_TIMESTAMP"
                  (:date work-log)
                  (:title work-log)
                  (:start-minute work-log)
                  (:end-minute work-log)
                  (normalize-state (:state work-log))
                  (:category-id work-log)
                  (:source-id work-log)
                  (:external-id work-log)
                  (:source-updated-at work-log)])
  (some-> (jdbc/execute-one! ds
                             ["SELECT *
                               FROM work_logs
                               WHERE source_id = ? AND external_id = ?"
                              (:source-id work-log)
                              (:external-id work-log)]
                             {:builder-fn builder})
          row->work-log))

(defn get-work-log [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT * FROM work_logs WHERE id = ?" id]
                             {:builder-fn builder})
          row->work-log))

(defn work-logs-by-date [ds date]
  (mapv row->work-log
        (jdbc/execute! ds
                       ["SELECT *
                         FROM work_logs
                         WHERE date = ?
                         ORDER BY start_minute, id"
                        date]
                       {:builder-fn builder})))

(defn update-work-log! [ds id attrs]
  (let [current (or (get-work-log ds id)
                    (throw (ex-info "Work log not found" {:id id})))
        updated (merge current attrs)]
    (jdbc/execute! ds
                   ["UPDATE work_logs SET
                       date = ?,
                       title = ?,
                       start_minute = ?,
                       end_minute = ?,
                       state = ?,
                       category_id = ?,
                       updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?"
                    (:date updated)
                    (:title updated)
                    (:start-minute updated)
                    (:end-minute updated)
                    (normalize-state (:state updated))
                    (:category-id updated)
                    id])
    (get-work-log ds id)))

(defn list-dates [ds]
  (mapv :date
        (jdbc/execute! ds
                       ["SELECT DISTINCT date FROM work_logs ORDER BY date DESC"]
                       {:builder-fn builder})))
