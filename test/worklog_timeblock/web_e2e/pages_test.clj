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
        (is (str/includes? html "class=\"summary-pane\""))
        (is (str/includes? html "Build"))
        (is (str/includes? html "Development"))
        (is (str/includes? html "0.75h"))
        (is (str/includes? html "manual-entry-output"))))

    (testing "day page exposes edit controls for category, range, and exclusion"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html (str "data-worklog-id=\"" (:build ids) "\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:unknown ids) "/assign-category\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/range\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/exclude\"")))
        (is (str/includes? html "name=\"category-id\""))
        (is (str/includes? html (str "value=\"" (:meeting category-ids) "\"")))
        (is (not (str/includes? html "value=\"meeting\"")))
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
        (is (str/includes? html "No confirmed work."))))

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

    (testing "worklog form creates a categorized log and updates manual-entry totals"
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
        (is (str/includes? html "Development\t1.00h"))))

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
        (testing "child categories are labelled with their parent"
          (is (str/includes? html "Engineering / Frontend"))
          (is (str/includes? html "Engineering / Backend"))
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
                html (response-body (request handler "/days/2026-07-08"))]
            (is (= 303 (:status response)))
            (is (str/includes? html "Engineering / Frontend"))
            (is (str/includes? html "Frontend\t1.00h"))))

        (testing "move buttons reorder only sibling categories"
          (let [response (request handler :post
                                  (str "/categories/" backend-id "/move")
                                  "direction=up&redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler "/days/2026-07-08"))
                backend-pos (str/index-of html "Engineering / Backend")
                frontend-pos (str/index-of html "Engineering / Frontend")]
            (is (= 303 (:status response)))
            (is (< backend-pos frontend-pos))))))))

(deftest web-import-source-test
  (let [{:keys [handler]} (empty-temp-system)
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
        (is (str/includes? day-html "09:00-10:00"))))))
