(ns worklog-timeblock.plugin.ical-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [worklog-timeblock.plugin.ical :as ical]
            [worklog-timeblock.plugin.protocol :as plugin])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.time OffsetDateTime]))

(defn fixture-events [fixture]
  (plugin/candidate-events
   (ical/from-resource (str "fixture-" fixture)
                       (str "fixtures/ical/" fixture ".ics"))
   {:date "2026-07-06"}))

(defn first-fixture-event [fixture]
  (first (fixture-events fixture)))

(defn with-http-server [body f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        bytes (.getBytes body "UTF-8")]
    (.createContext
     server
     "/calendar.ics"
     (reify HttpHandler
       (handle [_ exchange]
         (.sendResponseHeaders exchange 200 (alength bytes))
         (with-open [out (.getResponseBody exchange)]
           (.write out bytes)))))
    (.start server)
    (try
      (f (format "http://127.0.0.1:%d/calendar.ics"
                 (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(deftest ical-fixture-test
  (testing "basic timed event"
    (let [event (first-fixture-event "basic")]
      (is (= "fixture-basic" (:source-id event)))
      (is (= "basic-1" (:external-id event)))
      (is (= "Build" (:title event)))
      (is (= "2026-07-06T09:00+09:00" (:starts-at event)))
      (is (= "2026-07-06T10:00+09:00" (:ends-at event)))
      (is (= "Asia/Tokyo" (:timezone event)))
      (is (= "2026-07-06T00:00:00Z" (:updated-at event)))
      (is (= 1 (:sequence event)))))

  (testing "UTC event remains parseable as an offset timestamp"
    (let [event (first-fixture-event "utc")]
      (is (= "utc-1" (:external-id event)))
      (is (= "UTC support" (:title event)))
      (is (= "2026-07-06T01:00Z" (:starts-at event)))
      (is (= "UTC" (:timezone event)))
      (is (= 2026 (.getYear (OffsetDateTime/parse (:starts-at event)))))))

  (testing "TZID is exposed for local calendar events"
    (let [event (first-fixture-event "tzid")]
      (is (= "tzid-1" (:external-id event)))
      (is (= "Tokyo planning" (:title event)))
      (is (= "Asia/Tokyo" (:timezone event)))
      (is (= "2026-07-06T10:30+09:00" (:starts-at event)))))

  (testing "duplicate UIDs keep the latest event by sequence and last-modified"
    (let [events (fixture-events "duplicate")
          event (first events)]
      (is (= 1 (count events)))
      (is (= "dup-1" (:external-id event)))
      (is (= "New duplicate" (:title event)))
      (is (= 2 (:sequence event)))
      (is (= "2026-07-06T10:00+09:00" (:starts-at event)))))

  (testing "cancelled events are skipped"
    (is (= [] (fixture-events "cancelled"))))

  (testing "date query filters out events from another day"
    (is (= [] (fixture-events "outside-range"))))

  (testing "folded summaries are unfolded by the iCal parser"
    (let [event (first-fixture-event "folded-summary")]
      (is (= "folded-1" (:external-id event)))
      (is (= "Folded planning session" (:title event)))))

  (testing "date-only events are skipped because they cannot become work blocks"
    (is (= [] (fixture-events "date-only"))))

  (testing "daily recurrence expands and EXDATE removes one occurrence"
    (let [source (ical/from-resource "fixture-recurrence"
                                     "fixtures/ical/recurrence-exdate.ics")]
      (is (= ["rec-1#2026-07-06T09:00+09:00"]
             (map :external-id (plugin/candidate-events source {:date "2026-07-06"}))))
      (is (= [] (plugin/candidate-events source {:date "2026-07-07"})))
      (is (= ["rec-1#2026-07-08T09:00+09:00"]
             (map :external-id (plugin/candidate-events source {:date "2026-07-08"}))))))

  (testing "day crossing event is parsed with the original end timestamp"
    (let [event (first-fixture-event "day-crossing")]
      (is (= "cross-1" (:external-id event)))
      (is (= "2026-07-06T23:30+09:00" (:starts-at event)))
      (is (= "2026-07-07T00:30+09:00" (:ends-at event)))))

  (testing "malformed ICS fails instead of silently importing bad events"
    (is (thrown? Exception
                 (fixture-events "malformed")))))

(deftest ical-file-and-url-source-test
  (testing "file source reads an ICS file path"
    (let [file (io/file (io/resource "fixtures/ical/basic.ics"))
          source (ical/from-file "file-source" (.getPath file))
          events (plugin/candidate-events source {:date "2026-07-06"})]
      (is (= "file-source" (plugin/source-id source)))
      (is (= ["basic-1"] (map :external-id events)))))

  (testing "url source reads an ICS URL"
    (let [body (slurp (io/resource "fixtures/ical/basic.ics"))]
      (with-http-server
        body
        (fn [url]
          (let [source (ical/from-url "url-source" url)
                events (plugin/candidate-events source {:date "2026-07-06"})]
            (is (= "url-source" (plugin/source-id source)))
            (is (= ["basic-1"] (map :external-id events)))))))))
