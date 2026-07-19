(ns worklog-timeblock.db.core
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
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

(declare get-title-mapping list-categories)

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

(defn list-dates [ds]
  (mapv :date
        (jdbc/execute! ds
                       ["SELECT DISTINCT date FROM work_logs ORDER BY date DESC"]
                       {:builder-fn builder})))
