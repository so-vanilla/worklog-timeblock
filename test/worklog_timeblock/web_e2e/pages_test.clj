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
   (let [[path query] (str/split uri #"\?" 2)]
     (handler {:request-method method
             :uri path
             :query-string query
             :headers {"content-type" "application/x-www-form-urlencoded"}
             :body (when body (java.io.ByteArrayInputStream.
                               (.getBytes body "UTF-8")))}))))

(defn response-body [response]
  (str (:body response)))

(deftest web-smoke-test
  (let [{:keys [handler ids category-ids]} (temp-system)]
    (testing "home page renders a Days calendar and links to a day view"
      (let [html (response-body (request handler "/?view=month&date=2026-07-06"))]
        (is (str/includes? html "worklog-timeblock"))
        (is (str/includes? html "class=\"days-calendar\""))
        (is (str/includes? html "data-calendar-view=\"month\""))
        (is (str/includes? html "data-calendar-edit=\"inactive\""))
        (is (str/includes? html "/days/2026-07-06"))
        (is (str/includes? html "href=\"/?view=week&amp;date=2026-07-06\""))
        (is (str/includes? html "href=\"/?view=month&amp;date=2026-07-06&amp;edit=1\""))
        (is (str/includes? html "href=\"/?view=month&amp;date=2026-06-06\""))
        (is (str/includes? html "href=\"/?view=month&amp;date=2026-08-06\""))
        (is (str/includes? html "Prev month"))
        (is (str/includes? html "Next month"))
        (is (str/includes? html "class=\"toggle-option active\""))
        (is (str/includes? html "name=\"date\" value=\"2026-07-06\""))
        (is (str/includes? html "TODAY"))))

    (testing "day page renders a full-viewport worklog workspace"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html "class=\"day-workspace\""))
        (is (str/includes? html "class=\"timeline-pane\""))
        (is (str/includes? html "class=\"entry-pane\""))
        (is (str/includes? html "class=\"summary-pane\""))
        (is (str/includes? html "class=\"day-timeline\""))
        (is (str/includes? html "class=\"timeline-track\""))
        (is (str/includes? html "href=\"/settings\""))
        (is (str/includes? html "<nav class=\"page-actions\"><a class=\"nav-button\" href=\"/\">Days</a><a class=\"nav-button\" href=\"/settings\">Settings</a></nav>"))
        (is (str/includes? html "<div class=\"workspace-title-row\"><h1>2026-07-06</h1><div class=\"day-navigation\">"))
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
        (is (str/includes? html "data-auto-submit=\"category\""))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/range\"")))
        (is (str/includes? html (str "action=\"/worklogs/" (:build ids) "/exclude\"")))
        (is (str/includes? html "name=\"category-id\""))
        (is (str/includes? html (str "value=\"" (:meeting category-ids) "\"")))
        (is (not (str/includes? html "value=\"meeting\"")))
        (is (not (str/includes? html ">Set</button>")))
        (is (not (str/includes? html "work-log-category-label")))
        (is (str/includes? html "id=\"new-work-log-form\""))
        (is (str/includes? html "id=\"draft-summary-preview\""))
        (is (str/includes? html "aria-autocomplete=\"list\""))
        (is (str/includes? html "aria-controls=\"title-suggestion-list\""))
        (is (str/includes? html "aria-expanded=\"false\""))
        (is (str/includes? html "id=\"title-suggestion-list\""))
        (is (str/includes? html "class=\"title-suggestion-list\""))
        (is (str/includes? html "role=\"listbox\""))
        (is (not (str/includes? html "title-candidate")))
        (is (str/includes? html "id=\"candidate-menu\""))
        (is (str/includes? html "name=\"start-time\""))
        (is (str/includes? html "name=\"end-time\""))))

    (testing "day page renders uncategorized work as a normal visible block"
      (let [html (response-body (request handler "/days/2026-07-06"))]
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "Unknown"))
        (is (str/includes? html "uncategorized-block"))
        (is (not (str/includes? html "Uncategorized: Unknown")))))

    (testing "right pane is ordered as attendance, totals, then categories"
      (let [html (response-body (request handler "/days/2026-07-06"))
            attendance-pos (str/index-of html "<section class=\"input-panel attendance-panel\"")
            totals-pos (str/index-of html "<section class=\"input-panel category-totals-panel\"")
            categories-pos (str/index-of html "<section class=\"input-panel category-settings-panel\"")]
        (is (some? attendance-pos))
        (is (some? totals-pos))
        (is (some? categories-pos))
        (is (< attendance-pos totals-pos))
        (is (< totals-pos categories-pos))))

    (testing "category form updates the persisted log and rendered summary"
      (let [response (request handler :post
                              (str "/worklogs/" (:unknown ids) "/assign-category")
                              (str "category-id=" (:meeting category-ids)))
            html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-06" (get-in response [:headers "location"])))
        (is (str/includes? html "Meetings"))
        (is (str/includes? html "0.50h"))))

    (testing "category form can return a confirmed log to uncategorized"
      (let [response (request handler :post
                              (str "/worklogs/" (:build ids) "/assign-category")
                              "category-id=")
            html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-06" (get-in response [:headers "location"])))
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "Build"))
        (is (str/includes? html "data-summary-category-id=\"uncategorized\""))
        (is (not (str/includes? html "Uncategorized: Build")))))

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
    (testing "home page exposes a date picker and calendar for an empty database"
      (let [html (response-body (request handler "/?view=month&date=2026-07-07"))]
        (is (str/includes? html "class=\"days-calendar\""))
        (is (str/includes? html "action=\"/\""))
        (is (str/includes? html "name=\"date\""))
        (is (str/includes? html "type=\"date\""))
        (is (str/includes? html "data-date=\"2026-07-07\""))))

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
        (is (str/includes? html "category-settings-panel"))
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
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "data-summary-category-id=\"uncategorized\""))
        (is (not (str/includes? html "Uncategorized: Triage")))))))

