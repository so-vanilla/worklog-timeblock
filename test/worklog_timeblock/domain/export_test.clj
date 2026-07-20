(ns worklog-timeblock.domain.export-test
  (:require [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.domain.export :as export]))

(def categories
  [{:id 1 :name "Development" :active? true}
   {:id 2 :name "Engineering" :active? true}
   {:id 3 :name "Backend" :parent-id 2 :active? true}])

(def work-logs
  [{:date "2026-07-06"
    :title "Build"
    :start-minute 540
    :end-minute 600
    :state :confirmed
    :category-id 1}
   {:date "2026-07-06"
    :title "Backend plan"
    :start-minute 600
    :end-minute 660
    :state :confirmed
    :category-id 3}
   {:date "2026-07-06"
    :title "Loose task"
    :start-minute 660
    :end-minute 690
    :state :uncategorized}
   {:date "2026-07-06"
    :title "Lunch"
    :start-minute 720
    :end-minute 780
    :state :excluded
    :category-id 1}])

(deftest export-renderer-test
  (testing "org export is journal-like and includes confirmed work only"
    (is (= (str "* 2026-07-06\n"
                "** 09:00-10:00 Build\n"
                ":PROPERTIES:\n"
                ":CATEGORY: Development\n"
                ":HOURS: 1.00\n"
                ":END:\n"
                "** 10:00-11:00 Backend plan\n"
                ":PROPERTIES:\n"
                ":CATEGORY: Engineering / Backend\n"
                ":HOURS: 1.00\n"
                ":END:\n")
           (export/render :org {:date "2026-07-06"
                                :categories categories
                                :work-logs work-logs}))))

  (testing "markdown export renders a compact table and escapes table separators"
    (is (= (str "# 2026-07-06\n\n"
                "| Time | Title | Category | Hours |\n"
                "| --- | --- | --- | ---: |\n"
                "| 09:00-10:00 | Build | Development | 1.00 |\n"
                "| 10:00-11:00 | Backend plan | Engineering / Backend | 1.00 |\n")
           (export/render :markdown {:date "2026-07-06"
                                     :categories categories
                                     :work-logs work-logs}))))

  (testing "empty confirmed output is still a valid day document"
    (is (= "* 2026-07-07\n"
           (export/render :org {:date "2026-07-07"
                                :categories categories
                                :work-logs (remove #(= :confirmed (:state %)) work-logs)})))
    (is (= "# 2026-07-07\n\nNo confirmed work.\n"
           (export/render :markdown {:date "2026-07-07"
                                     :categories categories
                                     :work-logs (remove #(= :confirmed (:state %)) work-logs)})))))
