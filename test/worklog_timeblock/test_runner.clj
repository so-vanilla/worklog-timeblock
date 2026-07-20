(ns worklog-timeblock.test-runner
  (:require [clojure.test :as test]))

(def default-namespaces
  ['worklog-timeblock.domain.worklog-test
   'worklog-timeblock.domain.summary-test
   'worklog-timeblock.domain.export-test
   'worklog-timeblock.plugin.local-test
   'worklog-timeblock.plugin.ical-test
   'worklog-timeblock.importer.core-test
   'worklog-timeblock.db.core-test
   'worklog-timeblock.api-e2e.routes-test
   'worklog-timeblock.web-e2e.pages-test
   'worklog-timeblock.tui-e2e.main-test])

(defn- require-namespace! [sym]
  (require sym)
  sym)

(defn -main [& args]
  (let [namespaces (if (seq args)
                     (map symbol args)
                     default-namespaces)
        loaded (map require-namespace! namespaces)
        result (apply test/run-tests loaded)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
