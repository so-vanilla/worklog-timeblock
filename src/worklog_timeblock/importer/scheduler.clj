(ns worklog-timeblock.importer.scheduler
  (:require [worklog-timeblock.importer.core :as importer])
  (:import [java.time Instant]))

(defn start!
  ([ds] (start! ds {}))
  ([ds {:keys [poll-ms] :or {poll-ms 60000}}]
   (let [stopped? (atom false)
         worker (future
                  (while (not @stopped?)
                    (try
                      (importer/run-due-fetches! ds (Instant/now))
                      (catch Exception e
                        (binding [*out* *err*]
                          (println "worklog-timeblock importer scheduler error:"
                                   (.getMessage e)))))
                    (Thread/sleep poll-ms)))]
     (fn stop! []
       (reset! stopped? true)
       (future-cancel worker)))))
