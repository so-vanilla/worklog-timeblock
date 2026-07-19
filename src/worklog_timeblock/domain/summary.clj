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

(defn- active-break? [break]
  (not (false? (:active? break))))

(defn- round2 [value]
  (/ (math/round (* 100.0 value)) 100.0))

(defn- work-candidate? [log]
  (not= :excluded (normalize-state (:state log))))

(defn- confirmed-with-category? [assignable-category-ids log]
  (and (= :confirmed (normalize-state (:state log)))
       (some? (:category-id log))
       (or (nil? assignable-category-ids)
           (contains? assignable-category-ids (:category-id log)))))

(defn- confirmed-with-non-assignable-category? [assignable-category-ids log]
  (and assignable-category-ids
       (= :confirmed (normalize-state (:state log)))
       (some? (:category-id log))
       (not (contains? assignable-category-ids (:category-id log)))))

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

(defn- subtract-interval [segments interval]
  (mapcat
   (fn [segment]
     (let [start (:start-minute segment)
           end (:end-minute segment)
           break-start (:start-minute interval)
           break-end (:end-minute interval)]
       (cond
         (or (<= break-end start) (<= end break-start))
         [segment]

         (and (<= break-start start) (<= end break-end))
         []

         :else
         (cond-> []
           (< start break-start) (conj {:start-minute start
                                        :end-minute break-start
                                        :minutes (- break-start start)})
           (< break-end end) (conj {:start-minute break-end
                                    :end-minute end
                                    :minutes (- end break-end)})))))
   segments))

(defn- break-intervals [breaks]
  (->> breaks
       (filter active-break?)
       (map #(select-keys % [:start-minute :end-minute]))
       (sort-by :start-minute)))

(defn- uncovered-gaps [gaps breaks]
  (let [breaks (break-intervals breaks)]
    (mapcat (fn [gap]
              (reduce subtract-interval [gap] breaks))
            gaps)))

(defn- confirmed-work-minutes [logs]
  (reduce + 0
          (map duration
               (filter #(= :confirmed (normalize-state (:state %))) logs))))

(defn- break-minutes [breaks]
  (reduce + 0
          (map #(- (:end-minute %) (:start-minute %))
               (filter active-break? breaks))))

(defn- attendance-summary [attendance logs breaks]
  (let [clock-in (:clock-in-minute attendance)
        clock-out (:clock-out-minute attendance)
        span-minutes (when (and clock-in clock-out (< clock-in clock-out))
                       (- clock-out clock-in))
        confirmed-minutes (confirmed-work-minutes logs)
        break-minutes (break-minutes breaks)
        unallocated (when span-minutes
                      (max 0 (- span-minutes confirmed-minutes break-minutes)))]
    {:clock-in-minute clock-in
     :clock-out-minute clock-out
     :span-minutes (or span-minutes 0)
     :confirmed-work-minutes confirmed-minutes
     :break-minutes break-minutes
     :unallocated-minutes (or unallocated 0)}))

(defn summarize-day
  "Summarize a single day for manual entry.
  Confirmed categorized logs are rounded down to the configured quantum.
  Residual minutes and short gaps go to the other category. Larger gaps and
  uncategorized work remain warnings."
  [options logs]
  (let [rounding-minutes (:rounding-minutes options)
        small-gap-minutes (:small-gap-minutes options)
        other-category-id (:other-category-id options)
        assignable-category-ids (:assignable-category-ids options)
        breaks (:breaks options)
        attendance (:attendance options)]
    (when-not (pos-int? rounding-minutes)
      (throw (ex-info "rounding-minutes must be positive" options)))
    (let [included (filter work-candidate? logs)
          confirmed (filter #(confirmed-with-category? assignable-category-ids %) included)
          non-assignable (filter #(confirmed-with-non-assignable-category?
                                   assignable-category-ids %)
                                 included)
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
          gaps (uncovered-gaps (adjacent-gaps included) breaks)
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
                     (map (fn [log]
                            {:type :non-assignable-category
                             :work-log-id (:id log)
                             :title (:title log)
                             :category-id (:category-id log)})
                          non-assignable)
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
       :attendance (attendance-summary attendance included breaks)
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
