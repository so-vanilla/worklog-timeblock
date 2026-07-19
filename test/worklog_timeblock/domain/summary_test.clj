(ns worklog-timeblock.domain.summary-test
  (:require [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.domain.summary :as summary]))

(def base-options
  {:rounding-minutes 15
   :small-gap-minutes 15
   :other-category-id "other"})

(def logs
  [{:id 1 :date "2026-07-06" :title "Daily Standup"
    :start-minute 540 :end-minute 555 :state :confirmed :category-id "meeting"}
   {:id 2 :date "2026-07-06" :title "Build"
    :start-minute 560 :end-minute 610 :state :confirmed :category-id "dev"}
   {:id 3 :date "2026-07-06" :title "Lunch"
    :start-minute 720 :end-minute 780 :state :excluded :category-id nil}
   {:id 4 :date "2026-07-06" :title "Unknown"
    :start-minute 790 :end-minute 820 :state :uncategorized :category-id nil}])

(deftest category-summary-test
  (testing "sums confirmed logs by category using rounded-down category minutes"
    (is (= {"meeting" 15
            "dev" 45
            "other" 40}
           (:category-minutes (summary/summarize-day base-options logs)))))

  (testing "residual minutes are assigned to other category"
    (is (= 5
           (get-in (summary/summarize-day base-options
                                          [(assoc (second logs)
                                                  :start-minute 560
                                                  :end-minute 610)])
                   [:other :rounding-residual-minutes]))))

  (testing "short gaps between included logs are assigned to other"
    (is (= 5
           (get-in (summary/summarize-day base-options (take 2 logs))
                   [:other :short-gap-minutes]))))

  (testing "excluded logs do not contribute to totals"
    (is (not (contains? (:category-minutes (summary/summarize-day base-options logs))
                        nil))))

  (testing "uncategorized logs are warning targets and included in other"
    (let [result (summary/summarize-day base-options logs)]
      (is (= [4] (map :id (:uncategorized result))))
      (is (= 1 (count (filter #(= :uncategorized (:type %)) (:warnings result)))))
      (is (= 30 (get-in result [:other :uncategorized-minutes])))))

  (testing "large gaps create warnings instead of automatic other time"
    (let [result (summary/summarize-day base-options
                                        [(first logs)
                                         (assoc (second logs) :start-minute 600)])]
      (is (= 1 (count (filter #(= :large-gap (:type %)) (:warnings result)))))
      (is (= 0 (get-in result [:other :short-gap-minutes])))))

  (testing "category totals include other residual and short gaps"
    (is (= 10 (get (:category-minutes (summary/summarize-day base-options (take 2 logs)))
                  "other"))))

  (testing "custom rounding quantum is respected"
    (is (= {"dev" 50}
           (:category-minutes
            (summary/summarize-day (assoc base-options :rounding-minutes 10)
                                   [(assoc (second logs) :start-minute 560 :end-minute 610)])))))

  (testing "invalid rounding quantum is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"rounding-minutes must be positive"
         (summary/summarize-day (assoc base-options :rounding-minutes 0) logs))))

  (testing "summary exposes decimal hours for manual entry"
    (is (= {"meeting" 0.25 "dev" 0.75 "other" 0.67}
           (:category-hours (summary/summarize-day base-options logs)))))

  (testing "unallocated category minutes are folded into other"
    (let [result (summary/summarize-day
                  (assoc base-options :unallocated-category-ids #{"unallocated"})
                  [{:id 5 :date "2026-07-06" :title "Fallback"
                   :start-minute 540 :end-minute 590
                   :state :confirmed :category-id "unallocated"}
                   {:id 6 :date "2026-07-06" :title "Unknown"
                    :start-minute 590 :end-minute 620
                    :state :uncategorized :category-id nil}])]
      (is (nil? (get (:category-minutes result) "unallocated")))
      (is (= 80 (get (:category-minutes result) "other")))
      (is (= 45 (get-in result [:other :unallocated-category-minutes])))
      (is (= 30 (get-in result [:other :uncategorized-minutes])))))

  (testing "non-assignable categories are excluded from totals and warned"
    (let [result (summary/summarize-day
                  (assoc base-options :assignable-category-ids #{"dev" "meeting" "other"})
                  [(assoc (first logs) :category-id "parent")
                   (second logs)])]
      (is (nil? (get (:category-minutes result) "parent")))
      (is (= 45 (get (:category-minutes result) "dev")))
      (is (= 1 (count (filter #(= :non-assignable-category (:type %))
                              (:warnings result)))))
      (is (= {:type :non-assignable-category
              :work-log-id 1
              :title "Daily Standup"
              :category-id "parent"}
             (first (filter #(= :non-assignable-category (:type %))
                            (:warnings result))))))))

(deftest attendance-and-break-summary-test
  (let [work-logs [{:id 10 :date "2026-07-06" :title "Morning"
                    :start-minute 540 :end-minute 720
                    :state :confirmed :category-id "dev"}
                   {:id 11 :date "2026-07-06" :title "Afternoon"
                    :start-minute 780 :end-minute 1020
                    :state :confirmed :category-id "dev"}]
        result (summary/summarize-day
                (assoc base-options
                       :attendance {:date "2026-07-06"
                                    :clock-in-minute 540
                                    :clock-out-minute 1080}
                       :breaks [{:id 1 :date "2026-07-06" :title "Lunch"
                                 :start-minute 720 :end-minute 780
                                 :active? true}])
                work-logs)]
    (testing "breaks are excluded from work effort and attendance exposes remaining time"
      (is (= {"dev" 420} (:category-minutes result)))
      (is (= 420 (get-in result [:attendance :confirmed-work-minutes])))
      (is (= 60 (get-in result [:attendance :break-minutes])))
      (is (= 540 (get-in result [:attendance :span-minutes])))
      (is (= 60 (get-in result [:attendance :unallocated-minutes]))))

    (testing "a break-covered gap does not become a large gap warning"
      (is (empty? (filter #(= :large-gap (:type %)) (:warnings result)))))))

(deftest source-diff-warning-test
  (testing "changed source event can be represented without mutating confirmed log"
    (is (= [{:type :source-updated :work-log-id 42 :external-id "evt-1"}]
           (summary/source-diff-warnings
            [{:id 42 :external-id "evt-1" :source-updated-at "2026-07-06T09:00:00+09:00"}]
            [{:external-id "evt-1" :updated-at "2026-07-06T10:00:00+09:00"}])))))
