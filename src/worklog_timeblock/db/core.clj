(ns worklog-timeblock.db.core
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time OffsetDateTime]))

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

(defn normalize-category-id [category-id]
  (cond
    (nil? category-id) nil
    (integer? category-id) category-id
    (string? category-id) (let [category-id (str/trim category-id)]
                            (when-not (str/blank? category-id)
                              (try
                                (or (parse-long category-id) category-id)
                                (catch NumberFormatException _
                                  category-id))))
    :else category-id))

(defn- normalize-active [active]
  (if (false? active) 0 1))

(defn- db-bool [value]
  (not (zero? (long (or value 0)))))

(defn- row->category [row]
  {:id (:id row)
   :legacy-key (:legacy_key row)
   :name (:name row)
   :kind (keyword (:kind row))
   :parent-id (:parent_id row)
   :position (:position row)
   :active? (db-bool (:active row))})

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

(defn- row->import-source [row]
  {:id (:id row)
   :kind (keyword (:kind row))
   :name (:name row)
   :uri (:uri row)
   :enabled? (db-bool (:enabled row))
   :fetch-interval-minutes (:fetch_interval_minutes row)
   :last-fetched-at (:last_fetched_at row)})

(defn- row->import-run [row]
  {:id (:id row)
   :import-source-id (:import_source_id row)
   :started-at (:started_at row)
   :finished-at (:finished_at row)
   :status (keyword (:status row))
   :fetched-count (:fetched_count row)
   :work-logs-created-count (:work_logs_created_count row)
   :error (:error row)})

(defn- row->source-event [row]
  {:id (:id row)
   :import-source-id (:import_source_id row)
   :source-id (:source_id row)
   :external-id (:external_id row)
   :date (:date row)
   :title (:title row)
   :starts-at (:starts_at row)
   :ends-at (:ends_at row)
   :timezone (:timezone row)
   :updated-at (:source_updated_at row)
   :sequence (:sequence row)
   :status (keyword (:status row))})

(defn- row->attendance [row]
  {:date (:date row)
   :clock-in-minute (:clock_in_minute row)
   :clock-out-minute (:clock_out_minute row)})

(defn- row->break-rule [row]
  {:id (:id row)
   :title (:title row)
   :start-minute (:start_minute row)
   :end-minute (:end_minute row)
   :enabled? (db-bool (:enabled row))})

(defn- row->break [row]
  {:id (:id row)
   :date (:date row)
   :title (:title row)
   :start-minute (:start_minute row)
   :end-minute (:end_minute row)
   :break-rule-id (:break_rule_id row)
   :active? (db-bool (:active row))})

(declare get-title-mapping list-categories get-import-source get-import-run
         get-source-event get-source-event-by-identity get-break-rule get-break)

(defn- next-position [ds parent-id]
  (inc
   (or (:position
        (jdbc/execute-one! ds
                           ["SELECT MAX(position) AS position
                             FROM categories
                             WHERE ((? IS NULL AND parent_id IS NULL)
                                OR parent_id = ?)"
                            parent-id parent-id]
                           {:builder-fn builder}))
       -1)))

(defn get-category [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, legacy_key, name, kind, parent_id, position, active
                               FROM categories
                               WHERE id = ?"
                              (normalize-category-id id)]
                             {:builder-fn builder})
          row->category))

(defn get-category-by-legacy-key [ds legacy-key]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, legacy_key, name, kind, parent_id, position, active
                               FROM categories
                               WHERE legacy_key = ?"
                              legacy-key]
                             {:builder-fn builder})
          row->category))

(defn resolve-category-id [ds category-id]
  (let [category-id (normalize-category-id category-id)]
    (cond
      (nil? category-id) nil
      (integer? category-id) (when (get-category ds category-id) category-id)
      (string? category-id) (:id (get-category-by-legacy-key ds category-id))
      :else nil)))

(defn category-has-active-children? [ds id]
  (pos? (:count
         (jdbc/execute-one! ds
                            ["SELECT COUNT(*) AS count
                              FROM categories
                              WHERE parent_id = ? AND active = 1"
                             (normalize-category-id id)]
                            {:builder-fn builder}))))

(defn category-has-assignments? [ds id]
  (let [id (normalize-category-id id)
        work-log-count (:count
                        (jdbc/execute-one! ds
                                           ["SELECT COUNT(*) AS count
                                             FROM work_logs
                                             WHERE category_id = ?"
                                            id]
                                           {:builder-fn builder}))
        mapping-count (:count
                       (jdbc/execute-one! ds
                                          ["SELECT COUNT(*) AS count
                                            FROM title_mappings
                                            WHERE category_id = ?"
                                           id]
                                          {:builder-fn builder}))]
    (pos? (+ work-log-count mapping-count))))

