(ns worklog-timeblock.refactor-characterization-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]
            [worklog-timeblock.web.layout :as layout])
  (:import [java.time LocalDate]))

(def calendar-dates #'routes/calendar-dates)
(def classify-calendar-status #'routes/classify-calendar-status)
(def normalize-view #'routes/normalize-view)
(def active-edit? #'routes/active-edit?)
(def dates-between #'routes/dates-between)
(def safe-redirect-path #'routes/safe-redirect-path)
(def append-query-param #'routes/append-query-param)
(def flash-warning layout/flash-warning)
(def escape-html layout/escape-html)

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-refactor" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    {:ds ds
     :handler (routes/app {:ds ds})}))

(defn request
  ([handler uri] (request handler :get uri nil))
  ([handler method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (handler {:request-method method
               :uri path
               :query-string query
               :headers {"content-type" "application/x-www-form-urlencoded"}
               :body (when body
                       (java.io.ByteArrayInputStream.
                        (.getBytes body "UTF-8")))}))))

(defn json-request
  [handler method uri body]
  (let [[path query] (str/split uri #"\?" 2)]
    (handler {:request-method method
              :uri path
              :query-string query
              :headers {"content-type" "application/json"}
              :body (when body
                      (java.io.ByteArrayInputStream.
                       (.getBytes (json/generate-string body) "UTF-8")))})))

(defn response-body [response]
  (str (:body response)))

(defn parse-body [response]
  (json/parse-string (response-body response) keyword))

(deftest calendar-date-logic-characterization-test
  (let [reference (LocalDate/parse "2026-07-15")]
    (testing "week ranges honor configured week start"
      (is (= ["2026-07-13" "2026-07-14" "2026-07-15" "2026-07-16"
              "2026-07-17" "2026-07-18" "2026-07-19"]
             (calendar-dates "week" reference 1 1)))
      (is (= ["2026-07-12" "2026-07-13" "2026-07-14" "2026-07-15"
              "2026-07-16" "2026-07-17" "2026-07-18"]
             (calendar-dates "week" reference 7 1))))

    (testing "month ranges honor fiscal month start"
      (let [period (calendar-dates "month" reference 1 21)]
        (is (= "2026-06-21" (first period)))
        (is (= "2026-07-20" (last period)))
        (is (= 30 (count period))))
      (let [period (calendar-dates "month" (LocalDate/parse "2026-07-21") 1 21)]
        (is (= "2026-07-21" (first period)))
        (is (= "2026-08-20" (last period)))
        (is (= 31 (count period)))))

    (testing "date helpers normalize intentionally loose inputs"
      (is (= ["2026-07-15" "2026-07-16" "2026-07-17"]
             (dates-between "2026-07-17" "2026-07-15")))
      (is (= "week" (normalize-view "week")))
      (is (= "month" (normalize-view nil)))
      (is (true? (boolean (active-edit? "active"))))
      (is (false? (boolean (active-edit? "inactive")))))))

(deftest day-status-classification-characterization-test
  (let [today (LocalDate/parse "2026-07-20")]
    (testing "holiday base status wins"
      (is (= :holiday
             (classify-calendar-status "2026-07-19" today :holiday nil []
                                       {:attendance {:unallocated-minutes 120}}))))

    (testing "unallocated attendance time marks a workday missing"
      (is (= :missing
             (classify-calendar-status "2026-07-19" today :workday
                                       {:clock-in-minute 540 :clock-out-minute 600}
                                       [{:state :confirmed}]
                                       {:attendance {:unallocated-minutes 60}}))))

    (testing "past days without meaningful work are missing"
      (is (= :missing
             (classify-calendar-status "2026-07-19" today :workday nil []
                                       {:attendance {:unallocated-minutes 0}})))
      (is (= :missing
             (classify-calendar-status "2026-07-19" today :workday nil
                                       [{:state :excluded}]
                                       {:attendance {:unallocated-minutes 0}}))))

    (testing "future workdays stay neutral while recorded days are done"
      (is (= :workday
             (classify-calendar-status "2026-07-21" today :workday nil []
                                       {:attendance {:unallocated-minutes 0}})))
      (is (= :done
             (classify-calendar-status "2026-07-19" today :workday
                                       {:clock-in-minute 540 :clock-out-minute 600}
                                       [{:state :uncategorized}]
                                       {:attendance {:unallocated-minutes 0}}))))))

(deftest warning-and-response-helper-characterization-test
  (testing "warning rendering is escaped and user visible"
    (let [known (flash-warning "non-assignable-category")
          unknown (flash-warning "<unknown>")]
      (is (str/includes? known "role=\"alert\""))
      (is (str/includes? known "Non-assignable category"))
      (is (str/includes? unknown "&lt;unknown&gt;"))
      (is (nil? (flash-warning nil)))
      (is (= "&lt;tag attr=&quot;1&quot;&gt;&amp;&#39;"
             (escape-html "<tag attr=\"1\">&'")))))

  (testing "redirect helpers keep local redirects local"
    (is (= "/days/2026-07-06"
           (safe-redirect-path "/days/2026-07-06" "/")))
    (is (= "/" (safe-redirect-path "https://example.com" "/")))
    (is (= "/" (safe-redirect-path "//example.com" "/")))
    (is (= "/" (safe-redirect-path "" "/")))
    (is (= "/settings?warning=invalid+settings"
           (append-query-param "/settings" :warning "invalid settings")))
    (is (= "/days/2026-07-06?selected=42#logs"
           (append-query-param "/days/2026-07-06#logs" :selected 42)))))

(deftest public-page-characterization-test
  (let [{:keys [handler ds]} (temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        engineering (db/upsert-category! ds {:name "Engineering"})
        backend (db/upsert-category! ds {:name "Backend" :parent-id (:id engineering)})]
    (request handler :post "/days/2026-07-06/attendance"
             "clock-in-time=09%3A00&clock-out-time=18%3A00")
    (request handler :post "/days/2026-07-06/worklogs"
             (str "title=Build&start-time=09%3A00&end-time=10%3A00&category-id="
                  (:id dev)))
    (request handler :post "/days/2026-07-06/worklogs"
             (str "title=Backend&start-time=10%3A00&end-time=11%3A00&category-id="
                  (:id backend)))
    (request handler :post "/days/2026-07-06/worklogs"
             "title=Loose&start-time=11%3A00&end-time=11%3A30&category-id=")

    (testing "day workspace exposes the three-pane editing surface"
      (let [html (response-body (request handler "/days/2026-07-06?warning=unknown-category"))]
        (is (str/includes? html "<main class=\"day-workspace\">"))
        (is (str/includes? html "<section class=\"timeline-pane\">"))
        (is (str/includes? html "<section class=\"entry-pane\""))
        (is (str/includes? html "<aside class=\"summary-pane\">"))
        (is (str/includes? html "Unknown category"))
        (is (str/includes? html "data-worklog-id="))
        (is (str/includes? html "data-summary-category-id=\"uncategorized\""))
        (is (str/includes? html "data-export-download"))
        (is (str/includes? html "id=\"title-suggestion-list\""))
        (is (str/includes? html "class=\"timeline-selection\""))
        (is (str/includes? html "class=\"timeline-warning-bubble\""))))

    (testing "settings page owns global settings and import sources"
      (let [html (response-body (request handler "/settings?warning=invalid-settings"))]
        (is (str/includes? html "<main class=\"home settings-page\">"))
        (is (str/includes? html "Invalid settings"))
        (is (str/includes? html "form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/break-mode\""))
        (is (str/includes? html "form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/holiday-policy\""))
        (is (str/includes? html "form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/calendar\""))
        (is (str/includes? html "form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/export\""))
        (is (str/includes? html "<section class=\"input-panel import-sources-panel\">"))
        (is (not (str/includes? html "href=\"/import-sources\"")))))

    (testing "calendar API exposes the same period and category breakdown inputs"
      (let [calendar (parse-body (json-request handler :get "/api/calendar?view=week&date=2026-07-06" nil))
            day (first (filter #(= "2026-07-06" (:date %)) (:days calendar)))]
        (is (= "week" (:view calendar)))
        (is (= "2026-07-06" (:reference-date calendar)))
        (is (= "2026-07-06" (:period-start calendar)))
        (is (= "2026-07-12" (:period-end calendar)))
        (is (= "missing" (:status day)))
        (is (= 390 (:unallocated-minutes day)))
        (is (= 1.0 (get-in day [:category-hours (keyword (str (:id dev)))])))
        (is (= 1.0 (get-in day [:category-hours (keyword (str (:id backend)))])))
        (is (= 0.5 (get-in day [:category-hours :uncategorized])))))))
