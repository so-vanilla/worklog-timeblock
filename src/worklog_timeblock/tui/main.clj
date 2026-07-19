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

(defn- categories-by-id [categories]
  (into {} (map (juxt :id identity)) categories))

(defn- category-name [categories category-id]
  (cond
    (= summary/uncategorized-category-id category-id) "Uncategorized"
    :else (or (get-in categories [category-id :name]) category-id "(uncategorized)")))

(defn- hour-line [columns categories [category-id hours]]
  (let [name (category-name categories category-id)
        line (if (< columns 48)
               (format "%s %.2fh" name (double hours))
               (format "%-20s %.2fh" name (double hours)))]
    (fit-line columns line)))

(defn- warning-line [warning]
  (case (:type warning)
    :uncategorized (str "Uncategorized: " (:title warning))
    :large-gap (str "Large gap: " (:minutes warning) " minutes")
    :source-updated (str "Source updated: " (:external-id warning))
    (str warning)))

(defn render-dashboard
  ([state] (render-dashboard {:columns 80} state))
  ([{:keys [columns]} {:keys [date work-logs summary categories]}]
   (let [columns (max 20 (or columns 80))
         categories (categories-by-id categories)]
     (str/join
      "\n"
      (concat
       [(fit-line columns (str "worklog-timeblock  " date))
        ""
        "Timeline"]
       (map #(log-line columns %) work-logs)
       [""
        "Category totals"]
       (map #(hour-line columns categories %) (:category-hours summary))
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
            work-logs (db/work-logs-by-date ds date)
            source-events (db/source-events-by-date ds date)
            day-summary (summary/summarize-day
                         (assoc default-summary-options
                                :other-category-id (or (db/other-category-id ds) "other")
                                :assignable-category-ids (db/summarizable-category-ids ds))
                         work-logs)]
        (print
         (render-dashboard-for-terminal
          {:date date
           :work-logs work-logs
           :categories (db/list-categories ds)
           :summary (update day-summary :warnings into
                            (summary/source-diff-warnings work-logs source-events))}))))))