(defn category-assignable? [ds id]
  (when-let [category (get-category ds id)]
    (and (:active? category)
         (not (category-has-active-children? ds (:id category))))))

(defn assignable-category-ids [ds]
  (set (keep (fn [category]
               (when (category-assignable? ds (:id category))
                 (:id category)))
             (list-categories ds))))

(defn upsert-category! [ds category]
  (let [requested-legacy-key (or (:legacy-key category)
                                 (when (string? (:id category)) (:id category)))
        id (when (integer? (:id category)) (:id category))
        existing (or (when id (get-category ds id))
                     (when requested-legacy-key
                       (get-category-by-legacy-key ds requested-legacy-key)))
        legacy-key (or requested-legacy-key (:legacy-key existing))
        parent-id (if (contains? category :parent-id)
                    (resolve-category-id ds (:parent-id category))
                    (:parent-id existing))
        position (or (:position category) (:position existing) (next-position ds parent-id))
        active? (if (contains? category :active?)
                  (:active? category)
                  (if existing (:active? existing) true))
        kind (or (:kind category) (:kind existing))
        name (or (:name category) (:name existing))]
    (cond
      id
      (do
        (jdbc/execute! ds
                       ["INSERT INTO categories
                         (id, legacy_key, name, kind, parent_id, position, active)
                         VALUES (?, ?, ?, ?, ?, ?, ?)
                         ON CONFLICT(id) DO UPDATE SET
                           legacy_key = excluded.legacy_key,
                           name = excluded.name,
                           kind = excluded.kind,
                           parent_id = excluded.parent_id,
                           position = excluded.position,
                           active = excluded.active,
                           updated_at = CURRENT_TIMESTAMP"
                        id
                        legacy-key
                        name
                        (normalize-kind kind)
                        parent-id
                        position
                        (normalize-active active?)])
        (get-category ds id))

      legacy-key
      (do
        (jdbc/execute! ds
                       ["INSERT INTO categories
                         (legacy_key, name, kind, parent_id, position, active)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(legacy_key) DO UPDATE SET
                           name = excluded.name,
                           kind = excluded.kind,
                           parent_id = excluded.parent_id,
                           position = excluded.position,
                           active = excluded.active,
                           updated_at = CURRENT_TIMESTAMP"
                        legacy-key
                        name
                        (normalize-kind kind)
                        parent-id
                        position
                        (normalize-active active?)])
        (get-category-by-legacy-key ds legacy-key))

      :else
      (let [id (:id (jdbc/execute-one! ds
                                       ["INSERT INTO categories
                                        (name, kind, parent_id, position, active)
                                         VALUES (?, ?, ?, ?, ?)
                                         RETURNING id"
                                        name
                                        (normalize-kind kind)
                                        parent-id
                                        position
                                        (normalize-active active?)]
                                       {:builder-fn builder}))]
        (get-category ds id)))))

