(ns worklog-timeblock.domain.summary
  (:require [clojure.math :as math]
            [worklog-timeblock.domain.worklog :as worklog]))

(defn- normalize-state [state]
  (cond
    (keyword? state) state
    (string? state) (keyword state)
    :else state))

(defn- duration [log]
  (- (:end-minute log) (:start-minute log)))

(defn- round2 [value]
  (/ (math/round (* 100.0 value)) 100.0))

(defn- work-candidate? [log]
  (not= :excluded (normalize-state (:state log))))

(defn- confirmed-with-category? [log]
  (and (= :confirmed (normalize-state (:state log)))
       (some? (:category-id log))))

(defn- add-minutes [m category-id minutes]
  (if (pos? minutes)
    (update m category-id (fnil + 0) minutes)
    m))

(defn- rounded-category-minutes [rounding-minutes log]
  (* rounding-minutes (quot (duration log) rounding-minutes)))

(defn- adjacent-gaps [logs]
  (->> logs
       (sort-by :start-minute)
       (partition 2 1)
       (keep (fn [[left right]]
               (let [gap (- (:start-minute right) (:end-minute left))]
                 (when (pos? gap)
                   {:start-minute (:end-minute left)
                    :end-minute (:start-minute right)
                    :minutes gap}))))))

(defn summarize-day
  "Summarize a single day for manual entry.
  Confirmed categorized logs are rounded down to the configured quantum.
  Residual minutes and short gaps go to the other category. Larger gaps and
  uncategorized work remain warnings."
  [options logs]
  (let [rounding-minutes (:rounding-minutes options)
        small-gap-minutes (:small-gap-minutes options)
        other-category-id (:other-category-id options)]
    (when-not (pos-int? rounding-minutes)
      (throw (ex-info "rounding-minutes must be positive" options)))
    (let [included (filter work-candidate? logs)
          confirmed (filter confirmed-with-category? included)
          uncategorized (filter #(= :uncategorized (normalize-state (:state %))) included)
          category-minutes (reduce
                            (fn [acc log]
                              (add-minutes acc (:category-id log)
                                           (rounded-category-minutes rounding-minutes log)))
                            {}
                            confirmed)
          residual-minutes (reduce
                            (fn [acc log]
                              (+ acc (- (duration log)
                                        (rounded-category-minutes rounding-minutes log))))
                            0
                            confirmed)
          gaps (adjacent-gaps included)
          short-gap-minutes (reduce + 0 (map :minutes
                                             (filter #(<= (:minutes %) small-gap-minutes) gaps)))
          large-gaps (filter #(> (:minutes %) small-gap-minutes) gaps)
          other-minutes (+ residual-minutes short-gap-minutes)
          category-minutes (add-minutes category-minutes other-category-id other-minutes)
          warnings (vec
                    (concat
                     (map (fn [log]
                            {:type :uncategorized
                             :work-log-id (:id log)
                             :title (:title log)})
                          uncategorized)
                     (map (fn [gap]
                            (assoc gap :type :large-gap))
                          large-gaps)))]
      {:category-minutes category-minutes
       :category-hours (into {}
                             (map (fn [[category-id minutes]]
                                    [category-id (round2 (/ minutes 60.0))]))
                             category-minutes)
       :uncategorized (vec uncategorized)
       :warnings warnings
       :other {:category-id other-category-id
               :rounding-residual-minutes residual-minutes
               :short-gap-minutes short-gap-minutes
               :total-minutes other-minutes}})))

(defn source-diff-warnings [work-logs source-events]
  (let [events-by-id (into {} (map (juxt :external-id identity)) source-events)]
    (->> work-logs
         (keep (fn [log]
                 (when-let [event (get events-by-id (:external-id log))]
                   (when (worklog/stale-source? log event)
                     {:type :source-updated
                      :work-log-id (:id log)
                      :external-id (:external-id log)}))))
         vec)))
