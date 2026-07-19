(ns worklog-timeblock.api.routes
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.domain.summary :as summary]
            [worklog-timeblock.domain.worklog :as worklog]
            [worklog-timeblock.importer.core :as importer]
            [worklog-timeblock.web.pages :as pages])
  (:import [java.net URLDecoder URLEncoder]
           [java.time LocalDate LocalTime]))

(def default-summary-options
  {:rounding-minutes 15
   :small-gap-minutes 15
   :other-category-id "other"})

(def valid-states #{:confirmed :uncategorized :excluded})

(declare active-edit? calendar-dates create-work-log! date-string day-of-week-value
         normalize-bool normalize-view reference-date)

(defn- parse-json-body [request]
  (if-let [body (:body request)]
    (let [content (slurp body)]
      (if (str/blank? content)
        {}
        (json/parse-string content keyword)))
    {}))

(defn- decode-form-component [value]
  (URLDecoder/decode (or value "") "UTF-8"))

(defn- parse-form-body [request]
  (if-let [body (:body request)]
    (let [content (slurp body)]
      (if (str/blank? content)
        {}
        (into {}
              (map (fn [part]
                     (let [[k v] (str/split part #"=" 2)]
                       [(keyword (decode-form-component k))
                        (decode-form-component v)])))
              (str/split content #"&"))))
    {}))

(defn- parse-query-params [request]
  (let [content (:query-string request)]
    (if (str/blank? content)
      {}
      (into {}
            (map (fn [part]
                   (let [[k v] (str/split part #"=" 2)]
                     [(keyword (decode-form-component k))
                      (decode-form-component v)])))
            (str/split content #"&")))))

(defn- json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"content-type" "application/json; charset=utf-8"}
    :body (json/generate-string body)}))

(defn- html-response [body]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn- redirect-response [location]
  {:status 303
   :headers {"location" location}
   :body ""})

(defn- encode-query-component [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn- append-query-param [location key value]
  (let [[path fragment] (str/split location #"#" 2)
        separator (if (str/includes? path "?") "&" "?")
        with-query (str path separator
                        (encode-query-component (name key))
                        "="
                        (encode-query-component value))]
    (str with-query (when fragment (str "#" fragment)))))

(defn- response-error-code [response]
  (try
    (let [body (json/parse-string (str (:body response)) keyword)]
      (or (:reason body)
          (:error body)
          (str "http-" (:status response))))
    (catch Exception _
      (str "http-" (:status response)))))

(defn- form-warning-redirect [location response]
  (redirect-response (append-query-param location :warning
                                         (response-error-code response))))

(defn- invalid-work-log-response [reason]
  (json-response 400 {:error "invalid-work-log" :reason reason}))

(defn- invalid-category-response [reason]
  (json-response 400 {:error "invalid-category" :reason reason}))

(defn- invalid-import-source-response [reason]
  (json-response 400 {:error "invalid-import-source" :reason reason}))

(defn- invalid-attendance-response [reason]
  (json-response 400 {:error "invalid-attendance" :reason reason}))

(defn- invalid-break-response [reason]
  (json-response 400 {:error "invalid-break" :reason reason}))

(defn- import-candidates! [ds events]
  (let [result (importer/import-candidates! ds {:events events})]
    (assoc result :imported (:fetched result))))

(defn- summary-options [ds]
  (let [unallocated-category-ids (db/unallocated-category-ids ds)]
    (assoc default-summary-options
           :other-category-id (or (db/other-category-id ds)
                                  (db/unallocated-category-id ds)
                                  "other")
           :assignable-category-ids (db/summarizable-category-ids ds)
           :unallocated-category-ids unallocated-category-ids)))

(defn- rule->virtual-break [date rule]
  {:id nil
   :date date
   :title (:title rule)
   :start-minute (:start-minute rule)
   :end-minute (:end-minute rule)
   :break-rule-id (:id rule)
   :active? true})

(defn- summary-breaks [ds date break-mode]
  (let [breaks (db/breaks-by-date ds date)]
    (if (= :fixed break-mode)
      (let [materialized-rule-ids (db/break-rule-ids-by-date ds date)
            virtual-breaks (->> (db/list-break-rules ds)
                                (filter :enabled?)
                                (remove #(contains? materialized-rule-ids (:id %)))
                                (mapv #(rule->virtual-break date %)))]
        (vec (sort-by (juxt :start-minute #(or (:id %) 0))
                      (concat breaks virtual-breaks))))
      breaks)))

(defn- day-state [ds date]
  (let [break-mode (db/break-mode ds)]
    (when (= :fixed break-mode)
      (db/materialize-breaks-for-date! ds date))
    (let [work-logs (db/work-logs-by-date ds date)
        source-events (db/source-events-by-date ds date)
        attendance (db/get-attendance ds date)
        breaks (db/breaks-by-date ds date)
        summary (summary/summarize-day (assoc (summary-options ds)
                                              :attendance attendance
                                              :breaks breaks)
                                       work-logs)]
      {:date date
       :break-mode break-mode
       :work-logs work-logs
       :source-events source-events
       :attendance attendance
       :breaks breaks
       :summary (update summary :warnings into
                        (summary/source-diff-warnings work-logs source-events))})))

(defn- holiday-policy-json [policy]
  {:mode (name (:mode policy))
   :weekdays (vec (sort (:weekdays policy)))})

(defn- settings-json [ds]
  {:break-mode (name (db/break-mode ds))
   :week-start-day (db/week-start-day ds)
   :fiscal-month-start-day (db/fiscal-month-start-day ds)
   :holiday-policy (holiday-policy-json (db/holiday-policy ds))})

(defn- holiday-or-workday [policy override date]
  (cond
    (:status override) (:status override)
    (contains? (:weekdays policy) (day-of-week-value date)) :holiday
    :else :workday))

(defn- future-date? [date today]
  (.isAfter (LocalDate/parse date) today))

(defn- meaningful-work-log? [log]
  (not= :excluded (:state log)))

(defn- classify-calendar-status [date today base-status attendance work-logs summary]
  (if (= :holiday base-status)
    :holiday
    (let [unallocated (+ (get-in summary [:attendance :unallocated-minutes] 0)
                         (get-in summary [:other :unallocated-category-minutes] 0))]
      (cond
        (>= unallocated 60) :missing
        (and (nil? attendance)
             (not (future-date? date today))
             (not-any? meaningful-work-log? work-logs)) :missing
        (future-date? date today) :workday
        :else :done))))

(defn- calendar-day-state [ds policy today date]
  (let [break-mode (db/break-mode ds)
        work-logs (db/work-logs-by-date ds date)
        attendance (db/get-attendance ds date)
        breaks (summary-breaks ds date break-mode)
        summary (summary/summarize-day (assoc (summary-options ds)
                                              :attendance attendance
                                              :breaks breaks)
                                       work-logs)
        override (db/get-day-status-override ds date)
        base-status (holiday-or-workday policy override date)
        status (classify-calendar-status date today base-status attendance work-logs summary)]
    {:date date
     :day-of-month (.getDayOfMonth (LocalDate/parse date))
     :weekday (day-of-week-value date)
     :status (name status)
     :base-status (name base-status)
     :override-status (some-> (:status override) name)
     :unallocated-minutes (get-in summary [:attendance :unallocated-minutes] 0)
     :unallocated-category-minutes (get-in summary [:other :unallocated-category-minutes] 0)
     :uncategorized-minutes (get-in summary [:other :uncategorized-minutes] 0)
     :confirmed-work-minutes (get-in summary [:attendance :confirmed-work-minutes] 0)
     :break-minutes (get-in summary [:attendance :break-minutes] 0)
     :category-hours (:category-hours summary)
     :category-minutes (:category-minutes summary)}))

(defn- calendar-state [ds request]
  (let [params (parse-query-params request)
        view (normalize-view (:view params))
        date (reference-date params)
        week-start-day (db/week-start-day ds)
        fiscal-month-start-day (db/fiscal-month-start-day ds)
        dates (calendar-dates view date week-start-day fiscal-month-start-day)
        categories (db/list-categories ds)
        policy (db/holiday-policy ds)
        today (LocalDate/now)]
    {:view view
     :edit? (active-edit? (:edit params))
     :reference-date (date-string date)
     :period-start (first dates)
     :period-end (last dates)
     :month (subs (date-string (.withDayOfMonth date 1)) 0 7)
     :break-mode (name (db/break-mode ds))
     :week-start-day week-start-day
     :fiscal-month-start-day fiscal-month-start-day
     :holiday-policy (holiday-policy-json policy)
     :flash-warning (:warning params)
     :days (mapv #(calendar-day-state ds policy today %) dates)
     :categories categories}))

(defn- parse-id [value]
  (cond
    (integer? value) value
    (string? value) (try
                      (parse-long value)
                      (catch Exception _
                        nil))
    :else nil))

(defn- normalize-state [state]
  (cond
    (keyword? state) state
    (string? state) (keyword state)
    (nil? state) nil
    :else (keyword (str state))))

(defn- normalize-category-id [category-id]
  (cond
    (keyword? category-id) (name category-id)
    :else (db/normalize-category-id category-id)))

(defn- valid-minute? [minute]
  (and (integer? minute) (<= 0 minute 1440)))

(defn- ranges-overlap? [a-start a-end b-start b-end]
  (and (< a-start b-end)
       (< b-start a-end)))

(defn- normalize-text [value]
  (when value
    (let [value (str/trim (str value))]
      (when-not (str/blank? value)
        value))))

(defn- valid-date-string? [value]
  (boolean (re-matches #"\d{4}-\d{2}-\d{2}" (or value ""))))

(defn- parse-local-date [value]
  (when (valid-date-string? value)
    (try
      (LocalDate/parse value)
      (catch Exception _
        nil))))

(defn- date-string [date]
  (str date))

(defn- dates-between [start-date end-date]
  (let [start (parse-local-date start-date)
        end (parse-local-date end-date)]
    (when (and start end)
      (let [[start end] (if (.isAfter start end) [end start] [start end])]
        (loop [date start
               dates []]
          (if (.isAfter date end)
            dates
            (recur (.plusDays date 1) (conj dates (date-string date)))))))))

(defn- normalize-view [value]
  (if (= "week" value) "week" "month"))

(defn- active-edit? [value]
  (#{"1" "true" "active"} (str/lower-case (or value ""))))

(defn- reference-date [params]
  (or (parse-local-date (:date params))
      (some-> (:month params) (str "-01") parse-local-date)
      (LocalDate/now)))

(defn- fiscal-start-for-month [date fiscal-month-start-day]
  (let [first-day (.withDayOfMonth date 1)
        day (min fiscal-month-start-day (.lengthOfMonth first-day))]
    (.withDayOfMonth first-day day)))

(defn- fiscal-period [date fiscal-month-start-day]
  (let [this-start (fiscal-start-for-month date fiscal-month-start-day)
        start (if (.isBefore date this-start)
                (fiscal-start-for-month (.minusMonths date 1) fiscal-month-start-day)
                this-start)
        next-start (fiscal-start-for-month (.plusMonths start 1) fiscal-month-start-day)]
    [start (.minusDays next-start 1)]))

(defn- month-dates [date fiscal-month-start-day]
  (let [[start end] (fiscal-period date fiscal-month-start-day)]
    (dates-between (date-string start) (date-string end))))

(defn- week-dates [date week-start-day]
  (let [offset (mod (- (.getValue (.getDayOfWeek date)) week-start-day) 7)
        start (.minusDays date offset)]
    (mapv #(date-string (.plusDays start %)) (range 7))))

(defn- calendar-dates [view date week-start-day fiscal-month-start-day]
  (case view
    "week" (week-dates date week-start-day)
    (month-dates date fiscal-month-start-day)))

(defn- day-of-week-value [date]
  (.getValue (.getDayOfWeek (LocalDate/parse date))))

(defn- safe-redirect-path [value fallback]
  (let [value (normalize-text value)]
    (if (and value
             (str/starts-with? value "/")
             (not (str/starts-with? value "//")))
      value
      fallback)))

(defn- normalize-update [ds current attrs]
  (let [state (normalize-state (or (:state attrs) (:state current)))
        requested-category-id (when (contains? attrs :category-id)
                                (normalize-category-id (:category-id attrs)))
        category-id (normalize-category-id
                     (if (contains? attrs :category-id)
                       (when requested-category-id
                         (db/resolve-category-id ds requested-category-id))
                       (:category-id current)))
        start-minute (if (contains? attrs :start-minute)
                       (:start-minute attrs)
                       (:start-minute current))
        end-minute (if (contains? attrs :end-minute)
                     (:end-minute attrs)
                     (:end-minute current))
        updated (cond-> (merge attrs
                               {:state state
                                :category-id category-id
                                :start-minute start-minute
                                :end-minute end-minute})
                  (#{:uncategorized :excluded} state) (assoc :category-id nil))]
    (cond
      (not (contains? valid-states state))
      {:error (invalid-work-log-response "invalid-state")}

      (not (and (valid-minute? start-minute)
                (valid-minute? end-minute)
                (< start-minute end-minute)))
      {:error (invalid-work-log-response "invalid-time-range")}

      (and requested-category-id
           (nil? (:category-id updated)))
      {:error (invalid-work-log-response "unknown-category")}

      (and (= :confirmed state)
           (nil? (:category-id updated)))
      {:error (invalid-work-log-response "category-required")}

      (and (:category-id updated)
           (not (db/category-assignable? ds (:category-id updated))))
      {:error (invalid-work-log-response "non-assignable-category")}

      :else
      {:attrs updated})))

(defn- confirmed-overlap? [ds attrs exclude-id]
  (and (= :confirmed (:state attrs))
       (some (fn [log]
               (and (not= (:id log) exclude-id)
                    (= :confirmed (:state log))
                    (ranges-overlap? (:start-minute attrs)
                                     (:end-minute attrs)
                                     (:start-minute log)
                                     (:end-minute log))))
             (db/work-logs-by-date ds (:date attrs)))))

(defn- persist-work-log-update! [ds id attrs]
  (if-let [current (db/get-work-log ds id)]
    (let [normalized (normalize-update ds current attrs)]
      (if-let [error (:error normalized)]
        {:error error}
        (let [updated (merge current (:attrs normalized))]
          (if (confirmed-overlap? ds updated id)
            {:error (invalid-work-log-response "overlaps-confirmed-work-log")}
            {:work-log (db/update-work-log! ds id (:attrs normalized))}))))
    {:error (json-response 404 {:error "not-found"})}))

(defn- parse-clock-minute [value]
  (when-let [[_ hour minute] (re-matches #"(\d{1,2}):(\d{2})" (or value ""))]
    (let [hour (parse-long hour)
          minute (parse-long minute)]
      (cond
        (and (= 24 hour) (zero? minute)) 1440
        (and (<= 0 hour 23) (<= 0 minute 59)) (+ (* hour 60) minute)
        :else nil))))

(defn- normalize-minute [value]
  (cond
    (integer? value) value
    (string? value) (or (parse-clock-minute value) (parse-id value))
    (nil? value) nil
    :else nil))

(defn- current-clock-minute []
  (let [time (LocalTime/now)]
    (+ (* (.getHour time) 60) (.getMinute time))))

(defn- valid-optional-minute? [minute]
  (or (nil? minute) (valid-minute? minute)))

(defn- normalize-attendance [date attrs]
  (let [clock-in (normalize-minute (:clock-in-minute attrs))
        clock-out (normalize-minute (:clock-out-minute attrs))]
    (cond
      (not (valid-date-string? date))
      {:error (invalid-attendance-response "invalid-date")}

      (not (and (valid-optional-minute? clock-in)
                (valid-optional-minute? clock-out)))
      {:error (invalid-attendance-response "invalid-minute")}

      (and clock-in clock-out (not (< clock-in clock-out)))
      {:error (invalid-attendance-response "invalid-time-range")}

      :else
      {:attrs {:date date
               :clock-in-minute clock-in
               :clock-out-minute clock-out}})))

(defn- upsert-attendance-response! [ds date attrs]
  (let [normalized (normalize-attendance date attrs)]
    (if-let [error (:error normalized)]
      error
      (json-response (db/upsert-attendance! ds (:attrs normalized))))))

(defn- upsert-attendance-form-response! [ds date form]
  (let [normalized (normalize-attendance
                    date
                    {:clock-in-minute (:clock-in-time form)
                     :clock-out-minute (:clock-out-time form)})]
    (if-let [error (:error normalized)]
      (form-warning-redirect (str "/days/" date) error)
      (do
        (db/upsert-attendance! ds (:attrs normalized))
        (redirect-response (str "/days/" date))))))

(defn- clock-now-form-response! [ds date field]
  (let [current (or (db/get-attendance ds date)
                    {:date date})
        attrs (assoc current field (current-clock-minute))
        normalized (normalize-attendance date attrs)]
    (if-let [error (:error normalized)]
      (form-warning-redirect (str "/days/" date) error)
      (do
        (db/upsert-attendance! ds (:attrs normalized))
        (redirect-response (str "/days/" date))))))

(defn- normalize-break-range [attrs]
  (let [start-minute (normalize-minute (:start-minute attrs))
        end-minute (normalize-minute (:end-minute attrs))]
    (cond
      (not (and (valid-minute? start-minute)
                (valid-minute? end-minute)
                (< start-minute end-minute)))
      {:error (invalid-break-response "invalid-time-range")}

      :else
      {:start-minute start-minute
       :end-minute end-minute})))

(defn- normalize-break [date attrs]
  (let [title (or (normalize-text (:title attrs)) "Break")
        range (normalize-break-range attrs)]
    (cond
      (not (valid-date-string? date))
      {:error (invalid-break-response "invalid-date")}

      (:error range)
      range

      :else
      {:attrs (merge {:date date
                      :title title}
                     range)})))

(defn- create-break-response! [ds date attrs]
  (let [normalized (normalize-break date attrs)]
    (if-let [error (:error normalized)]
      error
      (json-response (db/create-break! ds (:attrs normalized))))))

(defn- normalize-break-rule [attrs]
  (let [title (or (normalize-text (:title attrs)) "Break")
        range (normalize-break-range attrs)]
    (if-let [error (:error range)]
      {:error error}
      {:attrs (merge {:title title
                      :enabled? (normalize-bool (:enabled attrs) true)}
                     range)})))

(defn- create-break-rule-response! [ds attrs]
  (let [normalized (normalize-break-rule attrs)]
    (if-let [error (:error normalized)]
      error
      (json-response (db/create-break-rule! ds (:attrs normalized))))))

(defn- create-break-rule-form-response! [ds form]
  (let [normalized (normalize-break-rule {:title (:break-title form)
                                          :start-minute (:start-time form)
                                          :end-minute (:end-time form)
                                          :enabled true})]
    (if-let [error (:error normalized)]
      (form-warning-redirect (safe-redirect-path (:redirect-to form) "/") error)
      (do
        (db/create-break-rule! ds (:attrs normalized))
        (redirect-response (safe-redirect-path (:redirect-to form) "/"))))))

(defn- update-break-response! [ds id attrs]
  (if-let [break (db/get-break ds id)]
    (let [range (normalize-break-range attrs)]
      (if-let [error (:error range)]
        error
        (json-response (db/update-break! ds (:id break) range))))
    (json-response 404 {:error "not-found"})))

(defn- update-break-form-response! [ds id form]
  (if-let [break (db/get-break ds id)]
    (let [range (normalize-break-range {:start-minute (:start-time form)
                                        :end-minute (:end-time form)})]
      (if-let [error (:error range)]
        (form-warning-redirect (str "/days/" (:date break)) error)
        (do
          (db/update-break! ds (:id break) range)
          (redirect-response (str "/days/" (:date break))))))
    (form-warning-redirect "/" (json-response 404 {:error "not-found"}))))

(defn- delete-break-response! [ds id]
  (if-let [break (db/get-break ds id)]
    (json-response (db/update-break! ds (:id break) {:active? false}))
    (json-response 404 {:error "not-found"})))

(defn- delete-break-form-response! [ds id]
  (if-let [break (db/get-break ds id)]
    (do
      (db/update-break! ds (:id break) {:active? false})
      (redirect-response (str "/days/" (:date break))))
    (form-warning-redirect "/" (json-response 404 {:error "not-found"}))))

(defn- convert-break-response! [ds id attrs]
  (if-let [break (db/get-break ds id)]
    (let [work-log-result (create-work-log!
                           ds
                           (:date break)
                           {:title (or (normalize-text (:title attrs)) (:title break))
                            :start-minute (:start-minute break)
                            :end-minute (:end-minute break)
                            :category-id (:category-id attrs)})]
      (if-let [error (:error work-log-result)]
        error
        (do
          (db/update-break! ds id {:active? false})
          (json-response (:work-log work-log-result)))))
    (json-response 404 {:error "not-found"})))

(defn- convert-break-form-response! [ds id form]
  (if-let [break (db/get-break ds id)]
    (let [result (create-work-log!
                  ds
                  (:date break)
                  {:title (or (normalize-text (:title form)) (:title break))
                   :start-minute (:start-minute break)
                   :end-minute (:end-minute break)
                   :category-id (:category-id form)})]
      (if-let [error (:error result)]
        (form-warning-redirect (str "/days/" (:date break)) error)
        (do
          (db/update-break! ds id {:active? false})
          (redirect-response (str "/days/" (:date break))))))
    (form-warning-redirect "/" (json-response 404 {:error "not-found"}))))

(defn- work-log-ref-id [ref]
  (when (= :work-log (normalize-state (:kind ref)))
    (parse-id (:id ref))))

(defn- boundary-adjustment-response! [ds date attrs]
  (let [left-id (work-log-ref-id (:left attrs))
        right-id (work-log-ref-id (:right attrs))
        boundary-minute (normalize-minute (:boundary-minute attrs))
        left (when left-id (db/get-work-log ds left-id))
        right (when right-id (db/get-work-log ds right-id))]
    (cond
      (or (nil? left) (nil? right))
      (json-response 404 {:error "not-found"})

      (not (and (= date (:date left)) (= date (:date right))))
      (invalid-work-log-response "invalid-date")

      (not (= (:end-minute left) (:start-minute right)))
      (invalid-work-log-response "non-adjacent-boundary")

      (not (and (valid-minute? boundary-minute)
                (< (:start-minute left) boundary-minute)
                (< boundary-minute (:end-minute right))))
      (invalid-work-log-response "invalid-time-range")

      :else
      (json-response (db/adjust-work-log-boundary! ds left-id right-id boundary-minute)))))

(defn- form-update-response! [ds id attrs]
  (let [current (when id (db/get-work-log ds id))
        fallback (if current (str "/days/" (:date current)) "/")
        result (persist-work-log-update! ds id attrs)]
    (if-let [error (:error result)]
      (form-warning-redirect fallback error)
      (redirect-response (str "/days/" (get-in result [:work-log :date]))))))

(defn- patch-work-log-response! [ds id attrs]
  (if id
    (let [result (persist-work-log-update! ds id attrs)]
      (if-let [error (:error result)]
        error
        (json-response (:work-log result))))
    (json-response 404 {:error "not-found"})))

(defn- create-category! [ds attrs]
  (let [name (normalize-text (:name attrs))
        parent-id (normalize-category-id (:parent-id attrs))
        resolved-parent-id (when parent-id (db/resolve-category-id ds parent-id))]
    (cond
      (or (contains? attrs :id)
          (contains? attrs :legacy-key))
      {:error (invalid-category-response "client-category-id-not-allowed")}

      (nil? name)
      {:error (invalid-category-response "category-name-required")}

      (and parent-id (nil? resolved-parent-id))
      {:error (invalid-category-response "unknown-parent-category")}

      (and resolved-parent-id (:parent-id (db/get-category ds resolved-parent-id)))
      {:error (invalid-category-response "nested-category-not-allowed")}

      (and resolved-parent-id (db/category-has-assignments? ds resolved-parent-id))
      {:error (invalid-category-response "parent-category-has-assignments")}

      (db/find-category-by-name-and-parent ds name resolved-parent-id)
      {:error (invalid-category-response "duplicate-category-name")}

      :else
      {:category (db/upsert-category! ds {:name name
                                          :parent-id resolved-parent-id
                                          :kind (:kind attrs)})})))

(defn- create-category-response! [ds attrs]
  (let [result (create-category! ds attrs)]
    (if-let [error (:error result)]
      error
      (json-response (:category result)))))

(defn- create-category-form-response! [ds form]
  (let [result (create-category! ds {:name (:category-name form)
                                     :parent-id (:parent-id form)})
        fallback (safe-redirect-path (:redirect-to form) "/")]
    (if-let [error (:error result)]
      (form-warning-redirect fallback error)
      (redirect-response fallback))))

(defn- rename-category! [ds id attrs]
  (if-let [category (db/get-category ds id)]
    (let [name (normalize-text (:name attrs))
          duplicate (when name
                      (db/find-category-by-name-and-parent ds
                                                           name
                                                           (:parent-id category)))]
      (cond
        (nil? name)
        {:error (invalid-category-response "category-name-required")}

        (and duplicate (not= (:id duplicate) (:id category)))
        {:error (invalid-category-response "duplicate-category-name")}

        :else
        {:category (db/rename-category! ds (:id category) name)}))
    {:error (json-response 404 {:error "not-found"})}))

(defn- rename-category-response! [ds id attrs]
  (let [result (rename-category! ds id attrs)]
    (if-let [error (:error result)]
      error
      (json-response (:category result)))))

(defn- rename-category-form-response! [ds id form]
  (let [result (rename-category! ds id {:name (:category-name form)})
        fallback (safe-redirect-path (:redirect-to form) "/")]
    (if-let [error (:error result)]
      (form-warning-redirect fallback error)
      (redirect-response fallback))))

(defn- delete-category-response! [ds id]
  (if-let [result (db/delete-category! ds id)]
    (if (= :blocked (:mode result))
      (invalid-category-response (name (:reason result)))
      (json-response result))
    (json-response 404 {:error "not-found"})))

(defn- delete-category-form-response! [ds id form]
  (let [fallback (safe-redirect-path (:redirect-to form) "/")
        response (delete-category-response! ds id)]
    (if (= 200 (:status response))
      (redirect-response fallback)
      (form-warning-redirect fallback response))))

(defn- new-work-log-attrs [date ds attrs]
  (let [title (normalize-text (:title attrs))
        requested-category-id (normalize-category-id (:category-id attrs))
        category-id (when requested-category-id
                      (db/resolve-category-id ds requested-category-id))
        start-minute (:start-minute attrs)
        end-minute (:end-minute attrs)
        state (if category-id :confirmed :uncategorized)]
    (cond
      (not (valid-date-string? date))
      {:error (invalid-work-log-response "invalid-date")}

      (nil? title)
      {:error (invalid-work-log-response "title-required")}

      (not (and (valid-minute? start-minute)
                (valid-minute? end-minute)
                (< start-minute end-minute)))
      {:error (invalid-work-log-response "invalid-time-range")}

      (and requested-category-id
           (nil? category-id))
      {:error (invalid-work-log-response "unknown-category")}

      (and category-id
           (not (db/category-assignable? ds category-id)))
      {:error (invalid-work-log-response "non-assignable-category")}

      :else
      {:attrs {:date date
               :title title
               :start-minute start-minute
               :end-minute end-minute
               :state state
               :category-id category-id}})))

(defn- create-work-log! [ds date attrs]
  (let [normalized (new-work-log-attrs date ds attrs)]
    (if-let [error (:error normalized)]
      {:error error}
      (if (confirmed-overlap? ds (:attrs normalized) nil)
        {:error (invalid-work-log-response "overlaps-confirmed-work-log")}
        (let [id (db/insert-work-log! ds (:attrs normalized))]
          {:work-log (db/get-work-log ds id)})))))

(defn- create-work-log-response! [ds date attrs]
  (let [result (create-work-log! ds date attrs)]
    (if-let [error (:error result)]
      error
      (json-response (:work-log result)))))

(defn- create-work-log-form-response! [ds date form]
  (let [result (create-work-log! ds date {:title (:title form)
                                          :start-minute (parse-clock-minute (:start-time form))
                                          :end-minute (parse-clock-minute (:end-time form))
                                          :category-id (:category-id form)})]
    (if-let [error (:error result)]
      (form-warning-redirect (str "/days/" date) error)
      (redirect-response (str "/days/" date)))))

(defn- move-category-form-response! [ds id form]
  (if (db/get-category ds id)
    (do
      (db/move-category! ds id (:direction form))
      (redirect-response (safe-redirect-path (:redirect-to form) "/")))
    (form-warning-redirect (safe-redirect-path (:redirect-to form) "/")
                           (json-response 404 {:error "not-found"}))))

(defn- move-category-response! [ds id attrs]
  (if (db/get-category ds id)
    (json-response (db/move-category! ds id (:direction attrs)))
    (json-response 404 {:error "not-found"})))

(defn- normalize-bool [value default]
  (cond
    (nil? value) default
    (boolean? value) value
    (string? value) (not (#{"false" "0" "off"} (str/lower-case value)))
    :else (boolean value)))

(defn- normalize-positive-int [value default]
  (let [value (cond
                (integer? value) value
                (string? value) (parse-id value)
                :else nil)]
    (if (and value (pos-int? value)) value default)))

(defn- normalize-import-source [attrs]
  (let [kind (normalize-state (or (:kind attrs) :ical))
        name (normalize-text (:name attrs))
        uri (normalize-text (:uri attrs))
        interval (normalize-positive-int (:fetch-interval-minutes attrs) 60)
        enabled-value (cond
                        (contains? attrs :enabled?) (:enabled? attrs)
                        (contains? attrs :enabled) (:enabled attrs)
                        :else nil)
        enabled? (normalize-bool enabled-value true)]
    (cond
      (not= :ical kind)
      {:error (invalid-import-source-response "unsupported-kind")}

      (nil? name)
      {:error (invalid-import-source-response "name-required")}

      (nil? uri)
      {:error (invalid-import-source-response "uri-required")}

      :else
      {:attrs {:kind kind
               :name name
               :uri uri
               :enabled? enabled?
               :fetch-interval-minutes interval}})))

(defn- create-import-source-response! [ds attrs]
  (let [normalized (normalize-import-source attrs)]
    (if-let [error (:error normalized)]
      error
      (json-response (db/create-import-source! ds (:attrs normalized))))))

(defn- create-import-source-form-response! [ds form]
  (let [normalized (normalize-import-source form)]
    (if-let [error (:error normalized)]
      (form-warning-redirect "/settings" error)
      (do
        (db/create-import-source! ds (:attrs normalized))
        (redirect-response "/settings")))))

(defn- fetch-import-source-response! [ds id]
  (if-let [source (db/get-import-source ds id)]
    (let [fetch (importer/fetch-import-source! ds source (java.time.Instant/now))
          body (merge (:result fetch)
                      {:run (:run fetch)
                       :fetched (get-in fetch [:result :fetched])
                       :source-events-upserted (get-in fetch [:result :source-events-upserted])
                       :work-logs-created (get-in fetch [:result :work-logs-created])})]
      (if (:error fetch)
        (json-response 500 (assoc body :error "import-failed"))
        (json-response body)))
    (json-response 404 {:error "not-found"})))

(defn- fetch-import-source-form-response! [ds id]
  (let [response (fetch-import-source-response! ds id)]
    (if (= 200 (:status response))
      (redirect-response "/settings")
      (form-warning-redirect "/settings" response))))

(defn- update-settings-response! [ds attrs]
  (try
    (when (contains? attrs :break-mode)
      (db/set-break-mode! ds (:break-mode attrs)))
    (when (or (contains? attrs :holiday-policy-mode)
              (contains? attrs :holiday-weekdays))
      (db/set-holiday-policy!
       ds
       {:mode (:holiday-policy-mode attrs)
        :weekdays (:holiday-weekdays attrs)}))
    (when (contains? attrs :week-start-day)
      (db/set-week-start-day! ds (:week-start-day attrs)))
    (when (contains? attrs :fiscal-month-start-day)
      (db/set-fiscal-month-start-day! ds (:fiscal-month-start-day attrs)))
    (json-response (settings-json ds))
    (catch Exception error
      (json-response 400 {:error "invalid-settings"
                          :reason (.getMessage error)}))))

(defn- update-break-mode-form-response! [ds form]
  (try
    (db/set-break-mode! ds (:break-mode form))
    (redirect-response (safe-redirect-path (:redirect-to form) "/settings"))
    (catch Exception error
      (form-warning-redirect
       (safe-redirect-path (:redirect-to form) "/settings")
       (json-response 400 {:error "invalid-settings"
                           :reason (.getMessage error)})))))

(defn- holiday-weekdays-from-form [form]
  (keep (fn [weekday]
          (when (contains? form (keyword (str "weekday-" weekday)))
            weekday))
        (range 1 8)))

(defn- update-holiday-policy-form-response! [ds form]
  (try
    (db/set-holiday-policy!
     ds
     {:mode (:holiday-policy-mode form)
      :weekdays (holiday-weekdays-from-form form)})
    (redirect-response (safe-redirect-path (:redirect-to form) "/settings"))
    (catch Exception error
      (form-warning-redirect
       (safe-redirect-path (:redirect-to form) "/settings")
       (json-response 400 {:error "invalid-settings"
                           :reason (.getMessage error)})))))

(defn- update-calendar-settings-form-response! [ds form]
  (try
    (db/set-week-start-day! ds (:week-start-day form))
    (db/set-fiscal-month-start-day! ds (:fiscal-month-start-day form))
    (redirect-response (safe-redirect-path (:redirect-to form) "/settings"))
    (catch Exception error
      (form-warning-redirect
       (safe-redirect-path (:redirect-to form) "/settings")
       (json-response 400 {:error "invalid-settings"
                           :reason (.getMessage error)})))))

(defn- normalize-day-status-range [attrs]
  (let [start-date (:start-date attrs)
        end-date (:end-date attrs)
        dates (dates-between start-date end-date)
        status (some-> (:status attrs) normalize-state name)]
    (cond
      (empty? dates)
      {:error (json-response 400 {:error "invalid-day-status-range"
                                  :reason "invalid-date"})}

      (not (#{"workday" "holiday"} status))
      {:error (json-response 400 {:error "invalid-day-status-range"
                                  :reason "invalid-status"})}

      :else
      {:dates dates
       :status (keyword status)})))

(defn- update-day-status-range-response! [ds attrs]
  (let [normalized (normalize-day-status-range attrs)]
    (if-let [error (:error normalized)]
      error
      (do
        (doseq [date (:dates normalized)]
          (db/upsert-day-status-override! ds date (:status normalized)))
        (json-response {:updated (count (:dates normalized))
                        :status (name (:status normalized))
                        :start-date (first (:dates normalized))
                        :end-date (last (:dates normalized))})))))

(defn- update-day-status-range-form-response! [ds form]
  (let [response (update-day-status-range-response! ds form)]
    (if (= 200 (:status response))
      (redirect-response (safe-redirect-path (:redirect-to form) "/"))
      (form-warning-redirect (safe-redirect-path (:redirect-to form) "/")
                             response))))

(defn- source-event->candidate [source-event]
  {:source-id (:source-id source-event)
   :external-id (:external-id source-event)
   :title (:title source-event)
   :starts-at (:starts-at source-event)
   :ends-at (:ends-at source-event)
   :timezone (or (:timezone source-event) "UTC")
   :updated-at (:updated-at source-event)})

(defn- persist-source-event-work-log! [ds source-event state category-id]
  (let [mapping {(:title source-event) {:state state :category-id category-id}}
        attrs (worklog/candidate->worklog mapping (source-event->candidate source-event))
        existing (db/work-log-by-source ds (:source-id source-event) (:external-id source-event))]
    (if existing
      (db/update-work-log! ds (:id existing) attrs)
      (db/get-work-log ds (db/insert-work-log! ds attrs)))))

(defn- confirm-source-event-form-response! [ds id form]
  (if-let [source-event (db/get-source-event ds id)]
    (let [requested-category-id (normalize-category-id (:category-id form))
          category-id (when requested-category-id
                        (db/resolve-category-id ds requested-category-id))]
      (cond
        (nil? category-id)
        (form-warning-redirect (str "/days/" (:date source-event))
                               (invalid-work-log-response "category-required"))

        (not (db/category-assignable? ds category-id))
        (form-warning-redirect (str "/days/" (:date source-event))
                               (invalid-work-log-response "non-assignable-category"))

        :else
        (do
          (persist-source-event-work-log! ds source-event :confirmed category-id)
          (redirect-response (str "/days/" (:date source-event))))))
    (form-warning-redirect "/" (json-response 404 {:error "not-found"}))))

(defn- exclude-source-event-form-response! [ds id]
  (if-let [source-event (db/get-source-event ds id)]
    (do
      (persist-source-event-work-log! ds source-event :excluded nil)
      (redirect-response (str "/days/" (:date source-event))))
    (form-warning-redirect "/" (json-response 404 {:error "not-found"}))))

(defn app [{:keys [ds]}]
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [request]
                  (html-response (pages/days-page (calendar-state ds request))))}]
     ["/days"
      {:post (fn [request]
               (let [form (parse-form-body request)
                     date (:date form)]
                 (if (valid-date-string? date)
                   (redirect-response (str "/days/" date))
                   (form-warning-redirect
                    "/"
                    (json-response 400 {:error "invalid-date"})))))}]
     ["/days/:date" {:get (fn [request]
                            (let [date (get-in request [:path-params :date])
                                  params (parse-query-params request)]
                              (html-response
                               (pages/day-page (assoc (day-state ds date)
                                                      :flash-warning (:warning params))
                                               (db/list-categories ds)))))}]
     ["/settings"
      {:get (fn [request]
              (let [params (parse-query-params request)]
                (html-response (pages/settings-page {:break-rules (db/list-break-rules ds)
                                                     :import-sources (db/list-import-sources ds)
                                                     :settings (settings-json ds)
                                                     :flash-warning (:warning params)}))))}]
     ["/settings/break-mode"
      {:post (fn [request]
               (update-break-mode-form-response! ds (parse-form-body request)))}]
     ["/settings/holiday-policy"
      {:post (fn [request]
               (update-holiday-policy-form-response! ds (parse-form-body request)))}]
     ["/settings/calendar"
      {:post (fn [request]
               (update-calendar-settings-form-response! ds (parse-form-body request)))}]
     ["/day-status-ranges"
      {:post (fn [request]
               (update-day-status-range-form-response! ds (parse-form-body request)))}]
     ["/days/:date/worklogs"
      {:post (fn [request]
               (let [date (get-in request [:path-params :date])
                     form (parse-form-body request)]
                 (create-work-log-form-response! ds date form)))}]
     ["/days/:date/attendance"
      {:post (fn [request]
               (upsert-attendance-form-response!
                ds
                (get-in request [:path-params :date])
                (parse-form-body request)))}]
     ["/days/:date/attendance/clock-in-now"
      {:post (fn [request]
               (clock-now-form-response!
                ds
                (get-in request [:path-params :date])
                :clock-in-minute))}]
     ["/days/:date/attendance/clock-out-now"
      {:post (fn [request]
               (clock-now-form-response!
                ds
                (get-in request [:path-params :date])
                :clock-out-minute))}]
     ["/days/:date/breaks"
      {:post (fn [request]
               (let [date (get-in request [:path-params :date])
                     form (parse-form-body request)
                     result (create-break-response!
                             ds
                             date
                             {:title (:break-title form)
                              :start-minute (:start-time form)
                              :end-minute (:end-time form)})]
                 (if (= 200 (:status result))
                   (redirect-response (str "/days/" date))
                   (form-warning-redirect (str "/days/" date) result))))}]
     ["/health" {:get (fn [_] (json-response {:status "ok"}))}]
     ["/categories"
      {:post (fn [request]
               (create-category-form-response! ds (parse-form-body request)))}]
     ["/categories/:id/move"
      {:post (fn [request]
               (move-category-form-response! ds
                                             (parse-id (get-in request [:path-params :id]))
                                             (parse-form-body request)))}]
     ["/categories/:id/rename"
      {:post (fn [request]
               (rename-category-form-response! ds
                                               (parse-id (get-in request [:path-params :id]))
                                               (parse-form-body request)))}]
     ["/categories/:id/delete"
      {:post (fn [request]
               (delete-category-form-response! ds
                                               (parse-id (get-in request [:path-params :id]))
                                               (parse-form-body request)))}]
     ["/break-rules"
      {:post (fn [request]
               (create-break-rule-form-response! ds (parse-form-body request)))}]
     ["/breaks/:id/range"
      {:post (fn [request]
               (update-break-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))
                (parse-form-body request)))}]
     ["/breaks/:id/convert"
      {:post (fn [request]
               (convert-break-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))
                (parse-form-body request)))}]
     ["/breaks/:id/delete"
      {:post (fn [request]
               (delete-break-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))))}]
     ["/import-sources"
      {:get (fn [_]
              (redirect-response "/settings"))
       :post (fn [request]
               (create-import-source-form-response! ds (parse-form-body request)))}]
     ["/import-sources/:id/fetch"
      {:post (fn [request]
               (fetch-import-source-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))))}]
     ["/source-events/:id/confirm"
      {:post (fn [request]
               (confirm-source-event-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))
                (parse-form-body request)))}]
     ["/source-events/:id/exclude"
      {:post (fn [request]
               (exclude-source-event-form-response!
                ds
                (parse-id (get-in request [:path-params :id]))))}]
     ["/worklogs/:id/assign-category"
      {:post (fn [request]
               (let [id (parse-id (get-in request [:path-params :id]))
                     form (parse-form-body request)
                     category-id (:category-id form)]
                 (form-update-response!
                  ds
                  id
                  (if (str/blank? category-id)
                    {:state :uncategorized :category-id nil}
                    {:state :confirmed :category-id category-id}))))}]
     ["/worklogs/:id/exclude"
      {:post (fn [request]
               (let [id (parse-id (get-in request [:path-params :id]))]
                 (form-update-response! ds id {:state :excluded})))}]
     ["/worklogs/:id/range"
      {:post (fn [request]
               (let [id (parse-id (get-in request [:path-params :id]))
                     form (parse-form-body request)]
                 (form-update-response! ds id {:start-minute (parse-clock-minute (:start-time form))
                                               :end-minute (parse-clock-minute (:end-time form))})))}]
     ["/api/candidates/import"
      {:post (fn [request]
               (let [body (parse-json-body request)
                     result (import-candidates! ds (:events body))]
                 (json-response result)))}]
     ["/api/categories"
      {:get (fn [_] (json-response {:categories (db/list-categories ds)}))
       :post (fn [request]
               (create-category-response! ds (parse-json-body request)))}]
     ["/api/categories/:id/move"
      {:post (fn [request]
               (move-category-response! ds
                                        (parse-id (get-in request [:path-params :id]))
                                        (parse-json-body request)))}]
     ["/api/categories/:id"
      {:patch (fn [request]
                (rename-category-response! ds
                                           (parse-id (get-in request [:path-params :id]))
                                           (parse-json-body request)))
       :delete (fn [request]
                 (delete-category-response! ds
                                            (parse-id (get-in request [:path-params :id]))))}]
     ["/api/import-sources"
      {:get (fn [_] (json-response {:import-sources (db/list-import-sources ds)}))
       :post (fn [request]
               (create-import-source-response! ds (parse-json-body request)))}]
     ["/api/import-sources/:id/fetch"
      {:post (fn [request]
               (fetch-import-source-response!
                ds
                (parse-id (get-in request [:path-params :id]))))}]
     ["/api/import-sources/:id/runs"
      {:get (fn [request]
              (let [id (parse-id (get-in request [:path-params :id]))]
                (if (db/get-import-source ds id)
                  (json-response {:import-runs (db/list-import-runs ds id)})
                  (json-response 404 {:error "not-found"}))))}]
     ["/api/settings"
      {:get (fn [_] (json-response (settings-json ds)))
       :put (fn [request]
              (update-settings-response! ds (parse-json-body request)))}]
     ["/api/calendar"
      {:get (fn [request]
              (json-response (calendar-state ds request)))}]
     ["/api/day-status-ranges"
      {:post (fn [request]
               (update-day-status-range-response! ds (parse-json-body request)))}]
     ["/api/days/:date"
      {:get (fn [request]
              (json-response (select-keys (day-state ds (get-in request [:path-params :date]))
                                          [:date :work-logs :source-events
                                           :attendance :breaks])))}]
     ["/api/days/:date/attendance"
      {:put (fn [request]
              (upsert-attendance-response!
               ds
               (get-in request [:path-params :date])
               (parse-json-body request)))}]
     ["/api/days/:date/worklogs"
      {:post (fn [request]
               (create-work-log-response! ds
                                          (get-in request [:path-params :date])
                                          (parse-json-body request)))}]
     ["/api/days/:date/breaks"
      {:post (fn [request]
               (create-break-response!
                ds
                (get-in request [:path-params :date])
                (parse-json-body request)))}]
     ["/api/days/:date/boundary-adjustments"
      {:post (fn [request]
               (boundary-adjustment-response!
                ds
                (get-in request [:path-params :date])
                (parse-json-body request)))}]
     ["/api/days/:date/summary"
      {:get (fn [request]
              (json-response (:summary (day-state ds (get-in request [:path-params :date])))))}]
     ["/api/worklogs/:id"
      {:patch (fn [request]
                (let [id (parse-id (get-in request [:path-params :id]))
                      attrs (parse-json-body request)]
                  (patch-work-log-response! ds id attrs)))}]
     ["/api/break-rules"
      {:get (fn [_] (json-response {:break-rules (db/list-break-rules ds)}))
       :post (fn [request]
               (create-break-rule-response! ds (parse-json-body request)))}]
     ["/api/breaks/:id"
      {:patch (fn [request]
                (update-break-response!
                 ds
                 (parse-id (get-in request [:path-params :id]))
                 (parse-json-body request)))
       :delete (fn [request]
                 (delete-break-response!
                  ds
                  (parse-id (get-in request [:path-params :id]))))}]
     ["/api/breaks/:id/convert"
      {:post (fn [request]
               (convert-break-response!
                ds
                (parse-id (get-in request [:path-params :id]))
                (parse-json-body request)))}]])
   (ring/create-default-handler
    {:not-found (constantly (json-response 404 {:error "not-found"}))})))