(defn list-categories [ds]
  (let [categories (mapv row->category
                         (jdbc/execute! ds
                                        ["SELECT id, legacy_key, name, kind,
                                                 parent_id, position, active
                                          FROM categories
                                          ORDER BY parent_id IS NOT NULL,
                                                   position,
                                                   id"]
                                        {:builder-fn builder}))
        children-by-parent (group-by :parent-id (filter :parent-id categories))
        roots (sort-by (juxt :position :id) (filter #(nil? (:parent-id %)) categories))]
    (vec
     (mapcat (fn [root]
               (cons root
                     (sort-by (juxt :position :id)
                              (get children-by-parent (:id root)))))
             roots))))

(defn categories-by-id [ds]
  (into {} (map (juxt :id identity)) (list-categories ds)))

(defn other-category-id [ds]
  (:id (first (filter #(and (:active? %) (= :other (:kind %)))
                      (list-categories ds)))))

(defn root-categories [ds]
  (vec (filter #(and (:active? %) (nil? (:parent-id %))) (list-categories ds))))

(defn find-category-by-name-and-parent [ds name parent-id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, legacy_key, name, kind, parent_id, position, active
                               FROM categories
                               WHERE name = ?
                                 AND ((? IS NULL AND parent_id IS NULL)
                                  OR parent_id = ?)
                                 AND active = 1"
                              name parent-id parent-id]
                             {:builder-fn builder})
          row->category))

(defn move-category! [ds id direction]
  (let [category (get-category ds id)
        id (:id category)
        parent-id (:parent-id category)
        sibling-sql "SELECT id, position
                     FROM categories
                     WHERE ((? IS NULL AND parent_id IS NULL)
                        OR parent_id = ?)
                       AND active = 1
                     ORDER BY position, id"
        siblings (jdbc/execute! ds [sibling-sql parent-id parent-id]
                                {:builder-fn builder})
        ids (mapv :id siblings)
        current-index (first (keep-indexed (fn [idx sibling-id]
                                             (when (= id sibling-id) idx))
                                           ids))
        target-index (case direction
                       :up (when current-index (dec current-index))
                       :down (when current-index (inc current-index))
                       "up" (when current-index (dec current-index))
                       "down" (when current-index (inc current-index))
                       nil)]
    (if (and category target-index (<= 0 target-index) (< target-index (count ids)))
      (let [reordered (assoc ids
                             current-index (nth ids target-index)
                             target-index id)]
        (doseq [[position sibling-id] (map-indexed vector reordered)]
          (jdbc/execute! ds
                         ["UPDATE categories
                           SET position = ?, updated_at = CURRENT_TIMESTAMP
                           WHERE id = ?"
                          position sibling-id]))
        (get-category ds id))
      category)))

(defn upsert-title-mapping! [ds mapping]
  (let [category-id (resolve-category-id ds (:category-id mapping))]
    (when (and (:category-id mapping) (nil? category-id))
      (throw (ex-info "Unknown category" {:category-id (:category-id mapping)})))
    (jdbc/execute! ds
                   ["INSERT INTO title_mappings (title, state, category_id)
                     VALUES (?, ?, ?)
                     ON CONFLICT(title) DO UPDATE SET
                       state = excluded.state,
                       category_id = excluded.category_id,
                       updated_at = CURRENT_TIMESTAMP"
                    (:title mapping)
                    (normalize-state (:state mapping))
                    category-id])
    (get-title-mapping ds (:title mapping))))

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

(defn- source-event-date [source-event]
  (str (.toLocalDate (OffsetDateTime/parse (:starts-at source-event)))))

(defn create-import-source! [ds source]
  (let [enabled? (if (contains? source :enabled?)
                   (:enabled? source)
                   (if (contains? source :enabled)
                     (:enabled source)
                     true))
        id (:id (jdbc/execute-one! ds
                                   ["INSERT INTO import_sources
                                     (kind, name, uri, enabled, fetch_interval_minutes)
                                     VALUES (?, ?, ?, ?, ?)
                                     RETURNING id"
                                    (normalize-kind (:kind source))
                                    (:name source)
                                    (:uri source)
                                    (normalize-active enabled?)
                                    (or (:fetch-interval-minutes source) 60)]
                                   {:builder-fn builder}))]
    (get-import-source ds id)))

(defn get-import-source [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, kind, name, uri, enabled,
                                      fetch_interval_minutes, last_fetched_at
                               FROM import_sources
                               WHERE id = ?"
                              id]
                             {:builder-fn builder})
          row->import-source))

(defn list-import-sources [ds]
  (mapv row->import-source
        (jdbc/execute! ds
                       ["SELECT id, kind, name, uri, enabled,
                                fetch_interval_minutes, last_fetched_at
                         FROM import_sources
                         ORDER BY id"]
                       {:builder-fn builder})))

(defn update-import-source-last-fetched! [ds id fetched-at]
  (jdbc/execute! ds
                 ["UPDATE import_sources
                   SET last_fetched_at = ?, updated_at = CURRENT_TIMESTAMP
                   WHERE id = ?"
                  fetched-at id])
  (get-import-source ds id))

(defn create-import-run! [ds run]
  (let [id (:id (jdbc/execute-one! ds
                                   ["INSERT INTO import_runs
                                     (import_source_id, started_at, status)
                                     VALUES (?, ?, ?)
                                     RETURNING id"
                                    (:import-source-id run)
                                    (:started-at run)
                                    (normalize-state (or (:status run) :running))]
                                   {:builder-fn builder}))]
    (get-import-run ds id)))

(defn get-import-run [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, import_source_id, started_at, finished_at,
                                      status, fetched_count,
                                      work_logs_created_count, error
                               FROM import_runs
                               WHERE id = ?"
                              id]
                             {:builder-fn builder})
          row->import-run))

(defn finish-import-run! [ds id attrs]
  (jdbc/execute! ds
                 ["UPDATE import_runs
                   SET finished_at = ?,
                       status = ?,
                       fetched_count = ?,
                       work_logs_created_count = ?,
                       error = ?,
                       updated_at = CURRENT_TIMESTAMP
                   WHERE id = ?"
                  (:finished-at attrs)
                  (normalize-state (:status attrs))
                  (or (:fetched-count attrs) 0)
                  (or (:work-logs-created-count attrs) 0)
                  (:error attrs)
                  id])
  (get-import-run ds id))

(defn list-import-runs [ds import-source-id]
  (mapv row->import-run
        (jdbc/execute! ds
                       ["SELECT id, import_source_id, started_at, finished_at,
                                status, fetched_count,
                                work_logs_created_count, error
                         FROM import_runs
                         WHERE import_source_id = ?
                         ORDER BY started_at, id"
                        import-source-id]
                       {:builder-fn builder})))

(defn upsert-source-event! [ds source-event]
  (jdbc/execute! ds
                 ["INSERT INTO source_events
                   (import_source_id, source_id, external_id, date, title,
                    starts_at, ends_at, timezone, source_updated_at, sequence,
                    status)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(source_id, external_id) DO UPDATE SET
                     import_source_id = excluded.import_source_id,
                     date = excluded.date,
                     title = excluded.title,
                     starts_at = excluded.starts_at,
                     ends_at = excluded.ends_at,
                     timezone = excluded.timezone,
                     source_updated_at = excluded.source_updated_at,
                     sequence = excluded.sequence,
                     status = excluded.status,
                     updated_at = CURRENT_TIMESTAMP"
                  (:import-source-id source-event)
                  (:source-id source-event)
                  (:external-id source-event)
                  (source-event-date source-event)
                  (:title source-event)
                  (:starts-at source-event)
                  (:ends-at source-event)
                  (:timezone source-event)
                  (:updated-at source-event)
                  (:sequence source-event)
                  (normalize-state (or (:status source-event) :candidate))])
  (get-source-event-by-identity ds (:source-id source-event) (:external-id source-event)))

(defn get-source-event-by-identity [ds source-id external-id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, import_source_id, source_id, external_id,
                                      date, title, starts_at, ends_at, timezone,
                                      source_updated_at, sequence, status
                               FROM source_events
                               WHERE source_id = ? AND external_id = ?"
                              source-id external-id]
                              {:builder-fn builder})
          row->source-event))

