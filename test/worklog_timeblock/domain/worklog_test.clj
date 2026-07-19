(ns worklog-timeblock.domain.worklog-test
  (:require [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.domain.worklog :as worklog]))

(def mappings
  {"Daily Standup" {:state :confirmed :category-id "meeting"}
   "Lunch" {:state :excluded}
   "Build worklog-timeblock" {:state :confirmed :category-id "dev"}})

(def candidate
  {:source-id "local"
   :external-id "evt-build"
   :title "Build worklog-timeblock"
   :starts-at "2026-07-06T09:20:00+09:00"
   :ends-at "2026-07-06T10:10:00+09:00"
   :timezone "Asia/Tokyo"
   :updated-at "2026-07-06T10:12:00+09:00"})

(deftest candidate-to-worklog-test
  (testing "known title is auto-confirmed with category"
    (is (= {:date "2026-07-06"
            :title "Build worklog-timeblock"
            :start-minute 560
            :end-minute 610
            :state :confirmed
            :category-id "dev"
            :source-id "local"
            :external-id "evt-build"
            :source-updated-at "2026-07-06T10:12:00+09:00"}
           (worklog/candidate->worklog mappings candidate))))

  (testing "unknown title becomes uncategorized but remains worklog candidate"
    (is (= :uncategorized
           (:state (worklog/candidate->worklog mappings
                                             (assoc candidate :title "Unknown work")))))
    (is (nil? (:category-id (worklog/candidate->worklog mappings
                                                      (assoc candidate :title "Unknown work"))))))

  (testing "excluded mapping creates excluded snapshot"
    (is (= {:state :excluded :category-id nil}
           (select-keys
            (worklog/candidate->worklog mappings
                                      (assoc candidate
                                             :title "Lunch"
                                             :external-id "evt-lunch"))
            [:state :category-id]))))

  (testing "snapshot keeps source identity for later diff warnings"
    (let [log (worklog/candidate->worklog mappings candidate)]
      (is (= "local" (:source-id log)))
      (is (= "evt-build" (:external-id log)))))

  (testing "day crossing candidates are clipped to the start day"
    (is (= {:date "2026-07-06"
            :start-minute 1410
            :end-minute 1440}
           (select-keys
            (worklog/candidate->worklog
             mappings
             (assoc candidate
                    :external-id "evt-cross"
                    :starts-at "2026-07-06T23:30:00+09:00"
                    :ends-at "2026-07-07T00:30:00+09:00"))
            [:date :start-minute :end-minute]))))

  (testing "invalid date range is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Candidate event must end after it starts"
         (worklog/candidate->worklog mappings
                                   (assoc candidate
                                          :starts-at "2026-07-06T10:10:00+09:00"
                                          :ends-at "2026-07-06T09:20:00+09:00"))))))

(deftest editing-worklogs-test
  (let [log (worklog/candidate->worklog mappings candidate)]
    (testing "assigning a category confirms an uncategorized log"
      (is (= {:state :confirmed :category-id "support"}
             (select-keys
              (worklog/assign-category (assoc log :state :uncategorized :category-id nil)
                                       "support")
              [:state :category-id]))))

    (testing "excluding a log clears category and keeps time range"
      (is (= {:state :excluded
              :category-id nil
              :start-minute 560
              :end-minute 610}
             (select-keys (worklog/exclude log) [:state :category-id :start-minute :end-minute]))))

    (testing "changing range uses minutes and preserves title"
      (is (= {:title "Build worklog-timeblock" :start-minute 570 :end-minute 615}
             (select-keys (worklog/change-range log 570 615)
                          [:title :start-minute :end-minute]))))

    (testing "invalid edited range is rejected"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Work log must end after it starts"
           (worklog/change-range log 610 560)))))

  (testing "detects stale source snapshot by updated-at"
    (is (true? (worklog/stale-source?
                {:source-updated-at "2026-07-06T10:12:00+09:00"}
                {:updated-at "2026-07-06T10:20:00+09:00"}))))

  (testing "unchanged source is not stale"
    (is (false? (worklog/stale-source?
                 {:source-updated-at "2026-07-06T10:12:00+09:00"}
                 {:updated-at "2026-07-06T10:12:00+09:00"})))))
