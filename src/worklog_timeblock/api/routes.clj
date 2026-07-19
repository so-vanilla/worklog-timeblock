(ns worklog-timeblock.api.routes
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.domain.summary :as summary]
            [worklog-timeblock.domain.worklog :as worklog]
            [worklog-timeblock.importer.core :as importer]
            [worklog-timeblock.web.pages :as pages])
  (:import [java.net URLDecoder]
           [java.time LocalTime]))

(def default-summary-options
  {:rounding-minutes 15
   :small-gap-minutes 15
   :other-category-id "other"})

(def valid-states #{:confirmed :uncategorized :excluded})

(declare create-work-log! normalize-bool)

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
  (assoc default-summary-options
         :other-category-id (or (db/other-category-id ds) "other")
         :assignable-category-ids (db/summarizable-category-ids ds)))

(defn- day-state [ds date]
  (db/materialize-breaks-for-date! ds date)
  (let [work-logs (db/work-logs-by-date ds date)
        source-events (db/source-events-by-date ds date)
        attendance (db/get-attendance ds date)
        breaks (db/breaks-by-date ds date)
        summary (summary/summarize-day (assoc (summary-options ds)
                                              :attendance attendance
                                              :breaks breaks)
                                       work-logs)]
    {:date date
     :work-logs work-logs
     :source-events source-events
     :attendance attendance
     :breaks breaks
     :summary (update summary :warnings into
                      (summary/source-diff-warnings work-logs source-events))}))

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
      error
      (do
        (db/upsert-attendance! ds (:attrs normalized))
        (redirect-response (str "/days/" date))))))

(defn- clock-now-form-response! [ds date field]
  (let [current (or (db/get-attendance ds date)
                    {:date date})
        attrs (assoc current field (current-clock-minute))
        normalized (normalize-attendance date attrs)]
    (if-let [error (:error normalized)]
      error
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
      error
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
        error
        (do
          (db/update-break! ds (:id break) range)
          (redirect-response (str "/days/" (:date break))))))
    (json-response 404 {:error "not-found"})))

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
        error
        (do
          (db/update-break! ds id {:active? false})
          (redirect-response (str "/days/" (:date break))))))
    (json-response 404 {:error "not-found"})))

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
  (let [result (persist-work-log-update! ds id attrs)]
    (if-let [error (:error result)]
      error
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
                                     :parent-id (:parent-id form)})]
    (if-let [error (:error result)]
      error
      (redirect-response (safe-redirect-path (:redirect-to form) "/")))))

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
  (let [result (rename-category! ds id {:name (:category-name form)})]
    (if-let [error (:error result)]
      error
      (redirect-response (safe-redirect-path (:redirect-to form) "/")))))

(defn- delete-category-response! [ds id]
  (if-let [result (db/delete-category! ds id)]
    (if (= :blocked (:mode result))
      (invalid-category-response (name (:reason result)))
      (json-response result))
    (json-response 404 {:error "not-found"})))

(defn- delete-category-form-response! [ds id form]
  (let [response (delete-category-response! ds id)]
    (if (= 200 (:status response))
      (redirect-response (safe-redirect-path (:redirect-to form) "/"))
      response)))

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
      error
      (redirect-response (str "/days/" date)))))

(defn- move-category-form-response! [ds id form]
  (if (db/get-category ds id)
    (do
      (db/move-category! ds id (:direction form))
      (redirect-response (safe-redirect-path (:redirect-to form) "/")))
    (json-response 404 {:error "not-found"})))

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
      error
      (do
        (db/create-import-source! ds (:attrs normalized))
        (redirect-response "/import-sources")))))

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
      (redirect-response "/import-sources")
      response)))

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
        (invalid-work-log-response "category-required")

        (not (db/category-assignable? ds category-id))
        (invalid-work-log-response "non-assignable-category")

        :else
        (do
          (persist-source-event-work-log! ds source-event :confirmed category-id)
          (redirect-response (str "/days/" (:date source-event))))))
    (json-response 404 {:error "not-found"})))

(defn- exclude-source-event-form-response! [ds id]
  (if-let [source-event (db/get-source-event ds id)]
    (do
      (persist-source-event-work-log! ds source-event :excluded nil)
      (redirect-response (str "/days/" (:date source-event))))
    (json-response 404 {:error "not-found"})))

(defn app [{:keys [ds]}]
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [_]
                  (html-response (pages/home-page (db/list-dates ds))))}]
     ["/days"
      {:post (fn [request]
               (let [form (parse-form-body request)
                     date (:date form)]
                 (if (valid-date-string? date)
                   (redirect-response (str "/days/" date))
                   (json-response 400 {:error "invalid-date"}))))}]
     ["/days/:date" {:get (fn [request]
                            (let [date (get-in request [:path-params :date])]
                              (html-response
                               (pages/day-page (day-state ds date)
                                               (db/list-categories ds)))))}]
     ["/settings"
      {:get (fn [_]
              (html-response (pages/settings-page (db/list-break-rules ds))))}]
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
                   result)))}]
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
     ["/import-sources"
      {:get (fn [_]
              (html-response (pages/import-sources-page (db/list-import-sources ds))))
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
                     form (parse-form-body request)]
                 (form-update-response! ds id {:state :confirmed
                                               :category-id (:category-id form)})))}]
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
                 (parse-json-body request)))}]
     ["/api/breaks/:id/convert"
      {:post (fn [request]
               (convert-break-response!
                ds
                (parse-id (get-in request [:path-params :id]))
                (parse-json-body request)))}]])
   (ring/create-default-handler
    {:not-found (constantly (json-response 404 {:error "not-found"}))})))
