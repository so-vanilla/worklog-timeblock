(ns worklog-timeblock.api-e2e.routes-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-api" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    (db/upsert-category! ds {:id "dev" :name "Development"})
    (db/upsert-category! ds {:id "meeting" :name "Meetings"})
    (db/upsert-category! ds {:id "other" :name "Other" :kind :other})
    (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id "dev"})
    (db/upsert-title-mapping! ds {:title "Lunch" :state :excluded})
    {:ds ds :handler (routes/app {:ds ds})}))

(defn request [handler method uri & [body]]
  (handler {:request-method method
            :uri uri
            :headers {"content-type" "application/json"}
            :body (when body (java.io.ByteArrayInputStream.
                              (.getBytes (json/generate-string body) "UTF-8")))}))

(defn parse-body [response]
  (json/parse-string (str (:body response)) keyword))

(defn work-log-by-title [handler title]
  (let [day (parse-body (request handler :get "/api/days/2026-07-06"))]
    (first (filter #(= title (:title %)) (:work-logs day)))))

(def events
  [{:source-id "local" :external-id "evt-build" :title "Build"
    :starts-at "2026-07-06T09:00:00+09:00" :ends-at "2026-07-06T09:50:00+09:00"
    :timezone "Asia/Tokyo"}
   {:source-id "local" :external-id "evt-lunch" :title "Lunch"
    :starts-at "2026-07-06T12:00:00+09:00" :ends-at "2026-07-06T13:00:00+09:00"
    :timezone "Asia/Tokyo"}
   {:source-id "local" :external-id "evt-unknown" :title "Unknown"
    :starts-at "2026-07-06T13:10:00+09:00" :ends-at "2026-07-06T13:40:00+09:00"
    :timezone "Asia/Tokyo"}])

(deftest api-e2e-test
  (let [{:keys [handler]} (temp-system)]
    (testing "health endpoint"
      (is (= {:status "ok"} (parse-body (request handler :get "/health")))))

    (testing "imports candidate events through core mapping"
      (let [response (request handler :post "/api/candidates/import" {:events events})]
        (is (= 200 (:status response)))
        (is (= 3 (:imported (parse-body response))))))

    (testing "day endpoint returns snapshots"
      (let [body (parse-body (request handler :get "/api/days/2026-07-06"))]
        (is (= 3 (count (:work-logs body))))
        (is (= #{"confirmed" "excluded" "uncategorized"}
               (set (map :state (:work-logs body)))))))

    (testing "summary endpoint returns manual-entry category totals"
      (let [body (parse-body (request handler :get "/api/days/2026-07-06/summary"))]
        (is (= 45 (get-in body [:category-minutes :dev])))
        (is (= 5 (get-in body [:other :rounding-residual-minutes])))))

    (testing "patch endpoint changes category"
      (let [unknown-id (:id (work-log-by-title handler "Unknown"))
            response (request handler :patch (str "/api/worklogs/" unknown-id)
                              {:state :confirmed :category-id "meeting"})]
        (is (= 200 (:status response)))
        (is (= {:state "confirmed" :category-id "meeting"}
               (select-keys (parse-body response) [:state :category-id])))
        (is (= 30 (get-in (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                          [:category-minutes :meeting])))))

    (testing "patch endpoint changes time range and recomputes summary"
      (let [build-id (:id (work-log-by-title handler "Build"))
            response (request handler :patch (str "/api/worklogs/" build-id)
                              {:start-minute 540 :end-minute 570})]
        (is (= 200 (:status response)))
        (is (= {:start-minute 540 :end-minute 570}
               (select-keys (parse-body response) [:start-minute :end-minute])))
        (is (= 30 (get-in (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                          [:category-minutes :dev])))))

    (testing "exclude endpoint removes a log from summary"
      (let [build-id (:id (work-log-by-title handler "Build"))]
        (is (= 200 (:status (request handler :patch (str "/api/worklogs/" build-id)
                                      {:state :excluded}))))
        (is (nil? (get-in (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                          [:category-minutes :dev])))))

    (testing "invalid range is rejected without mutating the log"
      (let [unknown-id (:id (work-log-by-title handler "Unknown"))
            response (request handler :patch (str "/api/worklogs/" unknown-id)
                              {:start-minute 900 :end-minute 900})]
        (is (= 400 (:status response)))
        (is (= {:error "invalid-work-log"}
               (select-keys (parse-body response) [:error])))
        (is (= {:start-minute 790 :end-minute 820}
               (select-keys (work-log-by-title handler "Unknown")
                            [:start-minute :end-minute])))))

    (testing "unknown category is rejected"
      (let [unknown-id (:id (work-log-by-title handler "Unknown"))
            response (request handler :patch (str "/api/worklogs/" unknown-id)
                              {:state :confirmed :category-id "missing-category"})]
        (is (= 400 (:status response)))
        (is (= "unknown-category" (:reason (parse-body response))))))

    (testing "unknown worklog returns 404"
      (let [response (request handler :patch "/api/worklogs/999999"
                              {:state :excluded})]
        (is (= 404 (:status response)))
        (is (= {:error "not-found"} (select-keys (parse-body response) [:error])))))

    (testing "unknown route returns 404"
      (is (= 404 (:status (request handler :get "/missing")))))))
