(ns worklog-timeblock.importer.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]
            [worklog-timeblock.importer.core :as importer])
  (:import [java.time Instant]))

(defn temp-system []
  (let [file (java.io.File/createTempFile "worklog-timeblock-importer" ".db")
        ds (db/datasource (.getAbsolutePath file))]
    (.deleteOnExit file)
    (migration/migrate! ds)
    ds))

(def basic-candidate
  {:source-id "ical:test"
   :external-id "evt-build"
   :title "Build"
   :starts-at "2026-07-06T09:00+09:00"
   :ends-at "2026-07-06T10:00+09:00"
   :timezone "Asia/Tokyo"
   :updated-at "2026-07-06T00:00:00Z"
   :sequence 1})

(deftest source-event-import-test
  (let [ds (temp-system)
        dev (db/upsert-category! ds {:id "dev" :name "Development"})]
    (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id (:id dev)})

    (testing "candidate import stores source events and creates initial snapshots"
      (let [result (importer/import-candidates! ds {:events [basic-candidate]})]
        (is (= {:fetched 1 :source-events-upserted 1 :work-logs-created 1}
               (select-keys result [:fetched :source-events-upserted :work-logs-created])))
        (is (= 1 (count (db/source-events-by-date ds "2026-07-06"))))
        (is (= 1 (count (db/work-logs-by-date ds "2026-07-06"))))
        (is (= (:id dev) (:category-id (first (db/work-logs-by-date ds "2026-07-06")))))))

    (testing "refetch updates source events without mutating user-owned work logs"
      (let [log-id (:id (first (db/work-logs-by-date ds "2026-07-06")))
            changed (assoc basic-candidate
                           :title "Build changed upstream"
                           :starts-at "2026-07-06T10:00+09:00"
                           :ends-at "2026-07-06T11:00+09:00"
                           :updated-at "2026-07-06T01:00:00Z"
                           :sequence 2)]
        (db/update-work-log! ds log-id {:state :excluded
                                        :category-id nil
                                        :start-minute 555
                                        :end-minute 585})
        (let [result (importer/import-candidates! ds {:events [changed]})
              source-event (first (db/source-events-by-date ds "2026-07-06"))
              work-log (db/get-work-log ds log-id)]
          (is (= 0 (:work-logs-created result)))
          (is (= "Build changed upstream" (:title source-event)))
          (is (= "2026-07-06T10:00+09:00" (:starts-at source-event)))
          (is (= "Build" (:title work-log)))
          (is (= {:state :excluded :start-minute 555 :end-minute 585 :category-id nil}
                 (select-keys work-log [:state :start-minute :end-minute :category-id]))))))

    (testing "duplicate source events within one import create one source event and one snapshot"
      (let [ds (temp-system)]
        (importer/import-candidates!
         ds
         {:events [(assoc basic-candidate :external-id "evt-dup" :title "Old")
                   (assoc basic-candidate
                          :external-id "evt-dup"
                          :title "New"
                          :updated-at "2026-07-06T02:00:00Z"
                          :sequence 2)]})
        (is (= ["New"] (map :title (db/source-events-by-date ds "2026-07-06"))))
        (is (= ["New"] (map :title (db/work-logs-by-date ds "2026-07-06"))))))))

(deftest due-import-source-test
  (let [ds (temp-system)
        fixture-path (.getPath (io/file (io/resource "fixtures/ical/basic.ics")))
        source (db/create-import-source! ds {:kind :ical
                                             :name "Fixture"
                                             :uri fixture-path
                                             :enabled? true
                                             :fetch-interval-minutes 15})
        first-now (Instant/parse "2026-07-06T00:00:00Z")
        second-now (Instant/parse "2026-07-06T00:10:00Z")
        third-now (Instant/parse "2026-07-06T00:16:00Z")]
    (testing "first due cycle fetches an enabled source"
      (let [runs (importer/run-due-fetches! ds first-now)]
        (is (= 1 (count runs)))
        (is (= 1 (get-in (first runs) [:result :fetched])))
        (is (= 1 (count (db/list-import-runs ds (:id source)))))))

    (testing "not-due cycle is skipped"
      (is (= [] (importer/run-due-fetches! ds second-now)))
      (is (= 1 (count (db/list-import-runs ds (:id source))))))

    (testing "second due cycle refetches without duplicating snapshots"
      (let [runs (importer/run-due-fetches! ds third-now)]
        (is (= 1 (count runs)))
        (is (= 1 (get-in (first runs) [:result :fetched])))
        (is (= 2 (count (db/list-import-runs ds (:id source)))))
        (is (= 1 (count (db/work-logs-by-date ds "2026-07-06"))))))))