(deftest web-days-calendar-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        dev-id (:id dev)]
    (request handler :post "/days/2026-07-15/attendance"
             "clock-in-time=09%3A00&clock-out-time=18%3A00")
    (request handler :post "/break-rules"
             "break-title=Lunch&start-time=12%3A00&end-time=13%3A00&redirect-to=%2F")
    (request handler :post "/days/2026-07-15/worklogs"
             (str "title=Build&start-time=09%3A00&end-time=17%3A00&category-id=" dev-id))
    (request handler :post "/days/2026-07-16/attendance"
             "clock-in-time=09%3A00&clock-out-time=18%3A00")
    (request handler :post "/days/2026-07-17/attendance"
             "clock-in-time=09%3A00&clock-out-time=10%3A00")
    (request handler :post "/days/2026-07-17/worklogs"
             "title=Needs%20category&start-time=09%3A00&end-time=10%3A00&category-id=")
    (request handler :post "/day-status-ranges"
             "start-date=2026-07-20&end-date=2026-07-22&status=holiday&redirect-to=%2F%3Fview%3Dmonth%26date%3D2026-07-15")

    (testing "month view shows done, missing, and holiday day states"
      (let [html (response-body (request handler "/?view=month&date=2026-07-15"))]
        (is (str/includes? html "class=\"days-calendar\""))
        (is (str/includes? html "data-calendar-view=\"month\""))
        (is (str/includes? html "class=\"calendar-day calendar-blank\""))
        (is (str/includes? html "class=\"calendar-day day-status-done\" data-date=\"2026-07-15\""))
        (is (str/includes? html "class=\"calendar-day day-status-missing\" data-date=\"2026-07-16\""))
        (is (str/includes? html "Uncategorized"))
        (is (str/includes? html "Uncategorized 1.00h"))
        (is (str/includes? html "class=\"calendar-day day-status-holiday\" data-date=\"2026-07-20\""))
        (is (str/includes? html "href=\"/days/2026-07-15\""))
        (is (not (str/includes? html "id=\"day-status-range-form\"")))))

    (testing "active month edit mode exposes click and range status actions"
      (let [html (response-body (request handler "/?view=month&date=2026-07-15&edit=1"))]
        (is (str/includes? html "data-calendar-edit=\"active\""))
        (is (str/includes? html "id=\"day-status-range-form\""))
        (is (str/includes? html "name=\"start-date\""))
        (is (str/includes? html "name=\"end-date\""))
        (is (str/includes? html "name=\"status\" value=\"workday\""))
        (is (str/includes? html "name=\"status\" value=\"holiday\""))))

    (testing "status range form can bulk-change days"
      (let [response (request handler :post "/day-status-ranges"
                              "start-date=2026-07-21&end-date=2026-07-22&status=workday&redirect-to=%2F%3Fview%3Dmonth%26date%3D2026-07-15%26edit%3D1")
            html (response-body (request handler "/?view=month&date=2026-07-15&edit=1"))]
        (is (= 303 (:status response)))
        (is (= "/?view=month&date=2026-07-15&edit=1" (get-in response [:headers "location"])))
        (is (str/includes? html "class=\"calendar-day day-status-workday\" data-date=\"2026-07-21\""))
        (is (str/includes? html "class=\"calendar-day day-status-workday\" data-date=\"2026-07-22\""))))

    (testing "week view shows all seven days and links to day input pages"
      (let [html (response-body (request handler "/?view=week&date=2026-07-15"))]
        (is (str/includes? html "class=\"week-calendar days-calendar\""))
        (is (str/includes? html "data-calendar-view=\"week\""))
        (is (str/includes? html "href=\"/?view=week&amp;date=2026-07-08\""))
        (is (str/includes? html "href=\"/?view=week&amp;date=2026-07-22\""))
        (is (str/includes? html "Prev week"))
        (is (str/includes? html "Next week"))
        (is (str/includes? html "data-date=\"2026-07-13\""))
        (is (str/includes? html "data-date=\"2026-07-19\""))
        (is (str/includes? html "href=\"/days/2026-07-15\""))
        (is (str/includes? html "Development"))
        (is (str/includes? html "8.00h"))))))

