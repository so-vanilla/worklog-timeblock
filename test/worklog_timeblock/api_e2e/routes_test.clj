(ns worklog-timeblock.api-e2e.routes-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-api" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    (let [dev (db/upsert-category! ds {:id "dev" :name "Development"})
          meeting (db/upsert-category! ds {:id "meeting" :name "Meetings"})
          other (db/upsert-category! ds {:id "other" :name "Other" :kind :other})]
      (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id "dev"})
      (db/upsert-title-mapping! ds {:title "Lunch" :state :excluded})
      {:ds ds
       :handler (routes/app {:ds ds})
       :category-ids {:dev (:id dev) :meeting (:id meeting) :other (:id other)}})))

(defn empty-temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-api-empty" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    {:ds ds :handler (routes/app {:ds ds})}))

(defn request [handler method uri & [body]]
  (let [[path query] (str/split uri #"\?" 2)]
    (handler {:request-method method
            :uri path
            :query-string query
            :headers {"content-type" "application/json"}
            :body (when body (java.io.ByteArrayInputStream.
                              (.getBytes (json/generate-string body) "UTF-8")))})))

(defn parse-body [response]
  (json/parse-string (str (:body response)) keyword))

(defn work-log-by-title [handler title]
  (let [day (parse-body (request handler :get "/api/days/2026-07-06"))]
    (first (filter #(= title (:title %)) (:work-logs day)))))

(defn category-minutes [summary category-id]
  (get-in summary [:category-minutes (keyword (str category-id))]))

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
  (let [{:keys [handler category-ids]} (temp-system)]
    (testing "health endpoint"
      (is (= {:status "ok"} (parse-body (request handler :get "/health")))))

    (testing "imports candidate events through core mapping"
      (let [response (request handler :post "/api/candidates/import" {:events events})]
        (is (= 200 (:status response)))
        (is (= 3 (:imported (parse-body response))))))

    (testing "day endpoint returns snapshots with internal category ids"
      (let [body (parse-body (request handler :get "/api/days/2026-07-06"))
            build (first (filter #(= "Build" (:title %)) (:work-logs body)))]
        (is (= 3 (count (:work-logs body))))
        (is (= #{"confirmed" "excluded" "uncategorized"}
               (set (map :state (:work-logs body)))))
        (is (= (:dev category-ids) (:category-id build)))))

    (testing "summary endpoint returns manual-entry category totals"
      (let [body (parse-body (request handler :get "/api/days/2026-07-06/summary"))]
        (is (= 45 (category-minutes body (:dev category-ids))))
        (is (= 5 (get-in body [:other :rounding-residual-minutes])))
        (is (= (:other category-ids) (get-in body [:other :category-id])))))

    (testing "patch endpoint changes category"
      (let [unknown-id (:id (work-log-by-title handler "Unknown"))
            response (request handler :patch (str "/api/worklogs/" unknown-id)
                              {:state :confirmed :category-id (:meeting category-ids)})]
        (is (= 200 (:status response)))
        (is (= {:state "confirmed" :category-id (:meeting category-ids)}
               (select-keys (parse-body response) [:state :category-id])))
        (is (= 30 (category-minutes
                   (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                   (:meeting category-ids))))))

    (testing "patch endpoint changes time range and recomputes summary"
      (let [build-id (:id (work-log-by-title handler "Build"))
            response (request handler :patch (str "/api/worklogs/" build-id)
                              {:start-minute 540 :end-minute 570})]
        (is (= 200 (:status response)))
        (is (= {:start-minute 540 :end-minute 570}
               (select-keys (parse-body response) [:start-minute :end-minute])))
        (is (= 30 (category-minutes
                   (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                   (:dev category-ids))))))

    (testing "exclude endpoint removes a log from summary"
      (let [build-id (:id (work-log-by-title handler "Build"))]
        (is (= 200 (:status (request handler :patch (str "/api/worklogs/" build-id)
                                      {:state :excluded}))))
        (is (nil? (category-minutes
                   (parse-body (request handler :get "/api/days/2026-07-06/summary"))
                   (:dev category-ids))))))

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

(deftest api-manual-input-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)]
    (testing "empty day starts without work logs"
      (is (= [] (:work-logs (parse-body (request handler :get "/api/days/2026-07-07"))))))

    (testing "category endpoint creates a category without a client-supplied id"
      (let [response (request handler :post "/api/categories"
                              {:name "Development"})
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (pos-int? (:id body)))
        (is (= "Development" (:name body)))
        (is (nil? (:legacy-key body)))))

    (testing "category endpoint rejects client-supplied ids"
      (let [response (request handler :post "/api/categories"
                              {:id "dev" :name "Development"})]
        (is (= 400 (:status response)))
        (is (= "client-category-id-not-allowed" (:reason (parse-body response))))))

    (testing "category endpoint rejects duplicate sibling names"
      (let [response (request handler :post "/api/categories"
                              {:name "Development"})]
        (is (= 400 (:status response)))
        (is (= "duplicate-category-name" (:reason (parse-body response))))))

    (testing "manual worklog endpoint creates a categorized log"
      (let [dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))
            response (request handler :post "/api/days/2026-07-07/worklogs"
                              {:title "Build"
                               :start-minute 540
                               :end-minute 600
                               :category-id dev-id})]
        (is (= 200 (:status response)))
        (is (= {:title "Build"
                :state "confirmed"
                :category-id dev-id
                :start-minute 540
                :end-minute 600}
               (select-keys (parse-body response)
                            [:title :state :category-id :start-minute :end-minute])))
        (is (= 60 (category-minutes
                   (parse-body (request handler :get "/api/days/2026-07-07/summary"))
                   dev-id)))))

    (testing "manual worklog endpoint creates an uncategorized log without a category"
      (let [response (request handler :post "/api/days/2026-07-07/worklogs"
                              {:title "Triage"
                               :start-minute 615
                               :end-minute 645})]
        (is (= 200 (:status response)))
        (is (= {:title "Triage"
                :state "uncategorized"
                :start-minute 615
                :end-minute 645}
               (select-keys (parse-body response)
                            [:title :state :start-minute :end-minute])))
        (is (nil? (:category-id (parse-body response))))
        (let [summary (parse-body (request handler :get "/api/days/2026-07-07/summary"))]
          (is (= 30 (category-minutes summary "uncategorized")))
          (is (empty? (filter #(= "uncategorized" (:type %)) (:warnings summary)))))))

    (testing "manual worklog rejects unknown categories without mutation"
      (let [response (request handler :post "/api/days/2026-07-08/worklogs"
                              {:title "Missing category"
                               :start-minute 600
                               :end-minute 660
                               :category-id "missing"})]
        (is (= 400 (:status response)))
        (is (= "unknown-category" (:reason (parse-body response))))
        (is (= [] (:work-logs (parse-body (request handler :get "/api/days/2026-07-08")))))))

    (testing "manual worklog rejects invalid ranges without mutation"
      (let [dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))
            response (request handler :post "/api/days/2026-07-08/worklogs"
                              {:title "Bad range"
                               :start-minute 660
                               :end-minute 660
                               :category-id dev-id})]
        (is (= 400 (:status response)))
        (is (= "invalid-time-range" (:reason (parse-body response))))
        (is (= [] (:work-logs (parse-body (request handler :get "/api/days/2026-07-08")))))))))

(deftest api-title-suggestions-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        meetings (db/upsert-category! ds {:name "Meetings"})
        engineering (db/upsert-category! ds {:name "Engineering"})
        backend (db/upsert-category! ds {:name "Backend" :parent-id (:id engineering)})]
    (db/upsert-title-mapping! ds {:title "Mapping only" :state :confirmed :category-id (:id dev)})
    (doseq [work-log [{:date "2026-07-01"
                       :title "Build API"
                       :start-minute 540
                       :end-minute 600
                       :state :confirmed
                       :category-id (:id dev)}
                      {:date "2026-07-02"
                       :title "Build API"
                       :start-minute 600
                       :end-minute 660
                       :state :confirmed
                       :category-id (:id dev)}
                      {:date "2026-07-03"
                       :title "Backend planning"
                       :start-minute 660
                       :end-minute 720
                       :state :confirmed
                       :category-id (:id backend)}
                      {:date "2026-07-04"
                       :title "Design sync"
                       :start-minute 720
                       :end-minute 780
                       :state :confirmed
                       :category-id (:id meetings)}
                      {:date "2026-07-05"
                       :title "Loose task"
                       :start-minute 780
                       :end-minute 840
                       :state :uncategorized}
                      {:date "2026-07-06"
                       :title "Excluded lunch"
                       :start-minute 840
                       :end-minute 900
                       :state :excluded
                       :category-id (:id meetings)}
                      {:date "2026-07-07"
                       :title "Parent planning"
                       :start-minute 900
                       :end-minute 960
                       :state :confirmed
                       :category-id (:id engineering)}]]
      (db/insert-work-log! ds work-log))

    (testing "endpoint returns fuzzy suggestions with category metadata"
      (let [response (request handler :get "/api/worklog-title-suggestions?q=bapi")
            suggestions (:suggestions (parse-body response))
            first-suggestion (first suggestions)]
        (is (= 200 (:status response)))
        (is (<= 1 (count suggestions)))
        (is (= "Build API" (:title first-suggestion)))
        (is (= (:id dev) (:category-id first-suggestion)))
        (is (= "Development" (:category-name first-suggestion)))
        (is (= "confirmed" (:state first-suggestion)))
        (is (= "subsequence" (:match-kind first-suggestion)))
        (is (= "work-logs" (:source first-suggestion)))
        (is (= 2 (:use-count first-suggestion)))
        (is (= "2026-07-02" (:last-used-date first-suggestion)))))

    (testing "endpoint includes parent path and uncategorized suggestions"
      (let [backend-suggestion (first (:suggestions
                                       (parse-body (request handler :get
                                                            "/api/worklog-title-suggestions?q=plan"))))
            loose-suggestion (first (:suggestions
                                     (parse-body (request handler :get
                                                          "/api/worklog-title-suggestions?q=loose"))))]
        (is (= "Backend planning" (:title backend-suggestion)))
        (is (= "Engineering / Backend" (:category-name backend-suggestion)))
        (is (= (:id backend) (:category-id backend-suggestion)))
        (is (= "Loose task" (:title loose-suggestion)))
        (is (nil? (:category-id loose-suggestion)))
        (is (= "Uncategorized" (:category-name loose-suggestion)))))

    (testing "blank query and limit are bounded"
      (is (= [] (:suggestions (parse-body (request handler :get
                                                   "/api/worklog-title-suggestions?q=")))))
      (is (= 1 (count (:suggestions (parse-body (request handler :get
                                                         "/api/worklog-title-suggestions?q=i&limit=1"))))))
      (is (<= (count (:suggestions (parse-body (request handler :get
                                                        "/api/worklog-title-suggestions?q=i&limit=100"))))
              20)))

    (testing "title mappings, excluded logs, imported drafts, and parents do not leak into suggestions"
      (let [import-response (request handler :post "/api/candidates/import"
                                     {:events [{:source-id "ical:test"
                                                :external-id "evt-imported-draft"
                                                :title "Imported draft"
                                                :starts-at "2026-07-08T09:00+09:00"
                                                :ends-at "2026-07-08T10:00+09:00"
                                                :timezone "Asia/Tokyo"
                                                :updated-at "2026-07-08T00:00:00Z"}]})
            titles (set (map :title (:suggestions
                                     (parse-body (request handler :get
                                                          "/api/worklog-title-suggestions?q=i&limit=20")))))]
        (is (= 200 (:status import-response)))
        (is (= 1 (:work-logs-created (parse-body import-response))))
        (is (not (contains? titles "Mapping only")))
        (is (not (contains? titles "Excluded lunch")))
        (is (not (contains? titles "Imported draft")))
        (is (not (contains? titles "Parent planning")))))))

(deftest api-category-hierarchy-e2e-test
  (let [{:keys [handler]} (empty-temp-system)]
    (testing "parent categories are visible but not assignable after a child is created"
      (let [ops (parse-body (request handler :post "/api/categories" {:name "Operations"}))
            engineering (parse-body (request handler :post "/api/categories" {:name "Engineering"}))
            frontend (parse-body (request handler :post "/api/categories"
                                          {:name "Frontend" :parent-id (:id engineering)}))
            backend (parse-body (request handler :post "/api/categories"
                                         {:name "Backend" :parent-id (:id engineering)}))]
        (is (= (:id engineering) (:parent-id frontend)))
        (is (= (:id engineering) (:parent-id backend)))
        (is (= 400 (:status (request handler :post "/api/categories"
                                      {:name "Nested" :parent-id (:id frontend)}))))
        (is (= "nested-category-not-allowed"
               (:reason (parse-body (request handler :post "/api/categories"
                                             {:name "Nested" :parent-id (:id frontend)})))))
        (is (= 400 (:status (request handler :post "/api/days/2026-07-09/worklogs"
                                      {:title "Parent assignment"
                                       :start-minute 540
                                       :end-minute 600
                                       :category-id (:id engineering)}))))
        (is (= "non-assignable-category"
               (:reason (parse-body
                         (request handler :post "/api/days/2026-07-09/worklogs"
                                  {:title "Parent assignment"
                                   :start-minute 540
                                   :end-minute 600
                                   :category-id (:id engineering)})))))
        (is (= 200 (:status (request handler :post "/api/days/2026-07-09/worklogs"
                                      {:title "Child assignment"
                                       :start-minute 600
                                       :end-minute 660
                                       :category-id (:id frontend)}))))
        (is (= 60 (category-minutes
                   (parse-body (request handler :get "/api/days/2026-07-09/summary"))
                   (:id frontend))))
        (is (= 400 (:status (request handler :post "/api/categories"
                                      {:name "Frontend" :parent-id (:id engineering)}))))
        (is (= 200 (:status (request handler :post "/api/categories"
                                      {:name "Frontend" :parent-id (:id ops)}))))
        (is (= 200 (:status (request handler :post (str "/api/categories/" (:id engineering) "/move")
                                      {:direction "up"}))))
        (is (= 200 (:status (request handler :post (str "/api/categories/" (:id backend) "/move")
                                      {:direction "up"}))))
        (is (= ["Engineering" "Backend" "Frontend" "Operations" "Frontend"]
               (map :name (:categories (parse-body (request handler :get "/api/categories"))))))))))

(deftest api-category-rename-delete-e2e-test
  (let [{:keys [handler]} (empty-temp-system)]
    (testing "category rename persists and rejects duplicate siblings"
      (let [engineering (parse-body (request handler :post "/api/categories"
                                             {:name "Engineering"}))
            frontend (parse-body (request handler :post "/api/categories"
                                          {:name "Frontend"
                                           :parent-id (:id engineering)}))
            backend (parse-body (request handler :post "/api/categories"
                                         {:name "Backend"
                                          :parent-id (:id engineering)}))
            rename-response (request handler :patch
                                     (str "/api/categories/" (:id backend))
                                     {:name "Platform"})
            duplicate-response (request handler :patch
                                        (str "/api/categories/" (:id backend))
                                        {:name "Frontend"})
            listed (:categories (parse-body (request handler :get "/api/categories")))]
        (is (= 200 (:status rename-response)))
        (is (= "Platform" (:name (parse-body rename-response))))
        (is (= 400 (:status duplicate-response)))
        (is (= "duplicate-category-name" (:reason (parse-body duplicate-response))))
        (is (= ["Engineering" "Frontend" "Platform"]
               (map :name listed)))
        (is (= (:id engineering) (:parent-id frontend)))))

    (testing "delete hard-deletes unreferenced categories"
      (let [temporary (parse-body (request handler :post "/api/categories"
                                           {:name "Temporary"}))
            response (request handler :delete
                              (str "/api/categories/" (:id temporary)))
            listed (:categories (parse-body (request handler :get "/api/categories")))]
        (is (= 200 (:status response)))
        (is (= "hard" (:mode (parse-body response))))
        (is (not (some #(= (:id temporary) (:id %)) listed)))))

    (testing "delete rejects parents with active children"
      (let [engineering (first (filter #(= "Engineering" (:name %))
                                       (:categories (parse-body (request handler :get "/api/categories")))))
            response (request handler :delete
                              (str "/api/categories/" (:id engineering)))]
        (is (= 400 (:status response)))
        (is (= "has-active-children" (:reason (parse-body response))))))

    (testing "delete soft-deletes assigned categories and preserves summaries"
      (let [frontend (first (filter #(= "Frontend" (:name %))
                                    (:categories (parse-body (request handler :get "/api/categories")))))
            assigned (request handler :post "/api/days/2026-07-09/worklogs"
                              {:title "Frontend work"
                               :start-minute 540
                               :end-minute 600
                               :category-id (:id frontend)})
            response (request handler :delete
                              (str "/api/categories/" (:id frontend)))
            deleted (first (filter #(= (:id frontend) (:id %))
                                   (:categories (parse-body (request handler :get "/api/categories")))))
            rejected (request handler :post "/api/days/2026-07-09/worklogs"
                              {:title "More frontend"
                               :start-minute 600
                               :end-minute 660
                               :category-id (:id frontend)})
            summary (parse-body (request handler :get "/api/days/2026-07-09/summary"))]
        (is (= 200 (:status assigned)))
        (is (= 200 (:status response)))
        (is (= "soft" (:mode (parse-body response))))
        (is (false? (:active? deleted)))
        (is (= 400 (:status rejected)))
        (is (= "non-assignable-category" (:reason (parse-body rejected))))
        (is (= 60 (category-minutes summary (:id frontend))))))))

(deftest api-attendance-and-breaks-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})]
    (testing "day endpoint starts without attendance or materialized breaks"
      (let [day (parse-body (request handler :get "/api/days/2026-07-10"))]
        (is (nil? (:attendance day)))
        (is (= [] (:breaks day)))))

    (testing "attendance endpoint persists day-level clock-in and clock-out"
      (let [response (request handler :put "/api/days/2026-07-10/attendance"
                              {:clock-in-minute 540
                               :clock-out-minute 1080})
            body (parse-body response)
            day (parse-body (request handler :get "/api/days/2026-07-10"))]
        (is (= 200 (:status response)))
        (is (= {:date "2026-07-10"
                :clock-in-minute 540
                :clock-out-minute 1080}
               (select-keys body [:date :clock-in-minute :clock-out-minute])))
        (is (= body (:attendance day)))))

    (testing "invalid attendance is rejected without mutation"
      (let [response (request handler :put "/api/days/2026-07-10/attendance"
                              {:clock-in-minute 1080
                               :clock-out-minute 540})
            attendance (:attendance (parse-body (request handler :get "/api/days/2026-07-10")))]
        (is (= 400 (:status response)))
        (is (= "invalid-time-range" (:reason (parse-body response))))
        (is (= {:clock-in-minute 540 :clock-out-minute 1080}
               (select-keys attendance [:clock-in-minute :clock-out-minute])))))

    (testing "daily break rule materializes one editable break per day"
      (let [response (request handler :post "/api/break-rules"
                              {:title "Lunch"
                               :start-minute 720
                               :end-minute 780
                               :enabled true})
            rule (parse-body response)
            first-day (parse-body (request handler :get "/api/days/2026-07-10"))
            second-day (parse-body (request handler :get "/api/days/2026-07-10"))
            break (first (:breaks second-day))]
        (is (= 200 (:status response)))
        (is (pos-int? (:id rule)))
        (is (= "Lunch" (:title rule)))
        (is (= 1 (count (:breaks first-day))))
        (is (= 1 (count (:breaks second-day))))
        (is (= (:id rule) (:break-rule-id break)))
        (is (= {:title "Lunch" :start-minute 720 :end-minute 780 :active? true}
               (select-keys break [:title :start-minute :end-minute :active?])))))

    (testing "breaks suppress false large-gap warnings and are excluded from work effort"
      (let [dev-id (:id dev)]
        (is (= 200 (:status (request handler :post "/api/days/2026-07-10/worklogs"
                                      {:title "Morning"
                                       :start-minute 540
                                       :end-minute 720
                                       :category-id dev-id}))))
        (is (= 200 (:status (request handler :post "/api/days/2026-07-10/worklogs"
                                      {:title "Afternoon"
                                       :start-minute 780
                                       :end-minute 1020
                                       :category-id dev-id}))))
        (let [summary (parse-body (request handler :get "/api/days/2026-07-10/summary"))]
          (is (= 420 (category-minutes summary dev-id)))
          (is (= 60 (get-in summary [:attendance :break-minutes])))
          (is (= 60 (get-in summary [:attendance :unallocated-minutes])))
          (is (empty? (filter #(= "large-gap" (:type %)) (:warnings summary)))))))

    (testing "break range patch validates and preserves the previous range on error"
      (let [break-id (:id (first (:breaks (parse-body (request handler :get "/api/days/2026-07-10")))))
            bad-response (request handler :patch (str "/api/breaks/" break-id)
                                  {:start-minute 800 :end-minute 800})
            unchanged (db/get-break ds break-id)
            good-response (request handler :patch (str "/api/breaks/" break-id)
                                   {:start-minute 735 :end-minute 795})]
        (is (= 400 (:status bad-response)))
        (is (= "invalid-time-range" (:reason (parse-body bad-response))))
        (is (= {:start-minute 720 :end-minute 780}
               (select-keys unchanged [:start-minute :end-minute])))
        (is (= 200 (:status good-response)))
        (is (= {:start-minute 735 :end-minute 795}
               (select-keys (parse-body good-response)
                            [:start-minute :end-minute])))))

    (testing "a break can be converted into categorized work effort"
      (let [break-id (:id (first (:breaks (parse-body (request handler :get "/api/days/2026-07-10")))))
            reset-response (request handler :patch (str "/api/breaks/" break-id)
                                    {:start-minute 720 :end-minute 780})
            response (request handler :post (str "/api/breaks/" break-id "/convert")
                              {:title "Lunch support"
                               :category-id (:id dev)})
            body (parse-body response)
            day (parse-body (request handler :get "/api/days/2026-07-10"))
            summary (parse-body (request handler :get "/api/days/2026-07-10/summary"))]
        (is (= 200 (:status reset-response)))
        (is (= 200 (:status response)))
        (is (= {:title "Lunch support"
                :state "confirmed"
                :category-id (:id dev)}
               (select-keys body [:title :state :category-id])))
        (is (empty? (:breaks day)))
        (is (= 480 (category-minutes summary (:id dev))))
        (is (= 0 (get-in summary [:attendance :break-minutes])))))

    (testing "fixed materialized breaks can be deleted without reappearing as virtual breaks"
      (is (= 200 (:status (request handler :post "/api/break-rules"
                                    {:title "Dinner"
                                     :start-minute 1320
                                     :end-minute 1350
                                     :enabled true}))))
      (let [day (parse-body (request handler :get "/api/days/2026-07-12"))
            break (first (filter #(= "Dinner" (:title %)) (:breaks day)))
            response (request handler :delete (str "/api/breaks/" (:id break)))
            day-after-delete (parse-body (request handler :get "/api/days/2026-07-12"))]
        (is (pos-int? (:id break)))
        (is (= 200 (:status response)))
        (is (false? (:active? (parse-body response))))
        (is (not-any? #(= "Dinner" (:title %)) (:breaks day-after-delete)))))

    (testing "daily break rule endpoints update and soft-delete future defaults"
      (let [created (parse-body (request handler :post "/api/break-rules"
                                         {:title "Snack"
                                          :start-minute 900
                                          :end-minute 915
                                          :enabled true}))
            patched (request handler :patch (str "/api/break-rules/" (:id created))
                             {:title "Tea"
                              :start-minute 930
                              :end-minute 945
                              :enabled true})
            day-before-delete (parse-body (request handler :get "/api/days/2026-07-18"))
            deleted (request handler :delete (str "/api/break-rules/" (:id created)))
            rules-after-delete (parse-body (request handler :get "/api/break-rules"))
            day-after-delete (parse-body (request handler :get "/api/days/2026-07-19"))]
        (is (= 200 (:status patched)))
        (is (= {:title "Tea" :start-minute 930 :end-minute 945 :active? true}
               (select-keys (parse-body patched) [:title :start-minute :end-minute :active?])))
        (is (some #(= "Tea" (:title %)) (:breaks day-before-delete)))
        (is (= 200 (:status deleted)))
        (is (false? (:active? (parse-body deleted))))
        (is (not-any? #(= "Tea" (:title %)) (:break-rules rules-after-delete)))
        (is (some #(= "Tea" (:title %)) (:breaks day-before-delete)))
        (is (not-any? #(= "Tea" (:title %)) (:breaks day-after-delete)))))))

(deftest api-settings-and-calendar-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        unallocated (db/upsert-category! ds {:name "Unallocated"})
        dev-id (:id dev)]
    (testing "settings endpoint exposes defaults and persists break/holiday policy"
      (let [default-settings (parse-body (request handler :get "/api/settings"))
            response (request handler :put "/api/settings"
                              {:break-mode :flexible
                               :holiday-policy-mode :manual
                               :holiday-weekdays []
                               :week-start-day 7
                               :fiscal-month-start-day 21})
            updated (parse-body (request handler :get "/api/settings"))]
        (is (= {:break-mode "fixed"
                :week-start-day 1
                :fiscal-month-start-day 1
                :holiday-policy {:mode "complete-two-day"
                                 :weekdays [6 7]}}
               (select-keys default-settings [:break-mode :week-start-day
                                              :fiscal-month-start-day
                                              :holiday-policy])))
        (is (= 200 (:status response)))
        (is (= "flexible" (:break-mode (parse-body response))))
        (is (= "flexible" (:break-mode updated)))
        (is (= 7 (:week-start-day updated)))
        (is (= 21 (:fiscal-month-start-day updated)))
        (is (= {:mode "manual" :weekdays []}
               (:holiday-policy updated)))
        (is (= 200 (:status (request handler :put "/api/settings"
                                      {:week-start-day 1
                                       :fiscal-month-start-day 1}))))))

    (testing "flexible break mode skips daily materialization but keeps one-off breaks"
      (is (= 200 (:status (request handler :post "/api/break-rules"
                                    {:title "Lunch"
                                     :start-minute 720
                                     :end-minute 780
                                     :enabled true}))))
      (is (= [] (:breaks (parse-body (request handler :get "/api/days/2026-07-13")))))
      (let [one-off (request handler :post "/api/days/2026-07-13/breaks"
                             {:title "Break"
                              :start-minute 900
                              :end-minute 915})
            day (parse-body (request handler :get "/api/days/2026-07-13"))]
        (is (= 200 (:status one-off)))
        (is (= [{:title "Break"
                 :start-minute 900
                 :end-minute 915}]
               (map #(select-keys % [:title :start-minute :end-minute])
                    (:breaks day))))))

    (testing "fixed break mode materializes daily rules"
      (let [response (request handler :put "/api/settings" {:break-mode :fixed})
            day (parse-body (request handler :get "/api/days/2026-07-14"))]
        (is (= 200 (:status response)))
        (is (= ["Lunch"] (map :title (:breaks day))))))

    (testing "calendar endpoint classifies holidays, missing days, and complete days"
      (is (= 200 (:status (request handler :put "/api/days/2026-07-15/attendance"
                                    {:clock-in-minute 540
                                     :clock-out-minute 1080}))))
      (is (= 200 (:status (request handler :post "/api/days/2026-07-15/worklogs"
                                    {:title "Build"
                                     :start-minute 540
                                     :end-minute 1020
                                     :category-id dev-id}))))
      (is (= 200 (:status (request handler :put "/api/days/2026-07-16/attendance"
                                    {:clock-in-minute 540
                                     :clock-out-minute 1080}))))
      (is (= 200 (:status (request handler :post "/api/days/2026-07-16/worklogs"
                                    {:title "Unallocated backlog"
                                     :start-minute 540
                                     :end-minute 1080
                                     :category-id (:id unallocated)}))))
      (is (= 200 (:status (request handler :put "/api/days/2026-07-17/attendance"
                                    {:clock-in-minute 540
                                     :clock-out-minute 600}))))
      (is (= 200 (:status (request handler :post "/api/days/2026-07-17/worklogs"
                                    {:title "Needs category"
                                     :start-minute 540
                                     :end-minute 600}))))
      (is (= 200 (:status (request handler :post "/api/day-status-ranges"
                                    {:start-date "2026-07-20"
                                     :end-date "2026-07-22"
                                     :status :holiday}))))
      (let [calendar (parse-body (request handler :get "/api/calendar?view=month&date=2026-07-15"))
            days-by-date (into {} (map (juxt :date identity)) (:days calendar))]
        (is (= "month" (:view calendar)))
        (is (= "done" (get-in days-by-date ["2026-07-15" :status])))
        (is (= "done" (get-in days-by-date ["2026-07-16" :status])))
        (is (= 0 (get-in days-by-date ["2026-07-16" :unallocated-minutes])))
        (is (= 540 (get-in days-by-date ["2026-07-16" :category-minutes
                                          (keyword (str (:id unallocated)))])))
        (is (= "done" (get-in days-by-date ["2026-07-17" :status])))
        (is (= 60 (get-in days-by-date ["2026-07-17" :uncategorized-minutes])))
        (is (= 60 (get-in days-by-date ["2026-07-17" :category-minutes
                                         :uncategorized])))
        (is (nil? (get-in days-by-date ["2026-07-17" :category-minutes
                                         (keyword (str (:id unallocated)))])))
        (is (= 0 (get-in days-by-date ["2026-07-17" :unallocated-minutes])))
        (is (= "holiday" (get-in days-by-date ["2026-07-20" :status])))
        (is (= "holiday" (get-in days-by-date ["2026-07-21" :status])))
        (is (= "holiday" (get-in days-by-date ["2026-07-22" :status])))))))

(deftest api-calendar-settings-e2e-test
  (let [{:keys [handler]} (empty-temp-system)]
    (testing "calendar settings shift week starts and fiscal month ranges"
      (is (= 200 (:status (request handler :put "/api/settings"
                                    {:week-start-day 7
                                     :fiscal-month-start-day 21}))))
      (let [week (parse-body (request handler :get "/api/calendar?view=week&date=2026-07-15"))
            fiscal-month (parse-body (request handler :get "/api/calendar?view=month&date=2026-07-15"))]
        (is (= 7 (:week-start-day week)))
        (is (= ["2026-07-12" "2026-07-13" "2026-07-14"
                "2026-07-15" "2026-07-16" "2026-07-17" "2026-07-18"]
               (map :date (:days week))))
        (is (= "2026-06-21" (:period-start fiscal-month)))
        (is (= "2026-07-20" (:period-end fiscal-month)))
        (is (= "2026-06-21" (-> fiscal-month :days first :date)))
        (is (= "2026-07-20" (-> fiscal-month :days last :date)))))))

(deftest api-overlap-and-boundary-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        dev-id (:id dev)
        left (parse-body (request handler :post "/api/days/2026-07-12/worklogs"
                                  {:title "Left"
                                   :start-minute 540
                                   :end-minute 600
                                   :category-id dev-id}))
        right (parse-body (request handler :post "/api/days/2026-07-12/worklogs"
                                   {:title "Right"
                                    :start-minute 600
                                    :end-minute 660
                                    :category-id dev-id}))]
    (testing "confirmed overlap creation is rejected without mutation"
      (let [before (:work-logs (parse-body (request handler :get "/api/days/2026-07-12")))
            response (request handler :post "/api/days/2026-07-12/worklogs"
                              {:title "Overlap"
                               :start-minute 570
                               :end-minute 630
                               :category-id dev-id})
            after (:work-logs (parse-body (request handler :get "/api/days/2026-07-12")))]
        (is (= 400 (:status response)))
        (is (= "overlaps-confirmed-work-log" (:reason (parse-body response))))
        (is (= (map :id before) (map :id after)))
        (is (= 2 (count after)))))

    (testing "confirmed overlap patch is rejected without mutation"
      (let [response (request handler :patch (str "/api/worklogs/" (:id right))
                              {:start-minute 585
                               :end-minute 645})
            unchanged (db/get-work-log ds (:id right))]
        (is (= 400 (:status response)))
        (is (= "overlaps-confirmed-work-log" (:reason (parse-body response))))
        (is (= {:start-minute 600 :end-minute 660}
               (select-keys unchanged [:start-minute :end-minute])))))

    (testing "non-overlap patch still succeeds"
      (let [response (request handler :patch (str "/api/worklogs/" (:id right))
                              {:start-minute 615
                               :end-minute 675})]
        (is (= 200 (:status response)))
        (is (= {:start-minute 615 :end-minute 675}
               (select-keys (parse-body response)
                            [:start-minute :end-minute])))))

    (testing "boundary adjustment updates adjacent ranges together"
      (is (= 200 (:status (request handler :patch (str "/api/worklogs/" (:id right))
                                    {:start-minute 600
                                     :end-minute 660}))))
      (let [response (request handler :post "/api/days/2026-07-12/boundary-adjustments"
                              {:left {:kind "work-log" :id (:id left)}
                               :right {:kind "work-log" :id (:id right)}
                               :boundary-minute 615})
            body (parse-body response)]
        (is (= 200 (:status response)))
        (is (= {:end-minute 615}
               (select-keys (:left body) [:end-minute])))
        (is (= {:start-minute 615}
               (select-keys (:right body) [:start-minute])))
        (is (= 120 (category-minutes
                    (parse-body (request handler :get "/api/days/2026-07-12/summary"))
                    dev-id)))))

    (testing "invalid boundary adjustment is rejected without mutation"
      (let [response (request handler :post "/api/days/2026-07-12/boundary-adjustments"
                              {:left {:kind "work-log" :id (:id left)}
                               :right {:kind "work-log" :id (:id right)}
                               :boundary-minute 540})
            left-after (db/get-work-log ds (:id left))
            right-after (db/get-work-log ds (:id right))]
        (is (= 400 (:status response)))
        (is (= "invalid-time-range" (:reason (parse-body response))))
        (is (= 615 (:end-minute left-after)))
        (is (= 615 (:start-minute right-after)))))))

(deftest api-source-snapshot-e2e-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:id "dev" :name "Development"})]
    (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id (:id dev)})

    (testing "candidate import stores source events and exposes them on the day"
      (let [response (request handler :post "/api/candidates/import"
                              {:events [{:source-id "ical:test"
                                         :external-id "evt-build"
                                         :title "Build"
                                         :starts-at "2026-07-06T09:00+09:00"
                                         :ends-at "2026-07-06T10:00+09:00"
                                         :timezone "Asia/Tokyo"
                                         :updated-at "2026-07-06T00:00:00Z"}]})
            body (parse-body response)
            day (parse-body (request handler :get "/api/days/2026-07-06"))]
        (is (= 200 (:status response)))
        (is (= 1 (:imported body)))
        (is (= 1 (:work-logs-created body)))
        (is (= ["evt-build"] (map :external-id (:source-events day))))
        (is (= ["Build"] (map :title (:work-logs day))))))

    (testing "source refetch does not overwrite an edited snapshot"
      (let [log-id (:id (work-log-by-title handler "Build"))]
        (is (= 200 (:status (request handler :patch (str "/api/worklogs/" log-id)
                                      {:state :excluded
                                       :start-minute 555
                                       :end-minute 585}))))
        (let [response (request handler :post "/api/candidates/import"
                                {:events [{:source-id "ical:test"
                                           :external-id "evt-build"
                                           :title "Build moved"
                                           :starts-at "2026-07-06T10:00+09:00"
                                           :ends-at "2026-07-06T11:00+09:00"
                                           :timezone "Asia/Tokyo"
                                           :updated-at "2026-07-06T01:00:00Z"}]})
              day (parse-body (request handler :get "/api/days/2026-07-06"))
              log (first (:work-logs day))
              source-event (first (:source-events day))
              summary (parse-body (request handler :get "/api/days/2026-07-06/summary"))]
          (is (= 200 (:status response)))
          (is (= 0 (:work-logs-created (parse-body response))))
          (is (= "Build moved" (:title source-event)))
          (is (= "Build" (:title log)))
          (is (= {:state "excluded" :start-minute 555 :end-minute 585}
                 (select-keys log [:state :start-minute :end-minute])))
          (is (= [{:type "source-updated"
                   :work-log-id log-id
                   :external-id "evt-build"}]
                 (map #(select-keys % [:type :work-log-id :external-id])
                      (:warnings summary)))))))))

(deftest api-import-source-e2e-test
  (let [{:keys [handler]} (empty-temp-system)
        fixture-path (.getPath (io/file (io/resource "fixtures/ical/basic.ics")))]
    (testing "import source endpoint creates zero-or-more iCal configurations"
      (let [empty-list (parse-body (request handler :get "/api/import-sources"))
            response (request handler :post "/api/import-sources"
                              {:kind :ical
                               :name "Fixture calendar"
                               :uri fixture-path
                               :enabled true
                               :fetch-interval-minutes 15})
            body (parse-body response)
            listed (parse-body (request handler :get "/api/import-sources"))]
        (is (= [] (:import-sources empty-list)))
        (is (= 200 (:status response)))
        (is (pos-int? (:id body)))
        (is (= "ical" (:kind body)))
        (is (= "Fixture calendar" (:name body)))
        (is (= [(:id body)] (map :id (:import-sources listed))))))

    (testing "manual fetch imports iCal file source through the common source interface"
      (let [source-id (:id (first (:import-sources
                                  (parse-body (request handler :get "/api/import-sources")))))
            response (request handler :post (str "/api/import-sources/" source-id "/fetch"))
            body (parse-body response)
            day (parse-body (request handler :get "/api/days/2026-07-06"))
            runs (parse-body (request handler :get (str "/api/import-sources/" source-id "/runs")))]
        (is (= 200 (:status response)))
        (is (= 1 (:fetched body)))
        (is (= 1 (:work-logs-created body)))
        (is (= ["basic-1"] (map :external-id (:source-events day))))
        (is (= ["Build"] (map :title (:work-logs day))))
        (is (= ["success"] (map :status (:import-runs runs))))))))
