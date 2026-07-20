(ns worklog-timeblock.domain.export
  (:require [clojure.string :as str]))

(def valid-formats #{:org :markdown})

(defn normalize-format [value]
  (let [value (cond
                (keyword? value) value
                (string? value) (keyword (str/lower-case (str/trim value)))
                :else nil)]
    (when (contains? valid-formats value)
      value)))

(defn extension [format]
  (case (or (normalize-format format) :org)
    :markdown "md"
    "org"))

(defn- confirmed? [work-log]
  (= :confirmed
     (cond
       (keyword? (:state work-log)) (:state work-log)
       (string? (:state work-log)) (keyword (:state work-log))
       :else (:state work-log))))

(defn- confirmed-logs [work-logs]
  (->> work-logs
       (filter confirmed?)
       (sort-by (juxt :start-minute :end-minute :id))))

(defn- time-string [minute]
  (format "%02d:%02d" (quot minute 60) (mod minute 60)))

(defn- range-string [{:keys [start-minute end-minute]}]
  (str (time-string start-minute) "-" (time-string end-minute)))

(defn- hours-string [{:keys [start-minute end-minute]}]
  (format "%.2f" (/ (- end-minute start-minute) 60.0)))

(defn- single-line [value]
  (-> (str value)
      (str/replace #"\s+" " ")
      str/trim))

(defn- markdown-cell [value]
  (str/replace (single-line value) "|" "\\|"))

(defn- categories-by-id [categories]
  (into {} (map (juxt :id identity)) categories))

(defn- category-path [categories-map category-id]
  (loop [category (get categories-map category-id)
         seen #{}
         parts ()]
    (cond
      (nil? category)
      (if (seq parts)
        (str/join " / " parts)
        "Uncategorized")

      (contains? seen (:id category))
      (str/join " / " parts)

      :else
      (recur (get categories-map (:parent-id category))
             (conj seen (:id category))
             (cons (:name category) parts)))))

(defn- render-org-entry [categories-map work-log]
  (str "** " (range-string work-log) " " (single-line (:title work-log)) "\n"
       ":PROPERTIES:\n"
       ":CATEGORY: " (category-path categories-map (:category-id work-log)) "\n"
       ":HOURS: " (hours-string work-log) "\n"
       ":END:\n"))

(defn- render-org [{:keys [date categories work-logs]}]
  (let [categories-map (categories-by-id categories)]
    (str "* " date "\n"
         (apply str (map #(render-org-entry categories-map %)
                         (confirmed-logs work-logs))))))

(defn- render-markdown-row [categories-map work-log]
  (str "| " (range-string work-log)
       " | " (markdown-cell (:title work-log))
       " | " (markdown-cell (category-path categories-map (:category-id work-log)))
       " | " (hours-string work-log)
       " |\n"))

(defn- render-markdown [{:keys [date categories work-logs]}]
  (let [logs (confirmed-logs work-logs)]
    (if (seq logs)
      (let [categories-map (categories-by-id categories)]
        (str "# " date "\n\n"
             "| Time | Title | Category | Hours |\n"
             "| --- | --- | --- | ---: |\n"
             (apply str (map #(render-markdown-row categories-map %) logs))))
      (str "# " date "\n\nNo confirmed work.\n"))))

(defn render [format payload]
  (case (or (normalize-format format) :org)
    :markdown (render-markdown payload)
    (render-org payload)))
