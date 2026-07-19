(ns worklog-timeblock.domain.worklog
  (:require [malli.core :as m])
  (:import [java.time OffsetDateTime]))

(def candidate-event-schema
  [:map
   [:source-id :string]
   [:external-id :string]
   [:title :string]
   [:starts-at :string]
   [:ends-at :string]
   [:timezone :string]
   [:updated-at {:optional true} :string]])

(def work-log-schema
  [:map
   [:date :string]
   [:title :string]
   [:start-minute :int]
   [:end-minute :int]
   [:state [:enum :confirmed :uncategorized :excluded]]
   [:category-id {:optional true} [:maybe [:or :int :string]]]
   [:source-id {:optional true} [:maybe :string]]
   [:external-id {:optional true} [:maybe :string]]
   [:source-updated-at {:optional true} [:maybe :string]]])

(defn- parse-offset [value]
  (OffsetDateTime/parse value))

(defn- minute-of-day [^OffsetDateTime value]
  (+ (* (.getHour value) 60) (.getMinute value)))

(defn- date-string [^OffsetDateTime value]
  (str (.toLocalDate value)))

(defn- normalize-state [state]
  (cond
    (keyword? state) state
    (string? state) (keyword state)
    :else state))

(defn- mapping-for [mappings title]
  (when-let [mapping (get mappings title)]
    (update mapping :state normalize-state)))

(defn candidate->worklog
  "Turn a source candidate event into a work-log snapshot.
  Known title mappings may auto-confirm or exclude. Unknown titles remain
  uncategorized so they are visible before manual entry."
  [mappings candidate]
  (when-not (m/validate candidate-event-schema candidate)
    (throw (ex-info "Invalid candidate event" {:candidate candidate})))
  (let [start (parse-offset (:starts-at candidate))
        end (parse-offset (:ends-at candidate))]
    (when-not (.isAfter end start)
      (throw (ex-info "Candidate event must end after it starts"
                      {:candidate candidate})))
    (let [mapping (mapping-for mappings (:title candidate))
          state (or (:state mapping) :uncategorized)
          category-id (when (= :confirmed state) (:category-id mapping))
          log {:date (date-string start)
               :title (:title candidate)
               :start-minute (minute-of-day start)
               :end-minute (minute-of-day end)
               :state state
               :category-id category-id
               :source-id (:source-id candidate)
               :external-id (:external-id candidate)
               :source-updated-at (:updated-at candidate)}]
      (when-not (m/validate work-log-schema log)
        (throw (ex-info "Invalid work log generated from candidate"
                        {:candidate candidate :work-log log})))
      log)))

(defn assign-category [work-log category-id]
  (assoc work-log :state :confirmed :category-id category-id))

(defn exclude [work-log]
  (assoc work-log :state :excluded :category-id nil))

(defn change-range [work-log start-minute end-minute]
  (when-not (> end-minute start-minute)
    (throw (ex-info "Work log must end after it starts"
                    {:start-minute start-minute :end-minute end-minute})))
  (assoc work-log :start-minute start-minute :end-minute end-minute))

(defn stale-source? [work-log source-event]
  (let [snapshot (:source-updated-at work-log)
        current (:updated-at source-event)]
    (boolean (and snapshot current (not= snapshot current)))))
