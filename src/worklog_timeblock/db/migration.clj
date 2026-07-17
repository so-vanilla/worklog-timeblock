(ns worklog-timeblock.db.migration
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(defn- sql-statements [resource-name]
  (->> (slurp (io/resource resource-name))
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)
       (map #(str % ";"))))

(defn migrate! [ds]
  (jdbc/execute! ds ["PRAGMA foreign_keys = ON"])
  (doseq [sql (sql-statements "migrations/001_initial.up.sql")]
    (jdbc/execute! ds [sql]))
  :migrated)
