(ns worklog-timeblock.importer.core
  (:require [worklog-timeblock.db.core :as db]
            [worklog-timeblock.domain.worklog :as worklog]
            [worklog-timeblock.plugin.ical :as ical]
            [worklog-timeblock.plugin.protocol :as plugin])
  (:import [java.time Duration Instant]))

(defn- now-str
  ([] (now-str (Instant/now)))
  ([instant] (str instant)))

(defn- candidate-key [candidate]
  [(:source-id candidate) (:external-id candidate)])

(defn- rank [candidate]
  [(or (some-> (:updated-at candidate) Instant/parse) Instant/EPOCH)
   (or (:sequence candidate) 0)])

(defn- newer-candidate [left right]
  (if (pos? (compare (rank right) (rank left)))
    right
    left))

(defn- dedupe-candidates [events]
  (->> events
       (reduce (fn [acc candidate]
                 (update acc
                         (candidate-key candidate)
                         (fn [existing]
                           (if existing
                             (newer-candidate existing candidate)
                             candidate))))
               {})
       vals
       (sort-by (juxt :starts-at :external-id))
       vec))

(defn- create-snapshot-if-missing! [ds mappings candidate]
  (when-not (db/work-log-by-source ds (:source-id candidate) (:external-id candidate))
    (db/insert-work-log! ds (worklog/candidate->worklog mappings candidate))
    true))

(defn import-candidates! [ds {:keys [import-source-id events]}]
  (let [events (dedupe-candidates (or events []))
        mappings (db/title-mappings-map ds)]
    (reduce
     (fn [result candidate]
       (let [candidate (assoc candidate :import-source-id import-source-id)
             _source-event (db/upsert-source-event! ds candidate)
             created? (create-snapshot-if-missing! ds mappings candidate)]
         (cond-> (update result :source-events-upserted inc)
           created? (update :work-logs-created inc))))
     {:fetched (count events)
      :source-events-upserted 0
      :work-logs-created 0}
     events)))

(defn- event-source [import-source]
  (case (:kind import-source)
    :ical (ical/from-uri (str "ical:" (:id import-source)) (:uri import-source))
    (throw (ex-info "Unsupported import source kind"
                    {:kind (:kind import-source)}))))

(defn fetch-import-source! [ds import-source now]
  (let [started-at (now-str now)
        run (db/create-import-run! ds {:import-source-id (:id import-source)
                                       :started-at started-at
                                       :status :running})]
    (try
      (let [source (event-source import-source)
            events (plugin/candidate-events source {})
            result (import-candidates! ds {:import-source-id (:id import-source)
                                           :events events})
            finished-at (now-str now)
            finished-run (db/finish-import-run!
                          ds
                          (:id run)
                          {:finished-at finished-at
                           :status :success
                           :fetched-count (:fetched result)
                           :work-logs-created-count (:work-logs-created result)})]
        (db/update-import-source-last-fetched! ds (:id import-source) finished-at)
        {:run finished-run :result result})
      (catch Exception e
        (let [finished-run (db/finish-import-run!
                            ds
                            (:id run)
                            {:finished-at (now-str now)
                             :status :failed
                             :error (.getMessage e)})]
          {:run finished-run
           :error e
           :result {:fetched 0
                    :source-events-upserted 0
                    :work-logs-created 0}})))))

(defn- due? [now import-source]
  (and (:enabled? import-source)
       (or (nil? (:last-fetched-at import-source))
           (not (pos? (compare (.plus (Instant/parse (:last-fetched-at import-source))
                                      (Duration/ofMinutes (:fetch-interval-minutes import-source)))
                                now))))))

(defn run-due-fetches!
  ([ds] (run-due-fetches! ds (Instant/now)))
  ([ds now]
   (->> (db/list-import-sources ds)
        (filter #(due? now %))
        (mapv #(fetch-import-source! ds % now)))))
