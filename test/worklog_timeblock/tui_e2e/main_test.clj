(ns worklog-timeblock.tui-e2e.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.tui.main :as tui]))

(def state
  {:date "2026-07-06"
   :work-logs [{:title "Build" :start-minute 540 :end-minute 590
                :state :confirmed :category-id "dev"}
               {:title "Unknown" :start-minute 600 :end-minute 630
                :state :uncategorized}]
   :summary {:category-hours {"dev" 0.75 "other" 0.08333333333333333}
             :warnings [{:type :uncategorized :work-log-id 2 :title "Unknown"}]}})

(deftest tui-smoke-test
  (testing "renders date and title"
    (let [screen (tui/render-dashboard state)]
      (is (str/includes? screen "2026-07-06"))
      (is (str/includes? screen "Build"))))

  (testing "renders category totals for manual entry"
    (let [screen (tui/render-dashboard state)]
      (is (str/includes? screen "dev"))
      (is (str/includes? screen "0.75h"))))

  (testing "renders warnings without hiding uncategorized work"
    (let [screen (tui/render-dashboard state)]
      (is (str/includes? screen "Warnings"))
      (is (str/includes? screen "Unknown"))))

  (testing "keeps narrow terminal output inside the requested width"
    (let [screen (tui/render-dashboard {:columns 30} state)]
      (is (str/includes? screen "worklog-timeblock  2026-07-06"))
      (is (str/includes? screen "09:00-09:50 confirmed Build"))
      (is (str/includes? screen "dev 0.75h"))
      (is (every? #(<= (count %) 30) (str/split-lines screen)))))

  (testing "renders category names for internal numeric ids"
    (let [screen (tui/render-dashboard
                  {:date "2026-07-07"
                   :categories [{:id 10 :name "Development"}
                                {:id 11 :name "Other"}]
                   :work-logs [{:title "Build" :start-minute 540 :end-minute 600
                                :state :confirmed :category-id 10}]
                   :summary {:category-hours {10 1.0}
                             :warnings []}})]
      (is (str/includes? screen "Development"))
      (is (not (str/includes? screen "10 1.00h"))))))
