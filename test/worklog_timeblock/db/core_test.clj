(ns worklog-timeblock.db.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
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
      (let [dev (db/upsert-category! ds {:id "dev" :name "Development"})
            other (db/upsert-category! ds {:id "other" :name "Other" :kind :other})]
        (is (pos-int? (:id dev)))
        (is (pos-int? (:id other)))
        (is (= #{"dev" "other"} (set (map :legacy-key (db/list-categories ds)))))
        (is (= (:id dev) (db/resolve-category-id ds "dev")))
        (is (= (:id other) (db/other-category-id ds)))
        (let [dev-again (db/upsert-category! ds {:id "dev" :name "Development Renamed"})]
          (is (= (:id dev) (:id dev-again)))
          (is (= (:position dev) (:position dev-again)))
          (is (= "Development Renamed" (:name dev-again))))))

    (testing "upserts and reads title mappings"
      (db/upsert-title-mapping! ds {:title "Build" :state :confirmed :category-id "dev"})
      (is (= {:title "Build"
              :state :confirmed
              :category-id (db/resolve-category-id ds "dev")}
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

(deftest category-hierarchy-test
  (let [path (temp-db)
        ds (db/datasource path)]
    (migration/migrate! ds)
    (let [ops (db/upsert-category! ds {:name "Operations"})
          dev (db/upsert-category! ds {:name "Development"})
          frontend (db/upsert-category! ds {:name "Frontend" :parent-id (:id dev)})
          backend (db/upsert-category! ds {:name "Backend" :parent-id (:id dev)})]
      (testing "creates internal ids and parent links"
        (is (every? pos-int? (map :id [ops dev frontend backend])))
        (is (= (:id dev) (:parent-id frontend)))
        (is (= (:id dev) (:parent-id backend)))
        (is (nil? (:legacy-key ops))))

      (testing "lists roots with their children in category order"
        (is (= ["Operations" "Development" "Frontend" "Backend"]
               (map :name (db/list-categories ds)))))

      (testing "moves root categories only within root order"
        (db/move-category! ds (:id dev) :up)
        (is (= ["Development" "Frontend" "Backend" "Operations"]
               (map :name (db/list-categories ds)))))

      (testing "moves child categories only within the parent group"
        (db/move-category! ds (:id backend) :up)
        (is (= ["Development" "Backend" "Frontend" "Operations"]
               (map :name (db/list-categories ds)))))

      (testing "category order persists across a new datasource"
        (let [reopened (db/datasource path)]
          (is (= ["Development" "Backend" "Frontend" "Operations"]
                 (map :name (db/list-categories reopened))))
          (is (= [0 0 1 1]
                 (map :position (db/list-categories reopened))))))

      (testing "upsert preserves omitted parent and position for existing categories"
        (let [before (db/get-category ds (:id backend))
              updated (db/upsert-category! ds {:id (:id backend) :name "Backend"})]
          (is (= (:parent-id before) (:parent-id updated)))
          (is (= (:position before) (:position updated)))
          (is (= ["Development" "Backend" "Frontend" "Operations"]
                 (map :name (db/list-categories ds))))))

      (testing "computes assignable leaves"
        (is (false? (db/category-assignable? ds (:id dev))))
        (is (true? (db/category-assignable? ds (:id backend))))
        (is (contains? (db/assignable-category-ids ds) (:id backend)))
        (is (not (contains? (db/assignable-category-ids ds) (:id dev)))))

      (testing "detects categories referenced by logs or mappings"
        (is (false? (db/category-has-assignments? ds (:id ops))))
        (db/insert-work-log! ds {:date "2026-07-07"
                                 :title "Ops"
                                 :start-minute 540
                                 :end-minute 600
                                 :state :confirmed
                                 :category-id (:id ops)})
        (is (true? (db/category-has-assignments? ds (:id ops)))))

      (testing "finds duplicate sibling names but allows same name under a different parent"
        (is (= (:id backend)
               (:id (db/find-category-by-name-and-parent ds "Backend" (:id dev)))))
        (is (nil? (db/find-category-by-name-and-parent ds "Backend" nil))))

      (testing "renames a category without changing its position"
        (let [before (db/get-category ds (:id backend))
              renamed (db/rename-category! ds (:id backend) "Platform")]
          (is (= "Platform" (:name renamed)))
          (is (= (:position before) (:position renamed)))
          (is (= (:parent-id before) (:parent-id renamed)))))

      (testing "hard-deletes an unreferenced leaf category"
        (let [temporary (db/upsert-category! ds {:name "Temporary"})
              result (db/delete-category! ds (:id temporary))]
          (is (= :hard (:mode result)))
          (is (nil? (db/get-category ds (:id temporary))))))

      (testing "rejects deleting a parent with active children"
        (let [result (db/delete-category! ds (:id dev))]
          (is (= :blocked (:mode result)))
          (is (= :has-active-children (:reason result)))
          (is (:active? (db/get-category ds (:id dev))))))

      (testing "soft-deletes an assigned category so historical names survive"
        (let [result (db/delete-category! ds (:id ops))
              deleted (db/get-category ds (:id ops))]
          (is (= :soft (:mode result)))
          (is (false? (:active? deleted)))
          (is (= "Operations" (:name deleted)))
          (is (false? (db/category-assignable? ds (:id ops))))
          (is (contains? (db/summarizable-category-ids ds) (:id ops))))))))

(deftest attendance-and-breaks-test
  (let [path (temp-db)
        ds (db/datasource path)]
    (migration/migrate! ds)

    (testing "upserts day-level attendance"
      (let [attendance (db/upsert-attendance! ds {:date "2026-07-06"
                                                  :clock-in-minute 540
                                                  :clock-out-minute 1080})]
        (is (= {:date "2026-07-06"
                :clock-in-minute 540
                :clock-out-minute 1080}
               (select-keys attendance [:date :clock-in-minute :clock-out-minute])))
        (is (= attendance (db/get-attendance ds "2026-07-06")))
        (is (nil? (db/get-attendance ds "2026-07-07")))))

    (testing "daily break rules materialize into editable day breaks"
      (let [rule (db/create-break-rule! ds {:title "Lunch"
                                            :start-minute 720
                                            :end-minute 780
                                            :enabled? true})
            first-run (db/materialize-breaks-for-date! ds "2026-07-06")
            second-run (db/materialize-breaks-for-date! ds "2026-07-06")
            breaks (db/breaks-by-date ds "2026-07-06")]
        (is (pos-int? (:id rule)))
        (is (= "Lunch" (:title rule)))
        (is (= 1 (count first-run)))
        (is (= 1 (count second-run)))
        (is (= 1 (count breaks)))
        (is (= (:id rule) (:break-rule-id (first breaks))))
        (is (= {:title "Lunch" :start-minute 720 :end-minute 780 :active? true}
               (select-keys (first breaks)
                            [:title :start-minute :end-minute :active?])))))

    (testing "day breaks can be updated independently of the rule"
      (let [break-id (:id (first (db/breaks-by-date ds "2026-07-06")))
            updated (db/update-break! ds break-id {:start-minute 735
                                                   :end-minute 795})]
        (is (= {:start-minute 735 :end-minute 795}
               (select-keys updated [:start-minute :end-minute])))
        (is (= {:start-minute 735 :end-minute 795}
               (select-keys (db/get-break ds break-id)
                            [:start-minute :end-minute])))))))

(deftest settings-and-day-status-test
  (let [path (temp-db)
        ds (db/datasource path)]
    (migration/migrate! ds)

    (testing "global settings have safe defaults and persist changes"
      (is (= :fixed (db/break-mode ds)))
      (is (= {:mode :complete-two-day
              :weekdays #{6 7}}
             (db/holiday-policy ds)))
      (is (= :flexible (db/set-break-mode! ds :flexible)))
      (is (= :flexible (db/break-mode (db/datasource path))))
      (is (= {:mode :manual
              :weekdays #{}}
             (db/set-holiday-policy! ds {:mode :manual :weekdays #{}})))
      (is (= {:mode :manual
              :weekdays #{}}
             (db/holiday-policy (db/datasource path)))))

    (testing "day status overrides support single days and ranges"
      (is (nil? (db/get-day-status-override ds "2026-07-20")))
      (is (= {:date "2026-07-20" :status :holiday}
             (db/upsert-day-status-override! ds "2026-07-20" :holiday)))
      (is (= {:date "2026-07-21" :status :workday}
             (db/upsert-day-status-override! ds "2026-07-21" :workday)))
      (is (= [{:date "2026-07-20" :status :holiday}
              {:date "2026-07-21" :status :workday}]
             (db/day-status-overrides-between ds "2026-07-19" "2026-07-22")))
      (db/delete-day-status-override! ds "2026-07-20")
      (is (= [{:date "2026-07-21" :status :workday}]
             (db/day-status-overrides-between ds "2026-07-19" "2026-07-22"))))))

(deftest legacy-category-schema-migration-test
  (let [path (temp-db)
        ds (db/datasource path)]
    (doseq [sql ["CREATE TABLE categories
                  (id TEXT PRIMARY KEY,
                   name TEXT NOT NULL,
                   kind TEXT NOT NULL DEFAULT 'normal')"
                 "CREATE TABLE title_mappings
                  (title TEXT PRIMARY KEY,
                   state TEXT NOT NULL,
                   category_id TEXT,
                   created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                   updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
                 "CREATE TABLE work_logs
                  (id INTEGER PRIMARY KEY AUTOINCREMENT,
                   date TEXT NOT NULL,
                   title TEXT NOT NULL,
                   start_minute INTEGER NOT NULL,
                   end_minute INTEGER NOT NULL,
                   state TEXT NOT NULL,
                   category_id TEXT,
                   source_id TEXT,
                   external_id TEXT,
                   source_updated_at TEXT,
                   created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                   updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
                 "CREATE INDEX idx_work_logs_date ON work_logs(date)"
                 "CREATE UNIQUE INDEX idx_work_logs_source_event
                  ON work_logs(source_id, external_id)"]]
      (jdbc/execute! ds [sql]))
    (jdbc/execute! ds ["INSERT INTO categories (id, name, kind)
                        VALUES ('dev', 'Development', 'normal'),
                               ('other', 'Other', 'other')"])
    (jdbc/execute! ds ["INSERT INTO title_mappings (title, state, category_id)
                        VALUES ('Build', 'confirmed', 'dev'),
                               ('Lunch', 'excluded', NULL)"])
    (jdbc/execute! ds ["INSERT INTO work_logs
                        (id, date, title, start_minute, end_minute, state,
                         category_id, source_id, external_id, source_updated_at)
                        VALUES
                        (10, '2026-07-06', 'Build', 540, 600, 'confirmed',
                         'dev', 'local', 'evt-build', '2026-07-06T09:00:00+09:00'),
                        (11, '2026-07-06', 'Lunch', 720, 780, 'excluded',
                         NULL, 'local', 'evt-lunch', NULL)"])

    (is (= :migrated (migration/migrate! ds)))
    (let [categories (db/list-categories ds)
          dev-id (db/resolve-category-id ds "dev")
          other-id (db/resolve-category-id ds "other")
          logs (db/work-logs-by-date ds "2026-07-06")]
      (is (every? pos-int? (map :id categories)))
      (is (= #{"dev" "other"} (set (map :legacy-key categories))))
      (is (= other-id (db/other-category-id ds)))
      (is (= {:title "Build" :state :confirmed :category-id dev-id}
             (db/get-title-mapping ds "Build")))
      (is (= {:title "Lunch" :state :excluded :category-id nil}
             (db/get-title-mapping ds "Lunch")))
      (is (= [10 11] (map :id logs)))
      (is (= dev-id (:category-id (first logs))))
      (is (nil? (:category-id (second logs))))
      (is (= "evt-build" (:external-id (first logs)))))))
