(ns worklog-timeblock.tui.main
  (:gen-class)
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]
            [worklog-timeblock.domain.summary :as summary]))

(def default-summary-options
  {:rounding-minutes 15
   :small-gap-minutes 15
   :other-category-id "other"})

(defn- time-string [minute]
  (format "%02d:%02d" (quot minute 60) (mod minute 60)))

(defn- char-width [ch]
  (if (< (int ch) 128) 1 2))

(defn- display-width [s]
  (reduce + (map char-width (str s))))

(defn- take-display-width [limit s]
  (loop [result []
         width 0
         chars (seq (str s))]
    (if-let [ch (first chars)]
      (let [next-width (+ width (char-width ch))]
        (if (<= next-width limit)
          (recur (conj result ch) next-width (next chars))
          (apply str result)))
      (apply str result))))

(defn- fit-line [columns line]
  (let [line (str line)]
    (if (<= (display-width line) columns)
      line
      (str (take-display-width (max 0 (dec columns)) line) "~"))))

(defn- log-line [columns log]
  (let [time-range (str (time-string (:start-minute log))
                        "-"
                        (time-string (:end-minute log)))
        state (name (:state log))
        line (if (< columns 48)
               (format "%s %s %s" time-range state (:title log))
               (format "%s  %-15s  %s" time-range state (:title log)))]
    (fit-line columns line)))

(defn- hour-line [columns [category-id hours]]
  (let [line (if (< columns 48)
               (format "%s %.2fh" category-id (double hours))
               (format "%-20s %.2fh" category-id (double hours)))]
    (fit-line columns line)))

(defn- warning-line [warning]
  (case (:type warning)
    :uncategorized (str "Uncategorized: " (:title warning))
    :large-gap (str "Large gap: " (:minutes warning) " minutes")
    (str warning)))

(defn render-dashboard
  ([state] (render-dashboard {:columns 80} state))
  ([{:keys [columns]} {:keys [date work-logs summary]}]
   (let [columns (max 20 (or columns 80))]
     (str/join
      "\n"
      (concat
       [(fit-line columns (str "worklog-timeblock  " date))
        ""
        "Timeline"]
       (map #(log-line columns %) work-logs)
       [""
        "Category totals"]
       (map #(hour-line columns %) (:category-hours summary))
       (when (seq (:warnings summary))
         (concat ["" "Warnings"]
                 (map #(fit-line columns (warning-line %)) (:warnings summary)))))))))

(defn- parse-positive-int [value]
  (when value
    (try
      (let [parsed (parse-long value)]
        (when (pos-int? parsed) parsed))
      (catch NumberFormatException _ nil))))

(defn- terminal-columns []
  (or (parse-positive-int (System/getenv "COLUMNS")) 80))

(defn render-dashboard-for-terminal [state]
  (render-dashboard {:columns (terminal-columns)} state))

(def cli-options
  [["-d" "--db PATH" "SQLite database path" :default "./data/app.db"]
   ["-D" "--date DATE" "Date to show"]])

(defn -main [& args]
  (let [{:keys [options errors]} (cli/parse-opts args cli-options)]
    (when (seq errors)
      (binding [*out* *err*]
        (doseq [error errors] (println error)))
      (System/exit 1))
    (let [ds (db/datasource (:db options))]
      (migration/migrate! ds)
      (let [date (or (:date options) (first (db/list-dates ds)) "2026-07-06")
            work-logs (db/work-logs-by-date ds date)]
        (print
         (render-dashboard-for-terminal
          {:date date
           :work-logs work-logs
           :summary (summary/summarize-day default-summary-options work-logs)}))))))
