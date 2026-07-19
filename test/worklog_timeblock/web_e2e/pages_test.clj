(ns worklog-timeblock.web-e2e.pages-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-web" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    (let [dev (db/upsert-category! ds {:id "dev" :name "Development"})
          meeting (db/upsert-category! ds {:id "meeting" :name "Meetings"})
          other (db/upsert-category! ds {:id "other" :name "Other" :kind :other})
          build-id (db/insert-work-log! ds {:date "2026-07-06" :title "Build"
                                            :start-minute 540 :end-minute 590
                                            :state :confirmed :category-id (:id dev)})
          unknown-id (db/insert-work-log! ds {:date "2026-07-06" :title "Unknown"
                                              :start-minute 600 :end-minute 630
                                              :state :uncategorized})]
      {:ds ds
       :handler (routes/app {:ds ds})
       :category-ids {:dev (:id dev) :meeting (:id meeting) :other (:id other)}
       :ids {:build build-id :unknown unknown-id}})))

(defn empty-temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-web-empty" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    {:ds ds :handler (routes/app {:ds ds})}))

(defn request
  ([handler uri] (request handler :get uri nil))
  ([handler method uri body]
   (handler {:request-method method
             :uri uri
             :headers {"content-type" "application/x-www-form-urlencoded"}
             :body (when body (java.io.ByteArrayInputStream.
                               (.getBytes body "UTF-8")))})))

(defn response-body [response]
  (str (:body response)))

(deftest web-smoke-test
  (let [{:keys [handler ids category-ids]} (temp-system)]
    (testing "home page links to a day view"
      (let [html (response-body (request handler "/"))]
        (is (str/includes? html "worklog-timeblock"))
        (is (str/includes? html "/days/2026-07-06"))))

    (testing "day page renders a full-viewport worklog workspace"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html "class=\"day-workspace\""))
        (is (str/includes? html "class=\"timeline-pane\""))
        (is (str/includes? html "class=\"entry-pane\""))
        (is (str/includes? html "class=\"summary-pane\""))
        (is (str/includes? html "class=\"day-timeline\""))
        (is (str/includes? html "class=\"timeline-track\""))
        (is (str/includes? html "Build"))
        (is (str/includes? html "Development"))
        (is (str/includes? html "0.75h"))
        (is (str/includes? html "class=\"day-navigation\""))
        (is (str/includes? html "/days/2026-07-05"))
        (is (str/includes? html "/days/2026-07-07"))
        (is (str/includes? html "name=\"date\" value=\"2026-07-06\""))
        (is (str/includes? html "TODAY"))
        (is (not (str/includes? html "manual-entry-output")))
        (is (not (str/includes? html "Manual entry")))))

    (testing "day page exposes edit controls for category, range, and exclusion"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html (str "data-worklog-id=\"" (:build ids) "\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:unknown ids) "/assign-category\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/range\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/exclude\"")))
        (is (str/includes? html "name=\"category-id\""))
        (is (str/includes? html (str "value=\"" (:meeting category-ids) "\"")))
        (is (not (str/includes? html "value=\"meeting\"")))
        (is (str/includes? html "id=\"new-work-log-form\""))
        (is (str/includes? html "id=\"draft-summary-preview\""))
        (is (str/includes? html "id=\"candidate-menu\""))
        (is (str/includes? html "name=\"start-time\""))
        (is (str/includes? html "name=\"end-time\""))))

    (testing "day page makes warnings visible"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "Unknown"))))

    (testing "category form updates the persisted log and rendered summary"
      (let [response (request handler :post
                              (str "/worklogs/" (:unknown ids) "/assign-category")
                              (str "category-id=" (:meeting category-ids)))
            html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-06" (get-in response [:headers "location"])))
        (is (str/includes? html "Meetings"))
        (is (str/includes? html "0.50h"))))

    (testing "range form updates the rendered timeline"
      (let [response (request handler :post
                              (str "/worklogs/" (:build ids) "/range")
                              "start-time=09%3A15&end-time=09%3A45")
            html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (str/includes? html "09:15-09:45"))))

    (testing "exclude form removes the log from manual-entry totals"
      (let [response (request handler :post
                              (str "/worklogs/" (:build ids) "/exclude")
                              "")
            html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (str/includes? html "excluded"))
        (is (not (str/includes? html "Development\t0.50h")))))))

