(ns worklog-timeblock.web-e2e.pages-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-web" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    (db/upsert-category! ds {:id "dev" :name "Development"})
    (db/upsert-category! ds {:id "meeting" :name "Meetings"})
    (db/upsert-category! ds {:id "other" :name "Other" :kind :other})
    (let [build-id (db/insert-work-log! ds {:date "2026-07-06" :title "Build"
                                            :start-minute 540 :end-minute 590
                                            :state :confirmed :category-id "dev"})
          unknown-id (db/insert-work-log! ds {:date "2026-07-06" :title "Unknown"
                                              :start-minute 600 :end-minute 630
                                              :state :uncategorized})]
      {:handler (routes/app {:ds ds})
       :ids {:build build-id :unknown unknown-id}})))

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
  (let [{:keys [handler ids]} (temp-system)]
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
        (is (str/includes? html "value=\"meeting\""))
        (is (str/includes? html "name=\"start-time\""))
        (is (str/includes? html "name=\"end-time\""))))

    (testing "day page makes warnings visible"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "Unknown"))))

    (testing "category form updates the persisted log and rendered summary"
      (let [response (request handler :post
                              (str "/worklogs/" (:unknown ids) "/assign-category")
                              "category-id=meeting")
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
