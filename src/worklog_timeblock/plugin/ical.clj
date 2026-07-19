(ns worklog-timeblock.plugin.ical
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [worklog-timeblock.plugin.protocol :as plugin])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.net URL]
           [java.time Duration Instant LocalDate LocalDateTime OffsetDateTime ZoneId ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Optional]
           [net.fortuna.ical4j.data CalendarBuilder]))

(def compact-date-time-format
  (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss"))

(defn- unwrap-optional [value]
  (if (instance? Optional value)
    (.orElse ^Optional value nil)
    value))

(defn- prop-value [prop]
  (some-> prop unwrap-optional .getValue))

(defn- prop-date [prop]
  (some-> prop unwrap-optional .getDate))

(defn- prop-parameter-value [prop parameter-name]
  (some-> prop
          unwrap-optional
          (.getParameter parameter-name)
          unwrap-optional
          .getValue))

(defn- properties [event property-name]
  (filter #(= property-name (.getName %)) (.getProperties event)))

(defn- offset-date-time [temporal]
  (cond
    (instance? OffsetDateTime temporal) temporal
    (instance? ZonedDateTime temporal) (.toOffsetDateTime ^ZonedDateTime temporal)
    (instance? Instant temporal) (.atOffset ^Instant temporal ZoneOffset/UTC)
    (instance? LocalDateTime temporal) (.atOffset ^LocalDateTime temporal ZoneOffset/UTC)
    (instance? LocalDate temporal) nil
    :else nil))

(defn- timezone [dt-start starts-at]
  (or (prop-parameter-value dt-start "TZID")
      (when (= ZoneOffset/UTC (.getOffset ^OffsetDateTime starts-at)) "UTC")
      (str (.getOffset ^OffsetDateTime starts-at))))

(defn- event-sequence [event]
  (some-> (.getSequence event) prop-value parse-long))

(defn- cancelled? [event]
  (= "CANCELLED" (some-> (.getStatus event) prop-value str/upper-case)))

(defn- parse-rrule [event]
  (when-let [value (some-> (first (properties event "RRULE")) prop-value)]
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=" 2)]
                    (when (and k v)
                      [(str/upper-case k) (str/upper-case v)]))))
          (str/split value #";"))))

(defn- parse-compact-date-time [value tzid fallback-offset]
  (when-not (str/blank? value)
    (cond
      (str/ends-with? value "Z")
      (.atOffset (LocalDateTime/parse (subs value 0 (dec (count value)))
                                      compact-date-time-format)
                 ZoneOffset/UTC)

      tzid
      (.toOffsetDateTime
       (.atZone (LocalDateTime/parse value compact-date-time-format)
                (ZoneId/of tzid)))

      :else
      (.atOffset (LocalDateTime/parse value compact-date-time-format)
                 fallback-offset))))

(defn- exdate-starts [event dt-start starts-at]
  (let [fallback-offset (.getOffset ^OffsetDateTime starts-at)]
    (->> (properties event "EXDATE")
         (mapcat (fn [prop]
                   (let [tzid (or (prop-parameter-value prop "TZID")
                                  (prop-parameter-value dt-start "TZID"))]
                     (map #(parse-compact-date-time % tzid fallback-offset)
                          (str/split (prop-value prop) #",")))))
         (keep identity)
         (map str)
         set)))

(defn- recurrence-starts [starts-at rrule]
  (if-let [freq (get rrule "FREQ")]
    (let [count (or (some-> (get rrule "COUNT") parse-long) 1)
          step (case freq
                 "DAILY" #(.plusDays ^OffsetDateTime % 1)
                 "WEEKLY" #(.plusWeeks ^OffsetDateTime % 1)
                 identity)]
      (take count (iterate step starts-at)))
    [starts-at]))

(defn- occurrence-candidate [base starts-at duration recurring?]
  (let [ends-at (.plus ^OffsetDateTime starts-at duration)]
    (cond-> (assoc base
                  :starts-at (str starts-at)
                  :ends-at (str ends-at))
      recurring? (assoc :external-id (str (:external-id base) "#" starts-at)))))

(defn- event->candidate [source-id event]
  (when-not (cancelled? event)
    (let [dt-start (.getDateTimeStart event)
          dt-end (.getDateTimeEnd event)
          starts-at (offset-date-time (prop-date dt-start))
          ends-at (offset-date-time (prop-date dt-end))]
      (when (and starts-at ends-at)
        {:source-id source-id
         :external-id (or (prop-value (.getUid event))
                          (str (prop-value (.getSummary event))
                               ":"
                               starts-at))
         :title (or (prop-value (.getSummary event)) "(untitled)")
         :starts-at (str starts-at)
         :ends-at (str ends-at)
         :timezone (timezone dt-start starts-at)
         :updated-at (some-> (.getLastModified event) prop-date str)
         :sequence (event-sequence event)}))))

(defn- event->candidates [source-id event]
  (when-let [base (event->candidate source-id event)]
    (let [dt-start (.getDateTimeStart event)
          starts-at (OffsetDateTime/parse (:starts-at base))
          ends-at (OffsetDateTime/parse (:ends-at base))
          duration (Duration/between starts-at ends-at)
          rrule (parse-rrule event)
          exdates (exdate-starts event dt-start starts-at)
          recurring? (some? rrule)]
      (->> (recurrence-starts starts-at rrule)
           (remove #(contains? exdates (str %)))
           (map #(occurrence-candidate base % duration recurring?))))))

(defn- event-date [candidate]
  (str (.toLocalDate (OffsetDateTime/parse (:starts-at candidate)))))

(defn- rank [candidate]
  [(or (some-> (:updated-at candidate) Instant/parse) Instant/EPOCH)
   (or (:sequence candidate) 0)])

(defn- newer-candidate [left right]
  (if (pos? (compare (rank right) (rank left)))
    right
    left))

(defn- dedupe-candidates [candidates]
  (->> candidates
       (reduce (fn [acc candidate]
                 (update acc
                         [(:source-id candidate) (:external-id candidate)]
                         (fn [existing]
                           (if existing
                             (newer-candidate existing candidate)
                             candidate))))
               {})
       vals
       (sort-by (juxt :starts-at :external-id))
       vec))

(defn- unfold-content [content]
  (str/replace content #"\r?\n[ \t]" ""))

(defn- input-stream [content]
  (ByteArrayInputStream. (.getBytes content "UTF-8")))

(defn- parse-stream [source-id ^InputStream stream]
  (let [calendar (.build (CalendarBuilder.) (input-stream (unfold-content (slurp stream))))]
    (->> (.getComponents calendar)
         (filter #(= "VEVENT" (.getName %)))
         (mapcat #(event->candidates source-id %))
         dedupe-candidates)))

(defn- filter-query [candidates query]
  (let [date (:date query)]
    (cond->> candidates
      date (filter #(= date (event-date %)))
      true vec)))

(defrecord ICalSource [id open-stream]
  plugin/EventSource
  (source-id [_] id)
  (candidate-events [_ query]
    (with-open [stream (open-stream)]
      (filter-query (parse-stream id stream) query))))

(defn from-resource [id resource-name]
  (let [resource (io/resource resource-name)]
    (when-not resource
      (throw (ex-info "iCal resource not found" {:resource resource-name})))
    (->ICalSource id #(.openStream resource))))

(defn from-file [id path]
  (->ICalSource id #(io/input-stream (io/file path))))

(defn from-url [id url]
  (->ICalSource id #(.openStream (URL. url))))

(defn from-uri [id uri]
  (cond
    (str/starts-with? uri "http://") (from-url id uri)
    (str/starts-with? uri "https://") (from-url id uri)
    (str/starts-with? uri "file:") (->ICalSource id #(io/input-stream (URL. uri)))
    :else (from-file id uri)))
