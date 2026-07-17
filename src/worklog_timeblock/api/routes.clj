(ns worklog-timeblock.api.routes
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.domain.summary :as summary]
            [worklog-timeblock.domain.worklog :as worklog]
            [worklog-timeblock.web.pages :as pages])
  (:import [java.net URLDecoder]))

(def default-summary-options
  {:rounding-minutes 15
   :small-gap-minutes 15
   :other-category-id "other"})

(def valid-states #{:confirmed :uncategorized :excluded})

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

(defn- import-candidates! [ds events]
  (let [mappings (db/title-mappings-map ds)]
    (doseq [event events]
      (db/upsert-work-log-by-source! ds (worklog/candidate->worklog mappings event)))
    (count events)))

(defn- day-state [ds date]
  (let [work-logs (db/work-logs-by-date ds date)]
    {:date date
     :work-logs work-logs
     :summary (summary/summarize-day default-summary-options work-logs)}))

(defn- parse-id [value]
  (try
    (parse-long value)
    (catch Exception _
      nil)))

(defn- normalize-state [state]
  (cond
    (keyword? state) state
    (string? state) (keyword state)
    (nil? state) nil
    :else (keyword (str state))))

(defn- normalize-category-id [category-id]
  (cond
    (nil? category-id) nil
    (keyword? category-id) (name category-id)
    (string? category-id) (let [category-id (str/trim category-id)]
                            (when-not (str/blank? category-id)
                              category-id))
    :else (str category-id)))

(defn- valid-minute? [minute]
  (and (integer? minute) (<= 0 minute 1440)))

(defn- normalize-update [current categories attrs]
  (let [state (normalize-state (or (:state attrs) (:state current)))
        category-id (normalize-category-id
                     (if (contains? attrs :category-id)
                       (:category-id attrs)
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

      (and (= :confirmed state)
           (nil? (:category-id updated)))
      {:error (invalid-work-log-response "category-required")}

      (and (:category-id updated)
           (not (contains? categories (:category-id updated))))
      {:error (invalid-work-log-response "unknown-category")}

      :else
      {:attrs updated})))

(defn- persist-work-log-update! [ds id attrs]
  (if-let [current (db/get-work-log ds id)]
    (let [categories (db/categories-by-id ds)
          normalized (normalize-update current categories attrs)]
      (if-let [error (:error normalized)]
        {:error error}
        {:work-log (db/update-work-log! ds id (:attrs normalized))}))
    {:error (json-response 404 {:error "not-found"})}))

(defn- parse-clock-minute [value]
  (when-let [[_ hour minute] (re-matches #"(\d{1,2}):(\d{2})" (or value ""))]
    (let [hour (parse-long hour)
          minute (parse-long minute)]
      (cond
        (and (= 24 hour) (zero? minute)) 1440
        (and (<= 0 hour 23) (<= 0 minute 59)) (+ (* hour 60) minute)
        :else nil))))

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

(defn app [{:keys [ds]}]
  (ring/ring-handler
   (ring/router
    [["/" {:get (fn [_]
                  (html-response (pages/home-page (db/list-dates ds))))}]
     ["/days/:date" {:get (fn [request]
                            (let [date (get-in request [:path-params :date])]
                              (html-response
                               (pages/day-page (day-state ds date)
                                               (db/list-categories ds)))))}]
     ["/health" {:get (fn [_] (json-response {:status "ok"}))}]
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
                     imported (import-candidates! ds (:events body))]
                 (json-response {:imported imported})))}]
     ["/api/days/:date"
      {:get (fn [request]
              (json-response (select-keys (day-state ds (get-in request [:path-params :date]))
                                          [:date :work-logs])))}]
     ["/api/days/:date/summary"
      {:get (fn [request]
              (json-response (:summary (day-state ds (get-in request [:path-params :date])))))}]
     ["/api/worklogs/:id"
      {:patch (fn [request]
                (let [id (parse-id (get-in request [:path-params :id]))
                      attrs (parse-json-body request)]
                  (patch-work-log-response! ds id attrs)))}]])
   (ring/create-default-handler
    {:not-found (constantly (json-response 404 {:error "not-found"}))})))
