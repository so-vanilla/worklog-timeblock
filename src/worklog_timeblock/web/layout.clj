(ns worklog-timeblock.web.layout
  (:require [clojure.string :as str]))

(defn escape-html [value]
  (str/escape (str value)
              {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&#39;"}))

(def warning-labels
  {"category-required" "Category is required"
   "client-category-id-not-allowed" "Category ID is assigned automatically"
   "duplicate-category-name" "Duplicate category name"
   "has-active-children" "Has active children"
   "invalid-date" "Invalid date"
   "invalid-time-range" "Invalid time range"
   "invalid-minute" "Invalid time"
   "invalid-settings" "Invalid settings"
   "name-required" "Name is required"
   "non-assignable-category" "Non-assignable category"
   "title-required" "Title is required"
   "unknown-category" "Unknown category"
   "unknown-parent-category" "Unknown parent category"
   "overlaps-confirmed-work-log" "Overlaps confirmed work"})

(defn flash-warning [warning]
  (when (and warning (not (str/blank? (str warning))))
    (str "<div class=\"flash-warning\" role=\"alert\">"
         (escape-html (get warning-labels (str warning) (str warning)))
         "</div>")))