(deftest web-settings-page-test
  (let [{:keys [handler]} (empty-temp-system)]
    (testing "settings page owns break mode, daily break rules, holidays, and imports"
      (let [day-html (response-body (request handler "/days/2026-07-11"))
            settings-html (response-body (request handler "/settings"))]
        (is (str/includes? day-html "href=\"/settings\""))
        (is (not (str/includes? day-html "Daily break")))
        (is (not (str/includes? day-html "action=\"/break-rules\"")))
        (is (str/includes? settings-html "Settings"))
        (is (str/includes? settings-html "<a class=\"nav-button\" href=\"/\">Days</a>"))
        (is (str/includes? settings-html "action=\"/settings/break-mode\""))
        (is (str/includes? settings-html "Fixed inserts configured default breaks"))
        (is (str/includes? settings-html "name=\"break-mode\" value=\"fixed\">Fixed"))
        (is (str/includes? settings-html "class=\"toggle-option active\" type=\"submit\" name=\"break-mode\" value=\"fixed\""))
        (is (str/includes? settings-html "action=\"/settings/holiday-policy\""))
        (is (str/includes? settings-html "name=\"holiday-policy-mode\""))
        (is (str/includes? settings-html "Default holidays"))
        (is (str/includes? settings-html "action=\"/settings/calendar\""))
        (is (str/includes? settings-html "name=\"week-start-day\""))
        (is (str/includes? settings-html "name=\"fiscal-month-start-day\""))
        (is (str/includes? settings-html "Fiscal month start day"))
        (is (str/includes? settings-html "Daily break"))
        (is (str/includes? settings-html "action=\"/break-rules\""))
        (is (str/includes? settings-html "value=\"/settings\""))
        (is (str/includes? settings-html "Daily break rules"))
        (is (str/includes? settings-html "Add iCal source"))
        (is (str/includes? settings-html "name=\"uri\""))
        (is (not (str/includes? settings-html "href=\"/import-sources\"")))))

    (testing "break mode form switches the day break controls"
      (let [response (request handler :post "/settings/break-mode"
                              "break-mode=flexible&redirect-to=%2Fsettings")
            settings-html (response-body (request handler "/settings"))
            day-html (response-body (request handler "/days/2026-07-11"))]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
        (is (str/includes? settings-html "class=\"toggle-option active\" type=\"submit\" name=\"break-mode\" value=\"flexible\""))
        (is (str/includes? day-html "Break today"))
        (is (str/includes? day-html "One-off break"))))

    (testing "calendar settings form persists week and fiscal month preferences"
      (let [response (request handler :post "/settings/calendar"
                              "week-start-day=7&fiscal-month-start-day=21&redirect-to=%2Fsettings")
            settings-html (response-body (request handler "/settings"))]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
        (is (str/includes? settings-html "value=\"7\" selected"))
        (is (str/includes? settings-html "name=\"fiscal-month-start-day\" type=\"number\" min=\"1\" max=\"31\" value=\"21\""))))

    (testing "settings daily break form persists and returns to settings"
      (let [response (request handler :post "/break-rules"
                              "break-title=Lunch&start-time=12%3A00&end-time=13%3A00&redirect-to=%2Fsettings")
            settings-html (response-body (request handler "/settings"))
            rule-id (second (re-find #"/break-rules/(\d+)/update" settings-html))]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
        (is (some? rule-id))
        (is (str/includes? settings-html "Lunch"))
        (is (str/includes? settings-html "value=\"12:00\""))
        (is (str/includes? settings-html "value=\"13:00\""))
        (is (str/includes? settings-html (str "action=\"/break-rules/" rule-id "/update\"")))
        (is (str/includes? settings-html (str "action=\"/break-rules/" rule-id "/delete\"")))
        (let [update-response (request handler :post
                                       (str "/break-rules/" rule-id "/update")
                                       "break-title=Coffee&start-time=15%3A00&end-time=15%3A15&redirect-to=%2Fsettings")
              updated-html (response-body (request handler "/settings"))
              delete-response (request handler :post
                                       (str "/break-rules/" rule-id "/delete")
                                       "redirect-to=%2Fsettings")
              deleted-html (response-body (request handler "/settings"))]
          (is (= 303 (:status update-response)))
          (is (str/includes? updated-html "Coffee"))
          (is (str/includes? updated-html "value=\"15:00\""))
          (is (str/includes? updated-html "value=\"15:15\""))
          (is (= 303 (:status delete-response)))
          (is (not (str/includes? deleted-html "Coffee")))
          (is (str/includes? deleted-html "No daily break rules yet.")))))))

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
          (is (str/includes? html (str "action=\"/categories/" backend-id "/move\"")))
          (is (str/includes? html (str "action=\"/categories/" backend-id "/rename\"")))
          (is (str/includes? html (str "action=\"/categories/" backend-id "/delete\""))))

        (testing "parent categories cannot be assigned through form submission"
          (let [response (request handler :post "/days/2026-07-08/worklogs"
                                  (str "title=Parent&start-time=09%3A00&end-time=10%3A00&category-id="
                                       engineering-id))
                html (response-body (request handler (get-in response [:headers "location"])))]
            (is (= 303 (:status response)))
            (is (= "/days/2026-07-08?warning=non-assignable-category"
                   (get-in response [:headers "location"])))
            (is (str/includes? html "Non-assignable category"))))

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
            (is (< backend-pos frontend-pos))))

        (testing "rename form updates the category name"
          (let [response (request handler :post
                                  (str "/categories/" backend-id "/rename")
                                  "category-name=Platform&redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler "/days/2026-07-08"))]
            (is (= 303 (:status response)))
            (is (= "/days/2026-07-08" (get-in response [:headers "location"])))
            (is (str/includes? html "value=\"Platform\""))
            (is (str/includes? html (str "data-summary-category-id=\"" backend-id "\"")))))

        (testing "delete form hard-deletes an unreferenced category"
          (request handler :post "/categories"
                   "category-name=Temporary&redirect-to=%2Fdays%2F2026-07-08")
          (let [temporary-id (:id (db/find-category-by-name-and-parent ds "Temporary" nil))
                response (request handler :post
                                  (str "/categories/" temporary-id "/delete")
                                  "redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler "/days/2026-07-08"))]
            (is (= 303 (:status response)))
            (is (not (str/includes? html "value=\"Temporary\"")))))

        (testing "delete form rejects a parent with active children"
          (let [response (request handler :post
                                  (str "/categories/" engineering-id "/delete")
                                  "redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler (get-in response [:headers "location"])))]
            (is (= 303 (:status response)))
            (is (= "/days/2026-07-08?warning=has-active-children"
                   (get-in response [:headers "location"])))
            (is (str/includes? html "Has active children"))))

        (testing "delete form soft-deletes assigned categories but keeps summary names"
          (let [response (request handler :post
                                  (str "/categories/" frontend-id "/delete")
                                  "redirect-to=%2Fdays%2F2026-07-08")
                html (response-body (request handler "/days/2026-07-08"))]
            (is (= 303 (:status response)))
            (is (not (str/includes? html (str "action=\"/categories/" frontend-id "/rename\""))))
            (is (not (str/includes? html (str "<option value=\"" frontend-id "\">Frontend</option>"))))
            (is (str/includes? html (str "data-summary-category-id=\"" frontend-id "\"")))
            (is (str/includes? html "Frontend"))))))))

(deftest web-attendance-and-breaks-test
  (let [{:keys [handler ds]} (empty-temp-system)]
    (request handler :post "/categories"
             "category-name=Development&redirect-to=%2Fdays%2F2026-07-11")
    (let [_dev-id (:id (db/find-category-by-name-and-parent ds "Development" nil))]
      (testing "day page exposes attendance and break controls"
        (let [html (response-body (request handler "/days/2026-07-11"))]
          (is (str/includes? html "Attendance"))
          (is (str/includes? html "Clock in now"))
          (is (str/includes? html "Clock out now"))
          (is (str/includes? html "name=\"clock-in-time\""))
          (is (str/includes? html "name=\"clock-out-time\""))
          (is (str/includes? html "Fixed breaks"))
          (is (not (str/includes? html "Break today")))
          (is (not (str/includes? html "One-off break")))
          (is (not (str/includes? html "name=\"break-title\"")))
          (is (not (str/includes? html "Daily break")))
          (is (not (str/includes? html "action=\"/break-rules\"")))))

      (testing "manual attendance form updates the day summary"
        (let [response (request handler :post "/days/2026-07-11/attendance"
                                "clock-in-time=09%3A00&clock-out-time=18%3A00")
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (= "/days/2026-07-11" (get-in response [:headers "location"])))
          (is (str/includes? html "09:00-18:00"))
          (is (str/includes? html "class=\"attendance-band\""))
          (is (str/includes? html "data-attendance-start-minute=\"540\""))
          (is (str/includes? html "data-attendance-end-minute=\"1080\""))
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
          (is (str/includes? html "Fixed breaks"))
          (is (str/includes? html "1.00h"))
          (is (str/includes? html (str "action=\"/breaks/" break-id "/range\"")))
          (is (str/includes? html (str "action=\"/breaks/" break-id "/delete\"")))
          (is (not (str/includes? html (str "action=\"/breaks/" break-id "/convert\""))))
          (is (not (str/includes? html "Convert to work")))))

      (testing "break range form updates the rendered break"
        (let [break-id (:id (first (db/breaks-by-date ds "2026-07-11")))
              response (request handler :post
                                (str "/breaks/" break-id "/range")
                                "start-time=12%3A15&end-time=13%3A15")
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (str/includes? html "12:15-13:15"))))

      (testing "break delete form hides a materialized fixed break"
        (let [break-id (:id (first (db/breaks-by-date ds "2026-07-11")))
              response (request handler :post
                                (str "/breaks/" break-id "/delete")
                                "")
              html (response-body (request handler "/days/2026-07-11"))]
          (is (= 303 (:status response)))
          (is (= "/days/2026-07-11" (get-in response [:headers "location"])))
          (is (not (str/includes? html "12:15-13:15")))
          (is (not (str/includes? html "class=\"timeline-block break-block\"")))))

      (testing "flexible mode shows day-level break creation without materializing rules"
        (let [mode-response (request handler :post "/settings/break-mode"
                                     "break-mode=flexible&redirect-to=%2Fdays%2F2026-07-12")
              html (response-body (request handler "/days/2026-07-12"))]
          (is (= 303 (:status mode-response)))
          (is (= "/days/2026-07-12" (get-in mode-response [:headers "location"])))
          (is (str/includes? html "Break today"))
          (is (str/includes? html "One-off break"))
          (is (str/includes? html "name=\"break-title\""))
          (is (not (str/includes? html "class=\"timeline-block break-block\"")))))

      (testing "flexible one-off break is excluded from work effort"
        (let [response (request handler :post "/days/2026-07-12/breaks"
                                "break-title=Coffee&start-time=15%3A00&end-time=15%3A15")
              html (response-body (request handler "/days/2026-07-12"))]
          (is (= 303 (:status response)))
          (is (str/includes? html "Coffee"))
          (is (str/includes? html "class=\"timeline-block break-block\""))
          (is (not (str/includes? html "Convert to work"))))))

    (testing "expected form errors return to HTML with an inline warning"
      (let [response (request handler :post "/days/2026-07-12/breaks"
                              "break-title=Bad&start-time=15%3A00&end-time=15%3A00")
            html (response-body (request handler (get-in response [:headers "location"])))]
        (is (= 303 (:status response)))
        (is (= "/days/2026-07-12?warning=invalid-time-range"
               (get-in response [:headers "location"])))
        (is (str/includes? html "Invalid time range"))))))

(deftest web-import-source-test
  (let [{:keys [handler ds]} (empty-temp-system)
        dev (db/upsert-category! ds {:name "Development"})
        fixture-path (.getPath (io/file (io/resource "fixtures/ical/basic.ics")))]
    (testing "home page links to Settings instead of a standalone import page"
      (let [html (response-body (request handler "/?view=month&date=2026-07-06"))]
        (is (str/includes? html "href=\"/settings\""))
        (is (not (str/includes? html "href=\"/import-sources\"")))))

    (testing "legacy import source page redirects to settings"
      (let [response (request handler "/import-sources")]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))))

    (testing "settings page exposes import source add form"
      (let [html (response-body (request handler "/settings"))]
        (is (str/includes? html "Add iCal source"))
        (is (str/includes? html "name=\"name\""))
        (is (str/includes? html "name=\"uri\""))
        (is (str/includes? html "name=\"fetch-interval-minutes\""))))

    (testing "source form creates a source and returns to settings"
      (let [response (request handler :post "/import-sources"
                              (str "kind=ical&name=Fixture%20calendar&uri="
                                   fixture-path
                                   "&fetch-interval-minutes=15"))
            html (response-body (request handler "/settings"))]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
        (is (str/includes? html "Fixture calendar"))
        (is (str/includes? html "/fetch"))))

    (testing "manual fetch imports the source and day page shows the snapshot"
      (let [settings-html (response-body (request handler "/settings"))
            source-id (second (re-find #"/import-sources/(\d+)/fetch" settings-html))
            response (request handler :post (str "/import-sources/" source-id "/fetch") "")
            day-html (response-body (request handler "/days/2026-07-06"))]
        (is (= 303 (:status response)))
        (is (= "/settings" (get-in response [:headers "location"])))
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