(deftest web-empty-input-test
  (let [{:keys [handler ds]} (empty-temp-system)]
    (testing "home page exposes a date picker for an empty database"
      (let [html (response-body (request handler "/"))]
        (is (str/includes? html "No work logs yet."))
        (is (str/includes? html "action=\"/days\""))
        (is (str/includes? html "name=\"date\""))
        (is (str/includes? html "type=\"date\""))))

    (testing "date picker redirects to the requested day"
      (let [response (request handler :post "/days" "date=2026-07-07")]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-07" (get-in response [:headers "location"])))))

    (testing "empty day page exposes category and worklog creation forms"
      (let [html (response-body (request handler "/days/2026-07-07"))]
        (is (str/includes? html "0 logs"))
        (is (str/includes? html "action=\"/categories\""))
        (is (str/includes? html "name=\"category-name\""))
        (is (str/includes? html "name=\"parent-id\""))
        (is (not (str/includes? html "placeholder=\"Category ID\"")))
        (is (str/includes? html "action=\"/days/2026-07-07/worklogs\""))
        (is (str/includes? html "name=\"title\""))
        (is (str/includes? html "name=\"start-time\""))
        (is (str/includes? html "name=\"end-time\""))
        (is (str/includes? html "Category totals"))
        (is (not (str/includes? html "No confirmed work.")))))

    (testing "category form creates a category and returns to the day"
      (let [response (request handler :post "/categories"
                              "category-name=Development&redirect-to=%2Fdays%2F2026-07-07")
            html (response-body (request handler "/days/2026-07-07"))
            dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-07" (get-in response [:headers "location"])))
        (is (pos-int? dev-id))
        (is (str/includes? html "Development"))
        (is (str/includes? html (str "value=\"" dev-id "\"")))
        (is (not (str/includes? html "value=\"dev\"")))))

    (testing "worklog form creates a categorized log and updates category totals"
      (let [dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))
            response (request handler :post "/days/2026-07-07/worklogs"
                              (str "title=Build&start-time=09%3A00&end-time=10%3A00&category-id="
                                   dev-id))
            html (response-body (request handler "/days/2026-07-07"))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-07" (get-in response [:headers "location"])))
        (is (str/includes? html "1 logs"))
        (is (str/includes? html "09:00-10:00"))
        (is (str/includes? html "Build"))
        (is (str/includes? html "Development"))
        (is (str/includes? html (str "data-summary-category-id=\"" dev-id "\"")))
        (is (str/includes? html "1.00h"))))

    (testing "worklog form can create an uncategorized log"
      (let [response (request handler :post "/days/2026-07-07/worklogs"
                              "title=Triage&start-time=10%3A15&end-time=10%3A45&category-id=")
            html (response-body (request handler "/days/2026-07-07"))]
        (is (= 303 (:status response)))
        (is (str/includes? html "2 logs"))
        (is (str/includes? html "Triage"))
        (is (str/includes? html "Uncategorized: Triage"))))))

(deftest web-category-hierarchy-test
  (let [{:keys [handler ds]} (empty-temp-system)]
    (request handler :post "/categories"
             "category-name=Engineering&redirect-to=%2Fdays%2F2026-07-08")
    (let [engineering-id (:id (db/find-category-by-name-and-parent ds "Engineering" nil))]
      (request handler :post "/categories"
               (str "category-name=Frontend&parent-id=" engineering-id
                    "&redirect-to=%2Fdays%2F2026-07-08"))
      (request handler :post "/categories"
               (str "category-name=Backend&parent-id=" engineering-id
                    "&redirect-to=%2Fdays%2F2026-07-08"))
      (let [frontend-id (:id (db/find-category-by-name-and-parent ds "Frontend" engineering-id))
            backend-id (:id (db/find-category-by-name-and-parent ds "Backend" engineering-id))
            html (response-body (request handler "/days/2026-07-08"))]
        (testing "child categories are grouped without repeating the parent name"
          (is (str/includes? html "class=\"category-row category-root\""))
          (is (str/includes? html "class=\"category-row category-child\""))
          (is (str/includes? html "style=\"--group-color:"))
          (is (str/includes? html "<optgroup label=\"Engineering\">"))
          (is (str/includes? html (str "<option value=\"" frontend-id "\">Frontend</option>")))
          (is (str/includes? html (str "<option value=\"" backend-id "\">Backend</option>")))
          (is (not (str/includes? html "Engineering / Frontend")))
          (is (not (str/includes? html "Engineering / Backend")))
          (is (str/includes? html (str "action=\"/categories/" backend-id "/move\""))))

        (testing "parent categories cannot be assigned through form submission"
          (let [response (request handler :post "/days/2026-07-08/worklogs"
                                  (str "title=Parent&start-time=09%3A00&end-time=10%3A00&category-id="
                                       engineering-id))]
            (is (= 400 (:status response)))
            (is (str/includes? (response-body response) "non-assignable-category"))))

        (testing "child categories remain assignable"
          (let [response (request handler :post "/days/2026-07-08/worklogs"
                                  (str "title=Frontend&start-time=10%3A00&end-time=11%3A00&category-id="
                                       frontend-id))
                backend-response (request handler :post "/days/2026-07-08/worklogs"
                                          (str "title=Backend&start-time=11%3A00&end-time=11%3A30&category-id="
                                               backend-id))
                html (response-body (request handler "/days/2026-07-08"))]
            (is (= 303 (:status response)))
            (is (= 303 (:status backend-response)))
            (is (str/includes? html "Frontend"))
            (is (str/includes? html (str "data-summary-category-id=\"" engineering-id "\"")))
            (is (str/includes? html (str "data-summary-category-id=\"" frontend-id "\"")))
            (is (str/includes? html (str "data-summary-category-id=\"" backend-id "\"")))
            (let [parent-pos (str/index-of html (str "data-summary-category-id=\"" engineering-id "\""))
                  frontend-pos (str/index-of html (str "data-summary-category-id=\"" frontend-id "\""))
                  backend-pos (str/index-of html (str "data-summary-category-id=\"" backend-id "\""))]
              (is (< parent-pos frontend-pos))
              (is (< frontend-pos backend-pos)))
            (is (str/includes? html "1.50h"))
            (is (str/includes? html "1.00h"))
            (is (str/includes? html "0.50h"))))

        (testing "move buttons reorder only sibling categories"
          (let [response (request handler :post
                                  (str "/categories/" backend-id "/move")
                                  "direction=up&redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler "/days/2026-07-08"))
                backend-pos (str/index-of html (str "action=\"/categories/" backend-id "/move\""))
                frontend-pos (str/index-of html (str "action=\"/categories/" frontend-id "/move\""))]
            (is (= 303 (:status response)))
            (is (< backend-pos frontend-pos))))))))

(deftest web-attendance-and-breaks-test
  (let [{:keys [handler ds]} (empty-temp-system)]
    (request handler :post "/categories"
             "category-name=Development&redirect-to=%2Fdays%2F2026-07-11")
    (let [dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))]
      (testing "day page exposes attendance and break controls"
        (let [html (response-body (request handler "/days/2026-07-11"))]
          (is (str/includes? html "Attendance"))
          (is (str/includes? html "Clock in now"))
          (is (str/includes? html "Clock out now"))
          (is (str/includes? html "name=\"clock-in-time\""))
          (is (str/includes? html "name=\"clock-out-time\""))
          (is (str/includes? html "Daily break"))
          (is (str/includes? html "name=\"break-title\""))))

      (testing "manual attendance form updates the day summary"
        (let [response (request handler :post "/days/2026-07-11/attendance"
                                "clock-in-time=09%3A00&clock-out-time=18%3A00")
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (= "/days/2026-07-11" (get-in response [:headers "location"])))
          (is (str/includes? html "09:00-18:00"))
          (is (str/includes? html "Unallocated"))
          (is (str/includes? html "9.00h"))))

      (testing "daily break rule materializes a break on the day page"
        (let [response (request handler :post "/break-rules"
                                "break-title=Lunch&start-time=12%3A00&end-time=13%3A00&redirect-to=%2Fdays%2F2026-07-11")
              html (response-body (request handler "/days/2026-07-11"))
              break-id (:id (first (db/breaks-by-date ds "2026-07-11")))]
          (is (= 303 (:status response)))
          (is (= "/days/2026-07-11" (get-in response [:headers "location"])))
          (is (pos-int? break-id))
          (is (str/includes? html "class=\"timeline-block break-block\""))
          (is (str/includes? html "Lunch"))
          (is (str/includes? html "Breaks"))
          (is (str/includes? html "1.00h"))
          (is (str/includes? html (str "action=\"/breaks/" break-id "/range\"")))
          (is (str/includes? html (str "action=\"/breaks/" break-id "/convert\"")))))

      (testing "break range form updates the rendered break"
        (let [break-id (:id (first (db/breaks-by-date ds "2026-07-11")))
              response (request handler :post
                                (str "/breaks/" break-id "/range")
                                "start-time=12%3A15&end-time=13%3A15")
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (str/includes? html "12:15-13:15"))))

      (testing "break convert form creates categorized effort and removes the break"
        (let [break-id (:id (first (db/breaks-by-date ds "2026-07-11")))
              response (request handler :post
                                (str "/breaks/" break-id "/convert")
                                (str "title=Lunch%20support&category-id=" dev-id))
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (str/includes? html "Lunch support"))
          (is (str/includes? html "confirmed"))
          (is (not (str/includes? html "class=\"timeline-block break-block\""))))))))

(deftest web-import-source-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        fixture-path (.getPath (io/file (io/resource "fixtures/ical/basic.ics")))]
    (testing "home page links to import source settings"
      (let [html (response-body (request handler "/"))]
        (is (str/includes? html "/import-sources"))
        (is (str/includes? html "Import sources"))))

    (testing "import source page exposes add form"
      (let [html (response-body (request handler "/import-sources"))]
        (is (str/includes? html "Add iCal source"))
        (is (str/includes? html "name=\"name\""))
        (is (str/includes? html "name=\"uri\""))
        (is (str/includes? html "name=\"fetch-interval-minutes\""))))

    (testing "source form creates a source and returns to settings"
      (let [response (request handler :post "/import-sources"
                              (str "kind=ical&name=Fixture%20calendar&uri="
                                   fixture-path
                                   "&fetch-interval-minutes=15"))
            html (response-body (request handler "/import-sources"))]
        (is (= 303 (:status response)))
        (is (= "/import-sources" (get-in response [:headers "location"])))
        (is (str/includes? html "Fixture calendar"))
        (is (str/includes? html "/fetch"))))

    (testing "manual fetch imports the source and day page shows the snapshot"
      (let [settings-html (response-body (request handler "/import-sources"))
            source-id (second (re-find #"/import-sources/(\d+)/fetch" settings-html))
            response (request handler :post (str "/import-sources/" source-id "/fetch") "")
            day-html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (= "/import-sources" (get-in response [:headers "location"])))
        (is (str/includes? day-html "Build"))
        (is (str/includes? day-html "09:00-10:00"))
        (is (str/includes? day-html "imported-block"))
        (is (str/includes? day-html "Imported candidates"))))

    (testing "source event confirm and exclude forms update the snapshot"
      (let [source-event-id (:id (first (db/source-events-by-date ds "2026-07-06")))
            confirm-response (request handler :post
                                      (str "/source-events/" source-event-id "/confirm")
                                      (str "category-id=" (:id dev)))
            confirmed-html (response-body (request handler "/days/2026-07-06"))
            exclude-response (request handler :post
                                      (str "/source-events/" source-event-id "/exclude")
                                      "")
            excluded-html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status confirm-response)))
        (is (= "/days/2026-07-06" (get-in confirm-response [:headers "location"])))
        (is (str/includes? confirmed-html "Development"))
        (is (str/includes? confirmed-html "1.00h"))
        (is (= 303 (:status exclude-response)))
        (is (= "/days/2026-07-06" (get-in exclude-response [:headers "location"])))
        (is (str/includes? excluded-html "excluded"))
        (is (not (str/includes? excluded-html "Development\t1.00h")))))))
