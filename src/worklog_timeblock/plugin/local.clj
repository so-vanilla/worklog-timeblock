(ns worklog-timeblock.plugin.local
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [worklog-timeblock.plugin.protocol :as plugin])
  (:import [java.time OffsetDateTime]))

(defn- event-date [event]
  (str (.toLocalDate (OffsetDateTime/parse (:starts-at event)))))

(defrecord LocalEventSource [id events]
  plugin/EventSource
  (source-id [_] id)
  (candidate-events [_ query]
    (let [date (:date query)]
      (cond->> events
        date (filter #(= date (event-date %)))
        true vec))))

(defn from-events
  ([events] (from-events "local" events))
  ([id events]
   (->LocalEventSource id (vec events))))

(defn from-resource [resource-name]
  (let [resource (io/resource resource-name)]
    (when-not resource
      (throw (ex-info "Local event resource not found"
                      {:resource resource-name})))
    (from-events (edn/read-string (slurp resource)))))

(defn from-file [path]
  (from-events (edn/read-string (slurp (io/file path)))))