(defn get-source-event [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, import_source_id, source_id, external_id,
                                      date, title, starts_at, ends_at, timezone,
                                      source_updated_at, sequence, status
                               FROM source_events
                               WHERE id = ?"
                              id]
                             {:builder-fn builder})
          row->source-event))

(defn source-events-by-date [ds date]
  (mapv row->source-event
        (jdbc/execute! ds
                       ["SELECT id, import_source_id, source_id, external_id,
                                date, title, starts_at, ends_at, timezone,
                                source_updated_at, sequence, status
                         FROM source_events
                         WHERE date = ?
                         ORDER BY starts_at, id"
                        date]
                       {:builder-fn builder})))

(defn get-attendance [ds date]
  (some-> (jdbc/execute-one! ds
                             ["SELECT date, clock_in_minute, clock_out_minute
                               FROM day_attendance
                               WHERE date = ?"
                              date]
                             {:builder-fn builder})
          row->attendance))

(defn upsert-attendance! [ds attendance]
  (jdbc/execute! ds
                 ["INSERT INTO day_attendance
                   (date, clock_in_minute, clock_out_minute)
                   VALUES (?, ?, ?)
                   ON CONFLICT(date) DO UPDATE SET
                     clock_in_minute = excluded.clock_in_minute,
                     clock_out_minute = excluded.clock_out_minute,
                     updated_at = CURRENT_TIMESTAMP"
                  (:date attendance)
                  (:clock-in-minute attendance)
                  (:clock-out-minute attendance)])
  (get-attendance ds (:date attendance)))

(defn create-break-rule! [ds rule]
  (let [enabled? (if (contains? rule :enabled?)
                   (:enabled? rule)
                   (if (contains? rule :enabled)
                     (:enabled rule)
                     true))
        id (:id (jdbc/execute-one! ds
                                   ["INSERT INTO break_rules
                                     (title, start_minute, end_minute, enabled)
                                     VALUES (?, ?, ?, ?)
                                     RETURNING id"
                                    (:title rule)
                                    (:start-minute rule)
                                    (:end-minute rule)
                                    (normalize-active enabled?)]
                                   {:builder-fn builder}))]
    (get-break-rule ds id)))

(defn get-break-rule [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, title, start_minute, end_minute, enabled
                               FROM break_rules
                               WHERE id = ?"
                              id]
                             {:builder-fn builder})
          row->break-rule))

