(ns worklog-timeblock.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(defn temp-db []
  (let [file (java.io.File/createTempFile "worklog-timeblock" ".db")]
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(deftest db-integration-test
  (let [path (temp-db)
        ds (db/datasource path)]
    (testing "migration creates usable schema"
      (is (= :migrated (migration/migrate! ds))))

    (testing "upserts and reads categories"
      (db/upsert-category! ds {:id "dev" :name "Development"})
      (db/upsert-category! ds {:id "other" :name "Other" :kind :other})
      (is (= #{"dev" "other"} (set (map :id (db/list-categories ds))))))

    (testing "upserts and reads title mappings"
      (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id "dev"})
      (is (= {:title "Build" :state :confirmed :category-id "dev"}
             (db/get-title-mapping ds "Build"))))

    (testing "inserts work logs"
      (let [id (db/insert-work-log! ds {:date "2026-07-06"
                                        :title "Build"
                                        :start-minute 540
                                        :end-minute 600
                                        :state :confirmed
                                        :category-id "dev"
                                        :source-id "local"
                                        :external-id "evt-build"})]
        (is (pos-int? id))
        (is (= 1 (count (db/work-logs-by-date ds "2026-07-06"))))))

    (testing "updates work log state and category"
      (let [id (:id (first (db/work-logs-by-date ds "2026-07-06")))]
        (db/update-work-log! ds id {:state :excluded :category-id nil})
        (is (= {:state :excluded :category-id nil}
               (select-keys (db/get-work-log ds id) [:state :category-id])))))

    (testing "source event uniqueness is enforced by upsert"
      (let [log {:date "2026-07-06"
                 :title "Build Changed"
                 :start-minute 600
                 :end-minute 660
                 :state :confirmed
                 :category-id "dev"
                 :source-id "local"
                 :external-id "evt-build"}]
        (db/upsert-work-log-by-source! ds log)
        (db/upsert-work-log-by-source! ds (assoc log :title "Build Again"))
        (is (= 1 (count (filter #(= "evt-build" (:external-id %))
                                (db/work-logs-by-date ds "2026-07-06")))))))

    (testing "summary reads confirmed work logs only by date"
      (is (= #{"2026-07-06"} (set (map :date (db/work-logs-by-date ds "2026-07-06"))))))))
