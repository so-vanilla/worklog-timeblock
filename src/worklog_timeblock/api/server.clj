(ns worklog-timeblock.api.server
  (:gen-class)
  (:require [clojure.tools.cli :as cli]
            [org.httpkit.server :as http]
            [worklog-timeblock.api.routes :as routes]
            [worklog-timeblock.db.core :as db]
            [worklog-timeblock.db.migration :as migration]))

(def cli-options
  [["-h" "--host HOST" "Bind host" :default "127.0.0.1"]
   ["-p" "--port PORT" "Bind port" :default 3000 :parse-fn parse-long]
   ["-d" "--db PATH" "SQLite database path"
    :default (or (System/getenv "WORKLOG_TIMEBLOCK_DB") "./data/app.db")]])

(defn -main [& args]
  (let [{:keys [options errors]} (cli/parse-opts args cli-options)]
    (when (seq errors)
      (binding [*out* *err*]
        (doseq [error errors] (println error)))
      (System/exit 1))
    (let [ds (db/datasource (:db options))]
      (migration/migrate! ds)
      (println (format "worklog-timeblock backend listening on %s:%d"
                       (:host options)
                       (:port options)))
      (http/run-server (routes/app {:ds ds})
                       {:ip (:host options)
                        :port (:port options)})
      @(promise))))