(defn list-break-rules [ds]
  (mapv row->break-rule
        (jdbc/execute! ds
                       ["SELECT id, title, start_minute, end_minute, enabled
                         FROM break_rules
                         ORDER BY id"]
                       {:builder-fn builder})))

(defn create-break! [ds break]
  (let [active? (if (contains? break :active?)
                  (:active? break)
                  true)
        id (:id (jdbc/execute-one! ds
                                   ["INSERT INTO breaks
                                     (date, title, start_minute, end_minute,
                                      break_rule_id, active)
                                     VALUES (?, ?, ?, ?, ?, ?)
                                     RETURNING id"
                                    (:date break)
                                    (:title break)
                                    (:start-minute break)
                                    (:end-minute break)
                                    (:break-rule-id break)
                                    (normalize-active active?)]
                                   {:builder-fn builder}))]
    (get-break ds id)))

(defn get-break [ds id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT id, date, title, start_minute, end_minute,
                                      break_rule_id, active
                               FROM breaks
                               WHERE id = ?"
                              id]
                             {:builder-fn builder})
          row->break))

(defn breaks-by-date [ds date]
  (mapv row->break
        (jdbc/execute! ds
                       ["SELECT id, date, title, start_minute, end_minute,
                                break_rule_id, active
                         FROM breaks
                         WHERE date = ? AND active = 1
                         ORDER BY start_minute, id"
                        date]
                       {:builder-fn builder})))

(defn update-break! [ds id attrs]
  (let [current (or (get-break ds id)
                    (throw (ex-info "Break not found" {:id id})))
        active? (if (contains? attrs :active?)
                  (:active? attrs)
                  (:active? current))
        updated (merge current attrs {:active? active?})]
    (jdbc/execute! ds
                   ["UPDATE breaks SET
                       title = ?,
                       start_minute = ?,
                       end_minute = ?,
                       active = ?,
                       updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?"
                    (:title updated)
                    (:start-minute updated)
                    (:end-minute updated)
                    (normalize-active (:active? updated))
                    id])
    (get-break ds id)))

(defn materialize-breaks-for-date! [ds date]
  (doseq [rule (filter :enabled? (list-break-rules ds))]
    (jdbc/execute! ds
                   ["INSERT INTO breaks
                     (date, title, start_minute, end_minute, break_rule_id, active)
                     VALUES (?, ?, ?, ?, ?, 1)
                     ON CONFLICT(date, break_rule_id)
                     WHERE break_rule_id IS NOT NULL
                     DO NOTHING"
                    date
                    (:title rule)
                    (:start-minute rule)
                    (:end-minute rule)
                    (:id rule)]))
  (breaks-by-date ds date))

(defn work-log-by-source [ds source-id external-id]
  (some-> (jdbc/execute-one! ds
                             ["SELECT *
                               FROM work_logs
                               WHERE source_id = ? AND external_id = ?"
                              source-id external-id]
                             {:builder-fn builder})
          row->work-log))

(defn insert-work-log! [ds work-log]
  (let [category-id (resolve-category-id ds (:category-id work-log))]
    (when (and (:category-id work-log) (nil? category-id))
      (throw (ex-info "Unknown category" {:category-id (:category-id work-log)})))
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
                          category-id
                          (:source-id work-log)
                          (:external-id work-log)
                          (:source-updated-at work-log)])
      (:id (jdbc/execute-one! tx ["SELECT last_insert_rowid() AS id"]
                              {:builder-fn builder})))))

(defn upsert-work-log-by-source! [ds work-log]
  (let [category-id (resolve-category-id ds (:category-id work-log))]
    (when (and (:category-id work-log) (nil? category-id))
      (throw (ex-info "Unknown category" {:category-id (:category-id work-log)})))
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
                    category-id
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
            row->work-log)))

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
        category-id (if (contains? attrs :category-id)
                      (resolve-category-id ds (:category-id attrs))
                      (:category-id current))
        updated (merge current attrs {:category-id category-id})]
    (when (and (contains? attrs :category-id)
               (:category-id attrs)
               (nil? category-id))
      (throw (ex-info "Unknown category" {:category-id (:category-id attrs)})))
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

(defn adjust-work-log-boundary! [ds left-id right-id boundary-minute]
  (jdbc/with-transaction [tx ds]
    (let [left (get-work-log tx left-id)
          right (get-work-log tx right-id)]
      (update-work-log! tx left-id {:end-minute boundary-minute})
      (update-work-log! tx right-id {:start-minute boundary-minute})
      {:left (get-work-log tx (:id left))
       :right (get-work-log tx (:id right))})))

(defn list-dates [ds]
  (mapv :date
        (jdbc/execute! ds
                       ["SELECT DISTINCT date FROM work_logs ORDER BY date DESC"]
                       {:builder-fn builder})))
