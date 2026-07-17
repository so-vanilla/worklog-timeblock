(ns worklog-timeblock.plugin.protocol)

(defprotocol EventSource
  (source-id [source])
  (candidate-events [source query]))
