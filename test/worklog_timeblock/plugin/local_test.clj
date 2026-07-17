(ns worklog-timeblock.plugin.local-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.plugin.local :as local]
            [worklog-timeblock.plugin.protocol :as plugin]))

(deftest local-event-source-test
  (testing "loads local EDN candidate events"
    (let [source (local/from-resource "fixtures/local-events.edn")
          events (plugin/candidate-events source {:date "2026-07-06"})]
      (is (= 3 (count events)))
      (is (= "evt-standup" (:external-id (first events))))))

  (testing "filters events by date"
    (let [source (local/from-events [{:source-id "local"
                                      :external-id "evt-a"
                                      :title "A"
                                      :starts-at "2026-07-06T09:00:00+09:00"
                                      :ends-at "2026-07-06T09:30:00+09:00"
                                      :timezone "Asia/Tokyo"}
                                     {:source-id "local"
                                      :external-id "evt-b"
                                      :title "B"
                                      :starts-at "2026-07-07T09:00:00+09:00"
                                      :ends-at "2026-07-07T09:30:00+09:00"
                                      :timezone "Asia/Tokyo"}])]
      (is (= ["evt-a"]
             (map :external-id (plugin/candidate-events source {:date "2026-07-06"}))))))

  (testing "source ids are stable"
    (let [source (local/from-resource "fixtures/local-events.edn")]
      (is (= "local" (plugin/source-id source)))))

  (testing "missing resource fails clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Local event resource not found"
         (local/from-resource "fixtures/missing.edn"))))

  (testing "can load from file path for local test data"
    (let [file (io/file (io/resource "fixtures/local-events.edn"))
          source (local/from-file (.getPath file))]
      (is (= 3 (count (plugin/candidate-events source {:date "2026-07-06"})))))))
