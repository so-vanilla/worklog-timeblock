(ns worklog-timeblock.web.pages
  (:require [clojure.string :as str])
  (:import [java.time LocalDate OffsetDateTime]))

(defn- escape-html [value]
  (str/escape (str value)
              {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&#39;"}))

(defn- page [title body]
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<title>" (escape-html title) "</title>"
       "<style>:root{color-scheme:light;--bg:#f6f7f9;--surface:#fff;--line:#d7dde5;--text:#17202a;--muted:#596579;--accent:#0f766e;--warn:#9a3412;}"
       "*{box-sizing:border-box;}body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,BlinkMacSystemFont,sans-serif;}"
       "a{color:var(--accent);}button,select,input{font:inherit;}button{border:1px solid var(--accent);background:var(--accent);color:#fff;border-radius:6px;padding:6px 10px;cursor:pointer;}"
       "select,input{border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--text);padding:5px 8px;min-height:34px;}"
       "table{border-collapse:collapse;width:100%;}td,th{border-bottom:1px solid var(--line);padding:7px 6px;text-align:left;}th{color:var(--muted);font-size:12px;text-transform:uppercase;}"
       ".home{max-width:980px;margin:40px auto;padding:0 24px;}.days-shell{max-width:1180px;margin:28px auto;padding:0 24px;display:grid;gap:16px;}.days-toolbar{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;}.view-tabs,.edit-tabs{display:flex;gap:8px;align-items:center;}.days-calendar{display:grid;gap:10px;}.month-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:6px;}.weekday-header{color:var(--muted);font-size:12px;text-transform:uppercase;padding:0 6px;}.calendar-day{position:relative;min-height:104px;border:1px solid var(--line);border-radius:8px;background:#fff;padding:8px;display:grid;align-content:start;gap:5px;}.calendar-blank{background:transparent;border-style:dashed;}.calendar-day.range-selected{outline:2px solid var(--accent);outline-offset:2px;}.calendar-day a,.week-day-card a{color:inherit;text-decoration:none;}.calendar-day button{all:unset;display:block;cursor:pointer;}.day-number{font-weight:700;font-variant-numeric:tabular-nums;}.day-status{display:inline-flex;width:max-content;border-radius:999px;padding:2px 7px;font-size:11px;font-weight:650;text-transform:uppercase;}.day-status-done{border-color:#86efac;background:#f0fdf4;}.day-status-done .day-status{background:#dcfce7;color:#166534;}.day-status-missing{border-color:#fdba74;background:#fff7ed;}.day-status-missing .day-status{background:#ffedd5;color:#9a3412;}.day-status-holiday{border-color:#c4b5fd;background:#f5f3ff;}.day-status-holiday .day-status{background:#ede9fe;color:#5b21b6;}.day-status-workday{border-color:#d7dde5;background:#fbfcfd;}.day-status-workday .day-status{background:#eef2f7;color:#475569;}.calendar-detail{color:var(--muted);font-size:12px;line-height:1.25;}.calendar-detail .uncategorized{color:var(--warn);font-weight:650;}.range-action-popover{position:fixed;z-index:30;display:grid;gap:8px;width:min(300px,calc(100vw - 24px));padding:12px;border:1px solid var(--line);border-radius:8px;background:#fff;box-shadow:0 12px 30px rgba(23,32,42,.18);}.range-action-popover[hidden]{display:none;}.range-action-popover form{display:grid;gap:8px;}.range-actions{display:flex;gap:8px;}.week-calendar{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:8px;}.week-day-card{min-height:180px;border:1px solid var(--line);border-radius:8px;background:#fff;padding:10px;display:grid;align-content:start;gap:8px;}.day-workspace{height:100vh;overflow:hidden;display:grid;grid-template-rows:auto auto minmax(0,1fr);}"
       ".workspace-header{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;padding:18px 28px;border-bottom:1px solid var(--line);background:var(--surface);}.workspace-title-area{display:grid;gap:6px;min-width:0;}.workspace-title-row{display:flex;flex-wrap:wrap;align-items:center;gap:12px;}"
       ".workspace-header h1{font-size:22px;line-height:1.2;margin:0;letter-spacing:0;}.workspace-meta{color:var(--muted);font-size:13px;}"
       ".header-actions{display:grid;gap:8px;justify-items:end;}.page-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:flex-end;}.day-navigation{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:flex-end;}.nav-button{display:inline-flex;align-items:center;min-height:34px;padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--text);text-decoration:none;}.day-navigation form{display:flex;gap:6px;align-items:center;}.day-navigation input{width:142px;}"
       ".workspace-grid{display:grid;grid-template-columns:minmax(280px,.95fr) minmax(340px,1.25fr) minmax(300px,.85fr);min-height:0;}"
       ".timeline-pane,.entry-pane,.summary-pane{min-width:0;height:100%;overflow:auto;padding:18px 22px 28px;}.entry-pane,.summary-pane{border-left:1px solid var(--line);background:var(--surface);}"
       ".input-panel{display:grid;gap:10px;margin:0 0 18px;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--surface);}.input-grid{display:grid;grid-template-columns:repeat(2,minmax(120px,1fr));gap:8px;}.input-grid .wide{grid-column:1/-1;}.inline-form{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0;}.inline-form input{min-width:160px;}"
       ".title-suggestion-wrap{position:relative;}.title-suggestion-wrap input{width:100%;}.title-suggestion-list{position:absolute;top:calc(100% + 4px);left:0;right:0;z-index:26;display:grid;gap:2px;max-height:220px;overflow:auto;padding:6px;border:1px solid var(--line);border-radius:7px;background:#fff;box-shadow:0 12px 30px rgba(23,32,42,.16);}.title-suggestion-list[hidden]{display:none;}.title-suggestion-option{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;align-items:center;width:100%;border:0;border-radius:5px;background:#fff;color:var(--text);padding:7px 8px;text-align:left;}.title-suggestion-option.active{background:#e6f5f3;}.title-suggestion-title{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:650;}.title-suggestion-category{color:var(--muted);font-size:12px;white-space:nowrap;}"
       ".draft-summary-preview{min-height:24px;color:var(--muted);font-size:13px;font-variant-numeric:tabular-nums;}"
       ".day-timeline{display:grid;grid-template-columns:48px minmax(160px,1fr);gap:10px;height:calc(100vh - 142px);min-height:640px;max-height:1080px;}.timeline-hours{position:relative;color:var(--muted);font-size:11px;font-variant-numeric:tabular-nums;}.timeline-hour-label{position:absolute;right:0;transform:translateY(-50%);}"
       ".timeline-track{position:relative;min-height:100%;border:1px solid var(--line);border-radius:8px;background:repeating-linear-gradient(to bottom,#fff 0,#fff calc(100% / 24 - 1px),#e8edf3 calc(100% / 24 - 1px),#e8edf3 calc(100% / 24));touch-action:none;overflow:hidden;}"
       ".timeline-selection{position:absolute;z-index:14;left:8px;right:8px;border:2px solid #0f766e;background:rgba(15,118,110,.14);border-radius:6px;pointer-events:none;box-shadow:0 0 0 2px rgba(15,118,110,.08);}"
       ".timeline-warning-bubble{position:absolute;left:18px;right:18px;z-index:18;padding:5px 7px;border:1px solid rgba(154,52,18,.5);border-radius:6px;background:#fff7ed;color:var(--warn);font-size:12px;line-height:1.2;pointer-events:none;box-shadow:0 6px 16px rgba(23,32,42,.16);}.timeline-warning-bubble[hidden]{display:none;}"
       ".attendance-band{position:absolute;z-index:1;left:2px;right:2px;border-left:4px solid rgba(15,118,110,.55);background:rgba(15,118,110,.06);color:#0f766e;font-size:11px;line-height:1.1;padding:4px 6px;pointer-events:none;}.attendance-marker{position:absolute;z-index:2;left:0;right:0;border-top:2px solid rgba(15,118,110,.72);color:#0f766e;font-size:11px;line-height:1;pointer-events:none;}.attendance-marker span{position:absolute;right:6px;top:-7px;background:#fff;padding:1px 4px;border-radius:4px;}"
       ".timeline-selection[hidden]{display:none;}.timeline-block{position:absolute;z-index:6;border-radius:6px;padding:5px 7px;overflow:hidden;font-size:12px;line-height:1.2;border:1px solid transparent;}"
       ".confirmed-block{z-index:9;left:8px;width:66%;background:#dbeafe;border-color:#93c5fd;color:#172554;cursor:move;}.confirmed-block.selected{outline:2px solid #1d4ed8;outline-offset:2px;}.uncategorized-block{background:#fef3c7;border-color:#f59e0b;color:#713f12;}.break-block{left:6%;width:88%;background:rgba(202,138,4,.14);border-color:rgba(202,138,4,.45);color:#713f12;}.imported-block{left:12%;width:78%;background:rgba(15,118,110,.12);border-color:rgba(15,118,110,.34);color:#064e3b;cursor:context-menu;}.overlap-block{left:76%;width:20%;padding-inline:4px;background:rgba(154,52,18,.12);border-color:rgba(154,52,18,.45);}"
       ".block-time{font-variant-numeric:tabular-nums;font-weight:650;}.block-title{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}.candidate-menu{position:fixed;z-index:20;width:min(320px,calc(100vw - 24px));padding:12px;border:1px solid var(--line);border-radius:8px;background:#fff;box-shadow:0 12px 30px rgba(23,32,42,.18);}.candidate-menu[hidden]{display:none;}.candidate-menu form{display:grid;gap:8px;margin-top:8px;}"
       ".attention-queue{display:grid;gap:8px;margin-bottom:18px;}.candidate-card{border:1px solid var(--line);border-radius:8px;padding:10px;background:#fbfcfd;}.candidate-card.covered{border-color:rgba(154,52,18,.45);}.candidate-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;}.candidate-actions form{display:flex;gap:6px;align-items:center;}.candidate-badge{font-size:11px;color:var(--warn);text-transform:uppercase;}"
       ".pane-title{font-size:15px;margin:0 0 14px;}.sub-title{font-size:13px;margin:14px 0 8px;}.work-log-list{display:grid;gap:10px;}.work-log-row{display:grid;gap:10px;border:1px solid var(--line);border-radius:8px;background:var(--surface);padding:10px;overflow:hidden;}.work-log-row.selected{border-color:#1d4ed8;box-shadow:0 0 0 2px rgba(29,78,216,.14);}.work-log-main{display:grid;grid-template-columns:112px minmax(0,1fr) 92px;gap:10px;align-items:center;}.work-log-actions{display:grid;grid-template-columns:minmax(0,1fr);gap:8px;align-items:start;}.work-log-range-line{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;align-items:center;}"
       ".time-range{font-variant-numeric:tabular-nums;font-weight:650;}.title{min-width:0;overflow-wrap:anywhere;}.state{color:var(--muted);}.state-excluded{opacity:.66;}.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;min-width:0;}.controls select{min-width:0;max-width:100%;}.category-form select{flex:1 1 180px;}.range-form{display:flex;flex-wrap:wrap;gap:6px;align-items:center;min-width:0;}.range-form input{width:92px;}.exclude-form{justify-self:end;}.exclude-form button,.delete-form button{border-color:#747b86;background:#747b86;}"
       ".category-create-form{display:grid;gap:8px;margin:0 0 14px;}.category-list{display:grid;gap:4px;list-style:none;margin:0;padding:0;}.category-row{display:grid;grid-template-columns:minmax(0,1fr) 48px auto;gap:8px;align-items:center;border:1px solid var(--line);border-left:5px solid var(--group-color,var(--line));border-radius:6px;padding:7px 8px;background:#fff;}.category-child{margin-left:20px;}.category-rename-form{display:flex;gap:6px;min-width:0;}.category-rename-form input{min-width:0;width:100%;}.category-row .controls{justify-content:flex-end;}.category-row button{padding:4px 8px;}.summary-row{border-left:5px solid var(--group-color,var(--line));}.summary-child td:first-child{padding-left:22px;}.summary-parent td:first-child{font-weight:700;}"
       ".metric-list{display:grid;grid-template-columns:1fr auto;gap:6px 10px;margin:0 0 14px;}.metric-list dt{color:var(--muted);}.metric-list dd{margin:0;font-variant-numeric:tabular-nums;font-weight:650;}.day-breaks{border-top:1px solid var(--line);padding-top:2px;}.break-list{display:grid;gap:8px;margin:0 0 12px;}.break-row{display:grid;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px;background:#fff7ed;}.break-actions{display:grid;gap:8px;}.one-off-break-form{display:grid;gap:8px;margin-top:8px;}"
       ".settings-grid{display:grid;grid-template-columns:1fr;gap:14px;}.settings-form{display:grid;gap:10px;}.settings-nav{display:flex;gap:8px;align-items:center;margin:0 0 12px;}.field{display:grid;gap:4px;}.field-label{font-weight:650;}.field-help{color:var(--muted);font-size:12px;line-height:1.35;}.segmented-toggle{display:inline-flex;width:max-content;border:1px solid var(--line);border-radius:7px;overflow:hidden;background:#fff;}.toggle-option{display:inline-flex;align-items:center;min-height:34px;padding:6px 12px;border:0;border-right:1px solid var(--line);border-radius:0;background:#fff;color:var(--text);text-decoration:none;}.toggle-option:last-child{border-right:0;}.toggle-option.active{background:#16a34a;color:#fff;}.break-rule-cards{display:grid;gap:10px;}.break-rule-card{display:grid;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px;background:#fbfcfd;}.break-rule-edit-form{display:grid;grid-template-columns:minmax(140px,1fr) 128px 128px auto;gap:8px;align-items:end;}.weekday-options{display:flex;flex-wrap:wrap;gap:8px 12px;}.weekday-options label{display:flex;gap:5px;align-items:center;color:var(--muted);}.flash-warning{border:1px solid rgba(154,52,18,.42);border-radius:8px;background:#fff7ed;color:var(--warn);padding:10px 12px;font-weight:650;}.day-workspace>.flash-warning{margin:12px 28px 0;}.warn{color:var(--warn);font-weight:650;}.warnings{padding-left:18px;}@media (max-width:980px){.workspace-header{align-items:flex-start;flex-direction:column;}.header-actions{justify-items:start;}.day-navigation,.page-actions{justify-content:flex-start;}.workspace-grid,.week-calendar{grid-template-columns:1fr;}.month-grid{grid-template-columns:repeat(7,minmax(86px,1fr));overflow:auto;}.entry-pane,.summary-pane{border-left:0;border-top:1px solid var(--line);}.day-timeline{height:720px;}.work-log-main,.work-log-actions,.work-log-range-line,.input-grid,.category-row,.break-rule-edit-form{grid-template-columns:1fr;}.range-form input{width:100%;}.controls,.range-form,.inline-form{align-items:stretch;}.controls form,.range-form,.inline-form input,.inline-form button,.exclude-form{width:100%;}.controls select,.controls button,.range-form input,.range-form button{width:100%;}}"
       "</style>"
       "</head><body>" body "</body></html>"))

(defn home-page [dates]
  (page "worklog-timeblock"
        (str "<main class=\"home\"><h1>worklog-timeblock</h1>"
             "<form class=\"inline-form\" method=\"post\" action=\"/days\">"
             "<input type=\"date\" name=\"date\" value=\"2026-07-06\">"
             "<button type=\"submit\">Open day</button></form>"
             "<p><a href=\"/import-sources\">Import sources</a> | <a href=\"/settings\">Settings</a></p>"
             (if (seq dates)
               (str "<ul>"
                    (apply str
                           (map (fn [date]
                                  (str "<li><a href=\"/days/" (escape-html date) "\">"
                                       (escape-html date)
                                       "</a></li>"))
                                dates))
                    "</ul>")
               "<p>No work logs yet.</p>")
             "</main>")))

(defn- categories-by-id [categories]
  (into {} (map (juxt :id identity)) categories))

(defn- category-name [categories category-id]
  (cond
    (= "uncategorized" (str category-id)) "Uncategorized"
    :else (or (get-in categories [category-id :name]) category-id "(uncategorized)")))

(defn- active-categories [categories]
  (filter :active? categories))

(defn- categories-with-children [categories]
  (set (keep :parent-id (active-categories categories))))

(defn- assignable-category? [categories-with-children category]
  (and (:active? category)
       (not (contains? categories-with-children (:id category)))))

(defn- children-by-parent [categories]
  (group-by :parent-id (filter :parent-id categories)))

(defn- root-categories [categories]
  (filter #(and (:active? %) (nil? (:parent-id %))) categories))

(defn- root-id-for [category]
  (or (:parent-id category) (:id category)))

(defn- group-color [root-id]
  (let [seed (Math/abs (long (hash (or root-id 0))))]
    (format "hsl(%d, 52%%, 42%%)" (mod (* 47 seed) 360))))

(defn- group-style [category]
  (str "--group-color:" (group-color (root-id-for category)) ";"))

(defn- day-link [date offset]
  (str "/days/" (.plusDays (LocalDate/parse date) offset)))

(defn- today-string []
  (str (LocalDate/now)))

(defn- day-navigation [date]
  (str "<div class=\"day-navigation\">"
       "<a class=\"nav-button\" href=\"" (escape-html (day-link date -1)) "\">Prev</a>"
       "<form method=\"post\" action=\"/days\">"
       "<input type=\"date\" name=\"date\" value=\"" (escape-html date) "\">"
       "<button type=\"submit\">GOTO</button></form>"
       "<a class=\"nav-button\" href=\"/days/" (escape-html (today-string)) "\">TODAY</a>"
       "<a class=\"nav-button\" href=\"" (escape-html (day-link date 1)) "\">Next</a>"
       "</div>"))

(defn- time-string [minute]
  (format "%02d:%02d" (quot minute 60) (mod minute 60)))

(defn- optional-time-string [minute]
  (if (integer? minute)
    (time-string minute)
    ""))

(defn- hours-string [minutes]
  (format "%.2fh" (/ (double (or minutes 0)) 60.0)))

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

(defn- flash-warning [warning]
  (when (and warning (not (str/blank? (str warning))))
    (str "<div class=\"flash-warning\" role=\"alert\">"
         (escape-html (get warning-labels (str warning) (str warning)))
         "</div>")))

(defn- offset-minute [value]
  (let [date-time (OffsetDateTime/parse value)]
    (+ (* (.getHour date-time) 60) (.getMinute date-time))))

(defn- source-end-minute [event]
  (let [start (OffsetDateTime/parse (:starts-at event))
        end (OffsetDateTime/parse (:ends-at event))]
    (if (= (.toLocalDate start) (.toLocalDate end))
      (offset-minute (:ends-at event))
      1440)))

(defn- percent [minute]
  (format "%.4f%%" (* 100.0 (/ minute 1440.0))))

(defn- block-style [start-minute end-minute]
  (str "top:" (percent start-minute) ";height:"
       (percent (max 15 (- end-minute start-minute))) ";"))

(defn- source-event-minutes [event]
  {:start-minute (offset-minute (:starts-at event))
   :end-minute (source-end-minute event)})

(defn- overlaps-range? [a-start a-end b-start b-end]
  (and (< a-start b-end)
       (< b-start a-end)))

(defn- overlaps-confirmed? [work-logs source-event]
  (let [{:keys [start-minute end-minute]} (source-event-minutes source-event)]
    (boolean
     (some #(and (= :confirmed (:state %))
                 (overlaps-range? start-minute end-minute
                                  (:start-minute %) (:end-minute %)))
           work-logs))))

(defn- timeline-hour-labels []
  (apply str
         (map (fn [hour]
                (str "<div class=\"timeline-hour-label\" style=\"top:"
                     (percent (* hour 60)) "\">"
                     (format "%02d:00" hour)
                     "</div>"))
              (range 0 25 2))))

(defn- timeline-confirmed-block [log]
  (str "<div class=\"timeline-block confirmed-block"
       (when (= :uncategorized (:state log)) " uncategorized-block")
       "\" style=\""
       (escape-html (block-style (:start-minute log) (:end-minute log)))
       "\" data-worklog-id=\"" (escape-html (:id log))
       "\" data-start-minute=\"" (escape-html (:start-minute log))
       "\" data-end-minute=\"" (escape-html (:end-minute log)) "\">"
       "<div class=\"block-time\">" (escape-html (str (time-string (:start-minute log))
                                                      "-"
                                                      (time-string (:end-minute log))))
       "</div><div class=\"block-title\">" (escape-html (:title log))
       "</div></div>"))

(defn- timeline-break-block [break]
  (str "<div class=\"timeline-block break-block\" style=\""
       (escape-html (block-style (:start-minute break) (:end-minute break)))
       "\" data-break-id=\"" (escape-html (:id break)) "\">"
       "<div class=\"block-time\">" (escape-html (str (time-string (:start-minute break))
                                                      "-"
                                                      (time-string (:end-minute break))))
       "</div><div class=\"block-title\">" (escape-html (:title break))
       "</div></div>"))

(defn- timeline-attendance-marker [label minute]
  (when (integer? minute)
    (str "<div class=\"attendance-marker\" style=\"top:" (percent minute)
         "\" data-attendance-marker=\"" (escape-html label)
         "\" data-minute=\"" (escape-html minute) "\"><span>"
         (escape-html (str label " " (time-string minute)))
         "</span></div>")))

(defn- timeline-attendance [attendance]
  (let [clock-in (:clock-in-minute attendance)
        clock-out (:clock-out-minute attendance)]
    (str
     (when (and (integer? clock-in) (integer? clock-out))
       (str "<div class=\"attendance-band\" style=\""
            (escape-html (block-style clock-in clock-out))
            "\" data-attendance-start-minute=\"" (escape-html clock-in)
            "\" data-attendance-end-minute=\"" (escape-html clock-out)
            "\">Clock " (escape-html (str (time-string clock-in)
                                          "-"
                                          (time-string clock-out)))
            "</div>"))
     (timeline-attendance-marker "In" clock-in)
     (timeline-attendance-marker "Out" clock-out))))

(defn- timeline-imported-block [work-logs event]
  (let [{:keys [start-minute end-minute]} (source-event-minutes event)
        overlap? (overlaps-confirmed? work-logs event)]
    (str "<div class=\"timeline-block imported-block"
         (when overlap? " overlap-block")
         "\" style=\"" (escape-html (block-style start-minute end-minute))
         "\" data-source-event-id=\"" (escape-html (:id event))
         "\" data-external-id=\"" (escape-html (:external-id event))
         "\" data-title=\"" (escape-html (:title event)) "\">"
         "<div class=\"block-time\">" (escape-html (str (time-string start-minute)
                                                        "-"
                                                        (time-string end-minute)))
         "</div><div class=\"block-title\">" (escape-html (:title event))
         "</div></div>")))

(defn- timeline-work-block? [log]
  (or (= :confirmed (:state log))
      (and (= :uncategorized (:state log))
           (nil? (:source-id log))
           (nil? (:external-id log)))))

(defn- category-option [selected-id category]
  (let [id (:id category)]
    (str "<option value=\"" (escape-html id) "\""
         (when (= id selected-id) " selected")
         ">" (escape-html (:name category)) "</option>")))

(defn- category-select [categories selected-id]
  (let [active (vec (active-categories categories))
        children (children-by-parent active)
        with-children (categories-with-children active)
        roots (root-categories active)]
    (str "<select name=\"category-id\">"
         "<option value=\"\">Uncategorized</option>"
         (apply str
                (map (fn [root]
                       (let [root-children (filter #(assignable-category? with-children %)
                                                   (get children (:id root)))]
                         (cond
                           (seq root-children)
                           (str "<optgroup label=\"" (escape-html (:name root)) "\">"
                                (apply str (map #(category-option selected-id %)
                                                root-children))
                                "</optgroup>")

                           (assignable-category? with-children root)
                           (category-option selected-id root)

                           :else "")))
                     roots))
         "</select>")))

(defn- source-confirm-form [categories source-event]
  (str "<form method=\"post\" action=\"/source-events/" (escape-html (:id source-event)) "/confirm\">"
       (category-select categories nil)
       "<button type=\"submit\">Confirm</button></form>"))

(defn- source-exclude-form [source-event]
  (str "<form method=\"post\" action=\"/source-events/" (escape-html (:id source-event)) "/exclude\">"
       "<button type=\"submit\">Exclude</button></form>"))

(defn- candidate-card [categories work-logs source-event]
  (let [{:keys [start-minute end-minute]} (source-event-minutes source-event)
        covered? (overlaps-confirmed? work-logs source-event)]
    (str "<article class=\"candidate-card"
         (when covered? " covered")
         "\" data-external-id=\"" (escape-html (:external-id source-event)) "\">"
         "<div class=\"time-range\">" (escape-html (str (time-string start-minute)
                                                        "-"
                                                        (time-string end-minute)))
         "</div><div class=\"title\">" (escape-html (:title source-event)) "</div>"
         (when covered? "<div class=\"candidate-badge\">covered</div>")
         "<div class=\"candidate-actions\">"
         (source-confirm-form categories source-event)
         (source-exclude-form source-event)
         "</div></article>")))

(defn- attention-queue [categories work-logs source-events]
  (str "<section class=\"attention-queue\"><h2 class=\"pane-title\">Imported candidates</h2>"
       (if (seq source-events)
         (apply str (map #(candidate-card categories work-logs %) source-events))
         "<p class=\"state\">No imported candidates.</p>")
       "</section>"))

(defn- candidate-menu [categories]
  (str "<div id=\"candidate-menu\" class=\"candidate-menu\" hidden>"
       "<div class=\"title\" data-menu-title></div>"
       "<form data-confirm-form method=\"post\" action=\"\">"
       (category-select categories nil)
       "<button type=\"submit\">Confirm</button></form>"
       "<form data-exclude-form method=\"post\" action=\"\">"
       "<button type=\"submit\">Exclude</button></form>"
       "</div>"))

(defn- new-work-log-form [date categories]
  (str "<form id=\"new-work-log-form\" class=\"input-panel\" method=\"post\" action=\"/days/"
       (escape-html date) "/worklogs\">"
       "<h2 class=\"pane-title\">Add work log</h2>"
       "<div class=\"input-grid\">"
       "<div class=\"title-suggestion-wrap wide\">"
       "<input name=\"title\" placeholder=\"Title\" autocomplete=\"off\""
       " aria-autocomplete=\"list\" aria-controls=\"title-suggestion-list\""
       " aria-expanded=\"false\">"
       "<div id=\"title-suggestion-list\" class=\"title-suggestion-list\""
       " role=\"listbox\" hidden></div></div>"
       "<input type=\"time\" name=\"start-time\" value=\"09:00\">"
       "<input type=\"time\" name=\"end-time\" value=\"10:00\">"
       (category-select categories nil)
       "<button type=\"submit\">Add</button>"
       "</div><div id=\"draft-summary-preview\" class=\"draft-summary-preview\">Draft 1.00h</div></form>"))

(defn- parent-category-option [category]
  (str "<option value=\"" (escape-html (:id category)) "\">"
       (escape-html (:name category))
       "</option>"))

(defn- parent-category-select [categories]
  (str "<select name=\"parent-id\">"
       "<option value=\"\">No parent</option>"
       (apply str
              (map parent-category-option
                   (filter #(and (:active? %) (nil? (:parent-id %))) categories)))
       "</select>"))

(defn- new-category-form [date categories]
  (str "<form class=\"category-create-form\" method=\"post\" action=\"/categories\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/days/" (escape-html date) "\">"
       "<div class=\"input-grid\">"
       "<input name=\"category-name\" placeholder=\"Category name\">"
       (parent-category-select categories)
       "<button type=\"submit\">Add category</button>"
       "</div></form>"))

(defn- move-category-form [date category direction label]
  (str "<form method=\"post\" action=\"/categories/" (escape-html (:id category)) "/move\">"
       "<input type=\"hidden\" name=\"direction\" value=\"" (escape-html direction) "\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/days/" (escape-html date) "\">"
       "<button type=\"submit\">" (escape-html label) "</button>"
       "</form>"))

(defn- rename-category-form [date category]
  (str "<form class=\"category-rename-form\" method=\"post\" action=\"/categories/"
       (escape-html (:id category)) "/rename\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/days/" (escape-html date) "\">"
       "<input name=\"category-name\" value=\"" (escape-html (:name category))
       "\" aria-label=\"Category name\">"
       "<button type=\"submit\">Rename</button>"
       "</form>"))

(defn- delete-category-form [date category]
  (str "<form class=\"delete-form\" method=\"post\" action=\"/categories/"
       (escape-html (:id category)) "/delete\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/days/" (escape-html date) "\">"
       "<button type=\"submit\">Delete</button>"
       "</form>"))

(defn- category-management-row [date category]
  (str "<li class=\"category-row category-"
       (if (:parent-id category) "child" "root")
       "\" style=\"" (escape-html (group-style category)) "\">"
       (rename-category-form date category)
       "<span class=\"state\">" (if (:parent-id category) "child" "root") "</span>"
       "<div class=\"controls\">"
       (move-category-form date category "up" "Up")
       (move-category-form date category "down" "Down")
       (delete-category-form date category)
       "</div></li>"))

(defn- category-management-list [date categories]
  (str "<ul class=\"category-list\">"
       (apply str (map #(category-management-row date %) (active-categories categories)))
       "</ul>"))

(defn- work-log-row [categories log]
  (let [id (:id log)
        state-name (name (:state log))]
    (str "<article class=\"work-log-row state-" (escape-html state-name)
         "\" data-worklog-id=\"" (escape-html id) "\">"
         "<div class=\"work-log-main\"><div class=\"time-range\">"
         (escape-html (str (time-string (:start-minute log))
                           "-"
                           (time-string (:end-minute log))))
         "</div><div class=\"title\">" (escape-html (:title log))
         "</div><div class=\"state\">" (escape-html state-name)
         "</div></div>"
         "<div class=\"work-log-actions\"><form class=\"category-form controls\" data-auto-submit=\"category\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/assign-category\">"
         (category-select categories (:category-id log))
         "</form>"
         "<div class=\"work-log-range-line\"><form class=\"range-form\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/range\">"
         "<input type=\"time\" name=\"start-time\" value=\"" (escape-html (time-string (:start-minute log))) "\">"
         "<input type=\"time\" name=\"end-time\" value=\"" (escape-html (time-string (:end-minute log))) "\">"
         "<button type=\"submit\">Range</button></form>"
         "<form class=\"exclude-form\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/exclude\"><button type=\"submit\">Exclude</button></form>"
         "</div></div></article>")))

(defn- summary-row [{:keys [category hours row-kind]}]
  (str "<tr class=\"summary-row summary-" (escape-html (name row-kind))
       "\" style=\"" (escape-html (group-style category))
       "\" data-summary-category-id=\"" (escape-html (:id category)) "\"><td>"
       (escape-html (:name category))
       "</td><td>" (format "%.2fh" (double hours))
       "</td></tr>"))

(defn- summary-hours [summary category-id]
  (double (get (:category-hours summary) category-id 0.0)))

(defn- category-summary-rows [categories summary]
  (let [categories-map (categories-by-id categories)
        children (children-by-parent categories)
        known-ids (set (keys categories-map))
        ordered-rows
        (vec
         (mapcat
          (fn [root]
            (let [root-children (get children (:id root))
                  child-rows (keep (fn [child]
                                     (let [hours (summary-hours summary (:id child))]
                                       (when (pos? hours)
                                         {:category child
                                          :hours hours
                                          :row-kind :child})))
                                   root-children)
                  child-total (reduce + 0.0 (map :hours child-rows))
                  root-hours (summary-hours summary (:id root))]
              (cond
                (seq root-children)
                (if (pos? child-total)
                  (cons {:category root
                         :hours child-total
                         :row-kind :parent}
                        child-rows)
                  [])

                (pos? root-hours)
                [{:category root
                  :hours root-hours
                  :row-kind :root}]

                :else [])))
          (root-categories categories)))
        ordered-ids (set (map (comp :id :category) ordered-rows))
        inactive-hours (keep (fn [category]
                               (let [hours (summary-hours summary (:id category))]
                                 (when (and (not (:active? category))
                                            (pos? hours)
                                            (not (contains? ordered-ids (:id category))))
                                   {:category category
                                    :hours hours
                                    :row-kind :root})))
                             categories)
        orphan-hours (keep (fn [[category-id hours]]
                             (when (and (pos? (double hours))
                                        (not (contains? known-ids category-id)))
                               {:category {:id category-id
                                           :name (category-name categories-map category-id)}
                                :hours hours
                                :row-kind :root}))
                           (:category-hours summary))]
    (concat ordered-rows inactive-hours orphan-hours)))

(defn- query-href [view date edit?]
  (str "/?view=" view "&date=" date (when edit? "&edit=1")))

(defn- calendar-date-link [view reference-date edit? offset]
  (let [date (LocalDate/parse reference-date)
        target (if (= "month" view)
                 (.plusMonths date offset)
                 (.plusDays date (* 7 offset)))]
    (query-href view (str target) edit?)))

(defn- toggle-link [label href active?]
  (str "<a class=\"toggle-option" (when active? " active")
       "\" href=\"" (escape-html href) "\">" (escape-html label) "</a>"))

(defn- calendar-navigation [view reference-date edit?]
  (let [month? (= "month" view)
        prev-label (if month? "Prev month" "Prev week")
        next-label (if month? "Next month" "Next week")]
    (str "<div class=\"days-toolbar\"><div><h1>Days</h1>"
       "<div class=\"workspace-meta\">" (escape-html reference-date) "</div></div>"
       "<div class=\"view-tabs segmented-toggle\" role=\"group\" aria-label=\"Calendar view\">"
       (toggle-link "Month" (query-href "month" reference-date edit?) month?)
       (toggle-link "Week" (query-href "week" reference-date edit?) (not month?))
       "</div>"
       "<div class=\"day-navigation\">"
       "<a class=\"nav-button\" href=\"" (escape-html (calendar-date-link view reference-date edit? -1)) "\">"
       prev-label "</a>"
       "<form class=\"inline-form\" method=\"get\" action=\"/\">"
       "<input type=\"hidden\" name=\"view\" value=\"" (escape-html view) "\">"
       (when edit? "<input type=\"hidden\" name=\"edit\" value=\"1\">")
       "<input type=\"date\" name=\"date\" value=\"" (escape-html reference-date) "\">"
       "<button type=\"submit\">GOTO</button></form>"
       "<a class=\"nav-button\" href=\"" (escape-html (query-href view (today-string) edit?)) "\">TODAY</a>"
       "<a class=\"nav-button\" href=\"" (escape-html (calendar-date-link view reference-date edit? 1)) "\">"
       next-label "</a>"
       "</div>"
       "<div class=\"edit-tabs\">"
       (when month?
         (str "<div class=\"segmented-toggle\" role=\"group\" aria-label=\"Month edit\">"
              (toggle-link "Edit off" (query-href view reference-date false) (not edit?))
              (toggle-link "Edit on" (query-href view reference-date true) edit?)
              "</div>"))
       "<a class=\"nav-button\" href=\"/settings\">Settings</a>"
       "</div></div>")))

(defn- calendar-detail [day]
  (str "<div class=\"calendar-detail\">"
       "<div>" (escape-html (hours-string (:confirmed-work-minutes day))) " work</div>"
       "<div>" (escape-html (hours-string (:unallocated-minutes day))) " open</div>"
       "</div>"))

(declare compact-category-lines)

(defn- calendar-day-content [categories day]
  (str "<span class=\"day-number\">" (escape-html (:day-of-month day)) "</span>"
       "<span class=\"day-status\">" (escape-html (:status day)) "</span>"
       (calendar-detail day)
       (compact-category-lines categories day)))

(defn- month-day-cell [categories edit? day]
  (let [date (:date day)
        class-name (str "calendar-day day-status-" (:status day))]
    (str "<div class=\"" (escape-html class-name) "\" data-date=\"" (escape-html date) "\""
         (when edit? (str " data-calendar-day=\"" (escape-html date) "\""))
         ">"
         (if edit?
           (str "<button type=\"button\" data-calendar-day=\"" (escape-html date) "\">"
                (calendar-day-content categories day)
                "</button>")
           (str "<a href=\"/days/" (escape-html date) "\">"
                (calendar-day-content categories day)
                "</a>"))
         "</div>")))

(def weekday-labels
  {1 "Mon" 2 "Tue" 3 "Wed" 4 "Thu" 5 "Fri" 6 "Sat" 7 "Sun"})

(defn- ordered-weekdays [week-start-day]
  (map #(inc (mod (+ (dec week-start-day) %) 7)) (range 7)))

(defn- weekday-headers [week-start-day]
  (apply str
         (map #(str "<div class=\"weekday-header\">" (weekday-labels %) "</div>")
              (ordered-weekdays week-start-day))))

(defn- calendar-blank-cell []
  "<div class=\"calendar-day calendar-blank\" aria-hidden=\"true\"></div>")

(defn- month-leading-blanks [state]
  (if-let [first-day (first (:days state))]
    (mod (- (:weekday first-day) (:week-start-day state)) 7)
    0))

(defn- month-trailing-blanks [day-count]
  (mod (- 7 (mod day-count 7)) 7))

(defn- day-status-popover [reference-date]
  (str "<div id=\"range-action-popover\" class=\"range-action-popover\" hidden>"
       "<form id=\"day-status-range-form\" method=\"post\" action=\"/day-status-ranges\">"
       "<input type=\"hidden\" name=\"start-date\" value=\"\">"
       "<input type=\"hidden\" name=\"end-date\" value=\"\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\""
       (escape-html (query-href "month" reference-date true)) "\">"
       "<div class=\"title\" data-range-label></div>"
       "<div class=\"range-actions\">"
       "<button type=\"submit\" name=\"status\" value=\"workday\">Workday</button>"
       "<button type=\"submit\" name=\"status\" value=\"holiday\">Holiday</button>"
       "<a class=\"nav-button\" data-open-day href=\"/days/" (escape-html reference-date) "\">Open</a>"
       "</div></form></div>"))

(defn- month-calendar [state]
  (let [edit? (:edit? state)
        leading (month-leading-blanks state)
        trailing (month-trailing-blanks (+ leading (count (:days state))))]
    (str "<section class=\"days-calendar\" data-calendar-view=\"month\" data-calendar-edit=\""
         (if edit? "active" "inactive")
         "\"><div class=\"month-grid\">"
         (weekday-headers (:week-start-day state))
         (apply str (repeat leading (calendar-blank-cell)))
         (apply str (map #(month-day-cell (:categories state) edit? %) (:days state)))
         (apply str (repeat trailing (calendar-blank-cell)))
         "</div>"
         (when edit? (day-status-popover (:reference-date state)))
         "</section>")))

(defn- category-lines [categories day]
  (let [rows (category-summary-rows categories {:category-hours (:category-hours day)})]
    (if (seq rows)
      (str "<ul class=\"calendar-detail\">"
           (apply str
                  (map (fn [{:keys [category hours]}]
                         (str "<li>" (escape-html (:name category)) " "
                              (escape-html (format "%.2fh" (double hours)))
                              "</li>"))
                      rows))
           "</ul>")
      "<div class=\"calendar-detail\">No work</div>")))

(defn- compact-category-lines [categories day]
  (let [rows (category-summary-rows categories {:category-hours (:category-hours day)})]
    (when (seq rows)
      (str "<ul class=\"calendar-detail\">"
           (apply str
                  (map (fn [{:keys [category hours]}]
                         (str "<li>" (escape-html (:name category)) " "
                              (escape-html (format "%.2fh" (double hours)))
                              "</li>"))
                       rows))
           "</ul>"))))

(defn- week-day-card [categories day]
  (let [date (:date day)
        class-name (str "week-day-card day-status-" (:status day))]
    (str "<article class=\"" (escape-html class-name) "\" data-date=\"" (escape-html date) "\">"
         "<a href=\"/days/" (escape-html date) "\">"
         "<div class=\"day-number\">" (escape-html date) "</div>"
         "<span class=\"day-status\">" (escape-html (:status day)) "</span>"
         (calendar-detail day)
         (category-lines categories day)
         "</a></article>")))

(defn- week-calendar [state]
  (str "<section class=\"week-calendar days-calendar\" data-calendar-view=\"week\" data-calendar-edit=\"inactive\">"
       (apply str (map #(week-day-card (:categories state) %) (:days state)))
       "</section>"))

(def calendar-script
  "<script>
(function(){
  const calendar = document.querySelector('.days-calendar[data-calendar-view=\"month\"][data-calendar-edit=\"active\"]');
  const popover = document.getElementById('range-action-popover');
  if (!calendar || !popover) return;
  const form = document.getElementById('day-status-range-form');
  const startInput = form.querySelector(\"input[name='start-date']\");
  const endInput = form.querySelector(\"input[name='end-date']\");
  const label = popover.querySelector('[data-range-label]');
  const openDay = popover.querySelector('[data-open-day]');
  let selecting = false;
  let startDate = null;
  let endDate = null;
  let suppressNextClick = false;
  function cellForEvent(event){
    const node = document.elementFromPoint(event.clientX, event.clientY);
    return node && node.closest('[data-calendar-day]');
  }
  function orderedRange(a, b){
    return a <= b ? [a, b] : [b, a];
  }
  function paintRange(){
    const range = orderedRange(startDate, endDate);
    document.querySelectorAll('.calendar-day').forEach(function(cell){
      const date = cell.dataset.date;
      cell.classList.toggle('range-selected', date >= range[0] && date <= range[1]);
    });
    startInput.value = range[0];
    endInput.value = range[1];
    label.textContent = range[0] === range[1] ? range[0] : range[0] + ' - ' + range[1];
    openDay.href = '/days/' + range[0];
  }
  function showPopover(event){
    popover.style.left = Math.min(event.clientX + 8, window.innerWidth - 320) + 'px';
    popover.style.top = Math.min(event.clientY + 8, window.innerHeight - 150) + 'px';
    popover.hidden = false;
  }
  calendar.addEventListener('pointerdown', function(event){
    if (event.button !== 0) return;
    const cell = cellForEvent(event);
    if (!cell) return;
    selecting = true;
    startDate = cell.dataset.calendarDay;
    endDate = startDate;
    calendar.setPointerCapture(event.pointerId);
    popover.hidden = true;
    paintRange();
    event.preventDefault();
  });
  calendar.addEventListener('pointermove', function(event){
    if (!selecting) return;
    const cell = cellForEvent(event);
    if (!cell) return;
    endDate = cell.dataset.calendarDay;
    paintRange();
  });
  calendar.addEventListener('pointerup', function(event){
    if (!selecting) return;
    selecting = false;
    const cell = cellForEvent(event);
    if (cell) endDate = cell.dataset.calendarDay;
    paintRange();
    showPopover(event);
    suppressNextClick = true;
  });
  document.addEventListener('click', function(event){
    if (suppressNextClick) {
      suppressNextClick = false;
      return;
    }
    if (!popover.contains(event.target) && !event.target.closest('[data-calendar-day]')) {
      popover.hidden = true;
    }
  });
})();
</script>")

(defn days-page [state]
  (page "worklog-timeblock days"
        (str "<main class=\"days-shell\">"
             (calendar-navigation (:view state) (:reference-date state) (:edit? state))
             (flash-warning (:flash-warning state))
             (if (= "week" (:view state))
               (week-calendar state)
               (month-calendar state))
             "</main>"
             calendar-script)))

(defn- attendance-range-label [attendance]
  (let [clock-in (:clock-in-minute attendance)
        clock-out (:clock-out-minute attendance)]
    (if (and (integer? clock-in) (integer? clock-out))
      (str (time-string clock-in) "-" (time-string clock-out))
      "Not set")))

(declare day-breaks-section)

(defn- attendance-panel [date attendance summary break-mode breaks]
  (let [stats (:attendance summary)]
    (str "<section class=\"input-panel attendance-panel\"><h2 class=\"pane-title\">Attendance</h2>"
         "<dl class=\"metric-list\">"
         "<dt>Clock range</dt><dd>" (escape-html (attendance-range-label attendance)) "</dd>"
         "<dt>Span</dt><dd>" (escape-html (hours-string (:span-minutes stats))) "</dd>"
         "<dt>Recorded work</dt><dd>" (escape-html (hours-string (:confirmed-work-minutes stats))) "</dd>"
         "<dt>Breaks</dt><dd>" (escape-html (hours-string (:break-minutes stats))) "</dd>"
         "<dt>Unallocated</dt><dd>" (escape-html (hours-string (:unallocated-minutes stats))) "</dd>"
         "</dl>"
         "<div class=\"controls\">"
         "<form method=\"post\" action=\"/days/" (escape-html date) "/attendance/clock-in-now\">"
         "<button type=\"submit\">Clock in now</button></form>"
         "<form method=\"post\" action=\"/days/" (escape-html date) "/attendance/clock-out-now\">"
         "<button type=\"submit\">Clock out now</button></form>"
         "</div>"
         "<form class=\"range-form\" method=\"post\" action=\"/days/" (escape-html date) "/attendance\">"
         "<input type=\"time\" name=\"clock-in-time\" value=\""
         (escape-html (optional-time-string (:clock-in-minute attendance))) "\">"
         "<input type=\"time\" name=\"clock-out-time\" value=\""
         (escape-html (optional-time-string (:clock-out-minute attendance))) "\">"
         "<button type=\"submit\">Set attendance</button></form>"
         (day-breaks-section date break-mode breaks)
         "</section>")))

(defn- daily-break-rule-form [redirect-to]
  (str "<form class=\"input-panel\" method=\"post\" action=\"/break-rules\">"
       "<h2 class=\"pane-title\">Default break</h2>"
       "<div class=\"field-help\">Used when break mode is fixed. These rules create daily break blocks, but edited day breaks stay independent.</div>"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"" (escape-html redirect-to) "\">"
       "<div class=\"input-grid\">"
       "<label class=\"field wide\"><span class=\"field-label\">Title</span>"
       "<input name=\"break-title\" value=\"Lunch\" placeholder=\"Break title\"></label>"
       "<label class=\"field\"><span class=\"field-label\">Start</span>"
       "<input type=\"time\" name=\"start-time\" value=\"12:00\"></label>"
       "<label class=\"field\"><span class=\"field-label\">End</span>"
       "<input type=\"time\" name=\"end-time\" value=\"13:00\"></label>"
       "<button type=\"submit\">Add daily break</button>"
       "</div></form>"))

(defn- one-off-break-form [date]
  (str "<form class=\"one-off-break-form\" method=\"post\" action=\"/days/" (escape-html date) "/breaks\">"
       "<h3 class=\"sub-title\">One-off break</h3>"
       "<div class=\"input-grid\">"
       "<input name=\"break-title\" value=\"Break\" placeholder=\"Break title\">"
       "<input type=\"time\" name=\"start-time\" value=\"12:00\">"
       "<input type=\"time\" name=\"end-time\" value=\"12:15\">"
       "<button type=\"submit\">Add break</button>"
       "</div></form>"))

(defn- break-row [break]
  (str "<article class=\"break-row\" data-break-id=\"" (escape-html (:id break)) "\">"
       "<div><span class=\"time-range\">"
       (escape-html (str (time-string (:start-minute break))
                         "-"
                         (time-string (:end-minute break))))
       "</span> <span class=\"title\">" (escape-html (:title break)) "</span></div>"
       "<div class=\"break-actions\">"
       "<form class=\"range-form\" method=\"post\" action=\"/breaks/"
       (escape-html (:id break)) "/range\">"
       "<input type=\"time\" name=\"start-time\" value=\"" (escape-html (time-string (:start-minute break))) "\">"
       "<input type=\"time\" name=\"end-time\" value=\"" (escape-html (time-string (:end-minute break))) "\">"
       "<button type=\"submit\">Range</button></form>"
       "<form class=\"delete-form\" method=\"post\" action=\"/breaks/"
       (escape-html (:id break)) "/delete\">"
       "<button type=\"submit\">Delete</button></form>"
       "</div></article>"))

(defn- day-breaks-section [date break-mode breaks]
  (let [fixed? (= :fixed break-mode)]
    (str "<div class=\"day-breaks\"><h3 class=\"sub-title\">"
       (if fixed? "Fixed breaks" "Break today")
       "</h3>"
       "<div class=\"break-list\">"
       (if (seq breaks)
         (apply str (map break-row breaks))
         (str "<p class=\"state\">No " (if fixed? "fixed " "") "breaks set.</p>"))
       "</div>"
       (when-not fixed? (one-off-break-form date))
       "</div>")))

(defn- warning-item [warning]
  (case (:type warning)
    :uncategorized (str "<li class=\"warn\">Uncategorized: "
                        (escape-html (:title warning))
                        "</li>")
    :large-gap (str "<li class=\"warn\">Large gap: "
                    (escape-html (:minutes warning))
                    " minutes</li>")
    :non-assignable-category (str "<li class=\"warn\">Non-assignable category: "
                                  (escape-html (:title warning))
                                  "</li>")
    :source-updated (str "<li class=\"warn\">Source updated: "
                         (escape-html (:external-id warning))
                         "</li>")
    (str "<li class=\"warn\">" (escape-html warning) "</li>")))

(defn- category-totals-panel [categories summary]
  (str "<section class=\"input-panel category-totals-panel\">"
       "<h2 class=\"pane-title\">Category totals</h2>"
       "<table><thead><tr><th>Category</th><th>Hours</th></tr></thead><tbody>"
       (let [rows (category-summary-rows categories summary)]
         (if (seq rows)
           (apply str (map summary-row rows))
           "<tr><td colspan=\"2\" class=\"state\">No category totals yet.</td></tr>"))
       "</tbody></table></section>"))

(defn- category-settings-panel [date categories]
  (str "<section class=\"input-panel category-settings-panel\">"
       "<h2 class=\"pane-title\">Categories</h2>"
       (new-category-form date categories)
       (category-management-list date categories)
       "</section>"))

(defn- day-timeline [date work-logs source-events attendance breaks]
  (str "<div class=\"day-timeline\" data-date=\"" (escape-html date) "\">"
       "<div class=\"timeline-hours\">" (timeline-hour-labels) "</div>"
       "<div class=\"timeline-track\" data-minute-quantum=\"15\">"
       "<div class=\"timeline-selection\" hidden></div>"
       "<div class=\"timeline-warning-bubble\" hidden></div>"
       (timeline-attendance attendance)
       (apply str (map timeline-break-block breaks))
       (apply str (map timeline-confirmed-block
                       (filter timeline-work-block? work-logs)))
       (apply str (map #(timeline-imported-block work-logs %) source-events))
       "</div></div>"))

(def timeline-script
  "<script>
(function(){
  const form = document.getElementById('new-work-log-form');
  const track = document.querySelector('.timeline-track');
  const entryPane = document.querySelector('.entry-pane');
  const selection = document.querySelector('.timeline-selection');
  const warningBubble = document.querySelector('.timeline-warning-bubble');
  const preview = document.getElementById('draft-summary-preview');
  const menu = document.getElementById('candidate-menu');
  const titleSuggestionList = document.getElementById('title-suggestion-list');
  if (!form || !track || !entryPane || !selection || !warningBubble || !preview || !menu || !titleSuggestionList) return;
  const startInput = form.querySelector(\"input[name='start-time']\");
  const endInput = form.querySelector(\"input[name='end-time']\");
  const categorySelect = form.querySelector(\"select[name='category-id']\");
  const titleInput = form.querySelector(\"input[name='title']\");
  const submitButton = form.querySelector(\"button[type='submit']\");
  const quantum = Number(track.dataset.minuteQuantum || 15);
  let dragging = false;
  let dragStart = null;
  let blockDrag = null;
  let suppressBlockClick = false;
  let titleSuggestionItems = [];
  let activeTitleSuggestionIndex = -1;
  let titleSuggestionTimer = null;
  let titleSuggestionRequest = 0;
  let titleSuggestionAbort = null;
  let titleSuggestionComposing = false;
  function pad(value){ return String(value).padStart(2, '0'); }
  function minuteToTime(minute){
    const bounded = Math.max(0, Math.min(1440, minute));
    return pad(Math.floor(bounded / 60)) + ':' + pad(bounded % 60);
  }
  function timeToMinute(value){
    const match = String(value || '').match(/^(\\d{1,2}):(\\d{2})$/);
    if (!match) return null;
    return Number(match[1]) * 60 + Number(match[2]);
  }
  function snap(minute){
    return Math.max(0, Math.min(1440, Math.round(minute / quantum) * quantum));
  }
  function minuteFromEvent(event){
    const rect = track.getBoundingClientRect();
    return snap(((event.clientY - rect.top) / rect.height) * 1440);
  }
  function blockRange(block){
    return {
      id: block.dataset.worklogId,
      start: Number(block.dataset.startMinute),
      end: Number(block.dataset.endMinute)
    };
  }
  function rangesOverlap(start, end, otherStart, otherEnd){
    return start < otherEnd && otherStart < end;
  }
  function otherConfirmedBlocks(id){
    return Array.from(document.querySelectorAll('.confirmed-block')).filter(function(block){
      return block.dataset.worklogId !== String(id);
    });
  }
  function overlapsOtherConfirmed(id, start, end){
    return otherConfirmedBlocks(id).some(function(block){
      const other = blockRange(block);
      return rangesOverlap(start, end, other.start, other.end);
    });
  }
  function adjacentBlock(range, side){
    const blocks = otherConfirmedBlocks(range.id);
    return blocks.find(function(block){
      const other = blockRange(block);
      return side === 'top' ? other.end === range.start : other.start === range.end;
    });
  }
  function showTimelineWarning(message, minute){
    warningBubble.textContent = message;
    warningBubble.style.top = (Math.max(0, Math.min(1410, minute)) / 1440 * 100) + '%';
    warningBubble.hidden = false;
  }
  function hideTimelineWarning(){
    warningBubble.hidden = true;
  }
  function setSelection(start, end, updateInputs){
    const low = Math.min(start, end);
    const high = Math.max(start, end);
    const safeEnd = high === low ? Math.min(1440, low + quantum) : high;
    selection.hidden = false;
    selection.dataset.startMinute = String(low);
    selection.dataset.endMinute = String(safeEnd);
    selection.style.top = (low / 1440 * 100) + '%';
    selection.style.height = ((safeEnd - low) / 1440 * 100) + '%';
    if (updateInputs) {
      startInput.value = minuteToTime(low);
      endInput.value = minuteToTime(safeEnd);
    }
    updatePreview();
  }
  function overlapsConfirmed(start, end){
    return Array.from(document.querySelectorAll('.confirmed-block')).some(function(block){
      const blockStart = Number(block.dataset.startMinute);
      const blockEnd = Number(block.dataset.endMinute);
      return start < blockEnd && blockStart < end;
    });
  }
  function updateFromInputs(){
    const start = timeToMinute(startInput.value);
    const end = timeToMinute(endInput.value);
    if (start === null || end === null || end <= start) return;
    setSelection(snap(start), snap(end), false);
  }
  function updatePreview(){
    const start = timeToMinute(startInput.value);
    const end = timeToMinute(endInput.value);
    if (start === null || end === null || end <= start) {
      preview.textContent = 'Draft';
      submitButton.disabled = true;
      return;
    }
    if (overlapsConfirmed(start, end)) {
      preview.textContent = 'Overlaps confirmed work';
      submitButton.disabled = true;
      return;
    }
    submitButton.disabled = false;
    const selected = categorySelect.options[categorySelect.selectedIndex];
    const label = selected && selected.value ? selected.textContent.trim() : 'Uncategorized';
    preview.textContent = label + ' ' + ((end - start) / 60).toFixed(2) + 'h';
  }
  function titleSuggestionOpen(){
    return !titleSuggestionList.hidden && titleSuggestionItems.length > 0;
  }
  function closeTitleSuggestions(){
    titleSuggestionItems = [];
    activeTitleSuggestionIndex = -1;
    titleSuggestionList.replaceChildren();
    titleSuggestionList.hidden = true;
    titleInput.setAttribute('aria-expanded', 'false');
  }
  function setActiveTitleSuggestion(index){
    const buttons = Array.from(titleSuggestionList.querySelectorAll('.title-suggestion-option'));
    buttons.forEach(function(button){
      button.classList.remove('active');
      button.setAttribute('aria-selected', 'false');
    });
    activeTitleSuggestionIndex = index;
    if (index < 0 || index >= buttons.length) return;
    const button = buttons[index];
    button.classList.add('active');
    button.setAttribute('aria-selected', 'true');
    button.scrollIntoView({ block: 'nearest' });
  }
  function applyTitleSuggestion(index){
    const item = titleSuggestionItems[index];
    if (!item) return false;
    const categoryId = item['category-id'];
    titleInput.value = item.title || '';
    if (categoryId === null || categoryId === undefined) {
      categorySelect.value = '';
    } else {
      const nextValue = String(categoryId);
      categorySelect.value = nextValue;
      if (categorySelect.value !== nextValue) {
        categorySelect.value = '';
      }
    }
    updatePreview();
    closeTitleSuggestions();
    return true;
  }
  function renderTitleSuggestions(suggestions){
    titleSuggestionItems = suggestions;
    activeTitleSuggestionIndex = -1;
    titleSuggestionList.replaceChildren();
    if (suggestions.length === 0) {
      closeTitleSuggestions();
      return;
    }
    suggestions.forEach(function(item, index){
      const button = document.createElement('button');
      const title = document.createElement('span');
      const category = document.createElement('span');
      button.type = 'button';
      button.className = 'title-suggestion-option';
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', 'false');
      button.dataset.index = String(index);
      title.className = 'title-suggestion-title';
      title.textContent = item.title || '';
      category.className = 'title-suggestion-category';
      category.textContent = item['category-name'] || 'Uncategorized';
      button.append(title, category);
      button.addEventListener('pointerdown', function(event){
        event.preventDefault();
        applyTitleSuggestion(index);
      });
      button.addEventListener('click', function(event){
        event.preventDefault();
        applyTitleSuggestion(index);
      });
      titleSuggestionList.append(button);
    });
    titleSuggestionList.hidden = false;
    titleInput.setAttribute('aria-expanded', 'true');
  }
  async function fetchTitleSuggestions(query){
    const requestId = titleSuggestionRequest + 1;
    titleSuggestionRequest = requestId;
    if (titleSuggestionAbort) titleSuggestionAbort.abort();
    titleSuggestionAbort = new AbortController();
    try {
      const response = await fetch('/api/worklog-title-suggestions?q=' + encodeURIComponent(query) + '&limit=8', {
        signal: titleSuggestionAbort.signal
      });
      if (!response.ok) {
        closeTitleSuggestions();
        return;
      }
      const body = await response.json();
      if (requestId !== titleSuggestionRequest) return;
      if (titleInput.value.trim() !== query.trim()) return;
      renderTitleSuggestions(Array.isArray(body.suggestions) ? body.suggestions : []);
    } catch (error) {
      if (error.name !== 'AbortError') closeTitleSuggestions();
    }
  }
  function scheduleTitleSuggestions(){
    if (titleSuggestionComposing) return;
    window.clearTimeout(titleSuggestionTimer);
    const query = titleInput.value.trim();
    if (!query) {
      closeTitleSuggestions();
      return;
    }
    titleSuggestionTimer = window.setTimeout(function(){
      fetchTitleSuggestions(query);
    }, 120);
  }
  function moveTitleSuggestion(delta){
    if (!titleSuggestionOpen()) return false;
    const maxIndex = titleSuggestionItems.length - 1;
    const nextIndex = activeTitleSuggestionIndex < 0
      ? (delta > 0 ? 0 : maxIndex)
      : Math.max(0, Math.min(maxIndex, activeTitleSuggestionIndex + delta));
    setActiveTitleSuggestion(nextIndex);
    return true;
  }
  function hideMenu(){ menu.hidden = true; }
  async function patchWorkLogRange(id, start, end){
    const response = await fetch('/api/worklogs/' + id, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ 'start-minute': start, 'end-minute': end })
    });
    if (!response.ok) throw new Error('range update failed');
  }
  async function postBoundaryAdjustment(leftId, rightId, boundaryMinute){
    const day = track.closest('.day-timeline').dataset.date;
    const response = await fetch('/api/days/' + day + '/boundary-adjustments', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        left: { kind: 'work-log', id: Number(leftId) },
        right: { kind: 'work-log', id: Number(rightId) },
        'boundary-minute': boundaryMinute
      })
    });
    if (!response.ok) throw new Error('boundary update failed');
  }
  function blockDragMode(block, event){
    const rect = block.getBoundingClientRect();
    const edge = Math.min(10, rect.height / 3);
    if (event.clientY - rect.top <= edge) return 'resize-start';
    if (rect.bottom - event.clientY <= edge) return 'resize-end';
    return 'move';
  }
  function selectedBoundaryCandidate(event){
    const selected = document.querySelector('.confirmed-block.selected');
    if (!selected) return null;
    const blockRect = selected.getBoundingClientRect();
    const trackRect = track.getBoundingClientRect();
    if (event.clientX < trackRect.left || event.clientX > trackRect.right) return null;
    const edge = Math.max(6, Math.min(12, blockRect.height / 3));
    if (Math.abs(event.clientY - blockRect.top) <= edge) {
      return { block: selected, mode: 'resize-start' };
    }
    if (Math.abs(event.clientY - blockRect.bottom) <= edge) {
      return { block: selected, mode: 'resize-end' };
    }
    return null;
  }
  function updateBlockCursor(block, event){
    if (blockDrag) return;
    block.style.cursor = blockDragMode(block, event) === 'move' ? 'move' : 'ns-resize';
  }
  function startBlockDrag(block, event, forcedMode){
    const range = blockRange(block);
    hideMenu();
    closeTitleSuggestions();
    hideTimelineWarning();
    blockDrag = {
      block: block,
      id: range.id,
      start: range.start,
      end: range.end,
      pointerStart: minuteFromEvent(event),
      mode: forcedMode || blockDragMode(block, event),
      shift: event.shiftKey,
      moved: false,
      nextRange: range
    };
    (event.currentTarget || track).setPointerCapture(event.pointerId);
    event.preventDefault();
    event.stopPropagation();
  }
  function computeBlockDragRange(event){
    if (!blockDrag) return null;
    const minute = minuteFromEvent(event);
    const delta = minute - blockDrag.pointerStart;
    const duration = blockDrag.end - blockDrag.start;
    let start = blockDrag.start;
    let end = blockDrag.end;
    if (blockDrag.mode === 'move') {
      start = Math.max(0, Math.min(1440 - duration, snap(blockDrag.start + delta)));
      end = start + duration;
    } else if (blockDrag.mode === 'resize-start') {
      start = snap(minute);
      if (blockDrag.shift && start < blockDrag.start) {
        return { invalid: true, minute: start, message: 'Shift edge drag only shrinks the block' };
      }
      start = Math.max(0, Math.min(start, blockDrag.end - quantum));
    } else if (blockDrag.mode === 'resize-end') {
      end = snap(minute);
      if (blockDrag.shift && end > blockDrag.end) {
        return { invalid: true, minute: end, message: 'Shift edge drag only shrinks the block' };
      }
      end = Math.min(1440, Math.max(end, blockDrag.start + quantum));
    }
    return { start: start, end: end, minute: minute };
  }
  function updateBlockDrag(event){
    if (!blockDrag) return;
    const next = computeBlockDragRange(event);
    if (!next) return;
    blockDrag.moved = blockDrag.moved || Math.abs(next.minute - blockDrag.pointerStart) >= quantum;
    if (next.invalid) {
      showTimelineWarning(next.message, next.minute);
      return;
    }
    blockDrag.nextRange = next;
    setSelection(next.start, next.end, false);
    if (overlapsOtherConfirmed(blockDrag.id, next.start, next.end)) {
      showTimelineWarning('Overlaps confirmed work', next.minute);
    } else {
      hideTimelineWarning();
    }
  }
  async function finishBlockDrag(event){
    if (!blockDrag) return;
    const current = blockDrag;
    const next = computeBlockDragRange(event);
    blockDrag = null;
    if (!current.moved || !next) return;
    suppressBlockClick = true;
    window.setTimeout(function(){ suppressBlockClick = false; }, 0);
    if (next.invalid) {
      showTimelineWarning(next.message, next.minute);
      return;
    }
    const topAdjacent = current.mode === 'resize-start' && !current.shift
      ? adjacentBlock(current, 'top')
      : null;
    const bottomAdjacent = current.mode === 'resize-end' && !current.shift
      ? adjacentBlock(current, 'bottom')
      : null;
    try {
      if (topAdjacent) {
        const adjacent = blockRange(topAdjacent);
        if (!(adjacent.start < next.start && next.start < current.end)) {
          showTimelineWarning('Invalid boundary', next.start);
          return;
        }
        await postBoundaryAdjustment(adjacent.id, current.id, next.start);
      } else if (bottomAdjacent) {
        const adjacent = blockRange(bottomAdjacent);
        if (!(current.start < next.end && next.end < adjacent.end)) {
          showTimelineWarning('Invalid boundary', next.end);
          return;
        }
        await postBoundaryAdjustment(current.id, adjacent.id, next.end);
      } else {
        if (overlapsOtherConfirmed(current.id, next.start, next.end)) {
          showTimelineWarning('Overlaps confirmed work', next.minute);
          return;
        }
        await patchWorkLogRange(current.id, next.start, next.end);
      }
      window.location.reload();
    } catch (_) {
      showTimelineWarning('Could not save range', next.minute);
    }
  }
  function clearSelectedWorkLog(){
    document.querySelectorAll('.confirmed-block.selected').forEach(function(block){
      block.classList.remove('selected');
    });
    document.querySelectorAll('.work-log-row.selected').forEach(function(row){
      row.classList.remove('selected');
    });
    entryPane.removeAttribute('data-selected-worklog-id');
  }
  function centerRowInEntryPane(row){
    const paneRect = entryPane.getBoundingClientRect();
    const rowRect = row.getBoundingClientRect();
    entryPane.scrollTop += rowRect.top - paneRect.top - (paneRect.height / 2) + (rowRect.height / 2);
  }
  function selectWorkLog(id){
    const row = document.querySelector(\".work-log-row[data-worklog-id='\" + id + \"']\");
    const block = document.querySelector(\".confirmed-block[data-worklog-id='\" + id + \"']\");
    if (!row || !block) return;
    clearSelectedWorkLog();
    row.classList.add('selected');
    block.classList.add('selected');
    entryPane.dataset.selectedWorklogId = id;
    centerRowInEntryPane(row);
  }
  track.addEventListener('pointerdown', function(event){
    if (event.button !== 0) return;
    const selectedBoundary = selectedBoundaryCandidate(event);
    if (selectedBoundary) {
      startBlockDrag(selectedBoundary.block, event, selectedBoundary.mode);
      return;
    }
    if (event.target.closest('.confirmed-block')) return;
    hideMenu();
    closeTitleSuggestions();
    dragging = true;
    dragStart = minuteFromEvent(event);
    track.setPointerCapture(event.pointerId);
    setSelection(dragStart, dragStart + quantum, true);
    event.preventDefault();
  });
  track.addEventListener('pointermove', function(event){
    if (!dragging) return;
    setSelection(dragStart, minuteFromEvent(event), true);
  });
  track.addEventListener('pointerup', function(event){
    if (!dragging) return;
    dragging = false;
    setSelection(dragStart, minuteFromEvent(event), true);
  });
  [startInput, endInput].forEach(function(input){
    input.addEventListener('input', updateFromInputs);
    input.addEventListener('change', updateFromInputs);
  });
  [categorySelect, titleInput].forEach(function(input){
    input.addEventListener('input', updatePreview);
    input.addEventListener('change', updatePreview);
  });
  titleInput.addEventListener('compositionstart', function(){
    titleSuggestionComposing = true;
  });
  titleInput.addEventListener('compositionend', function(){
    titleSuggestionComposing = false;
    scheduleTitleSuggestions();
  });
  titleInput.addEventListener('input', scheduleTitleSuggestions);
  titleInput.addEventListener('keydown', function(event){
    if (titleSuggestionComposing || event.isComposing) return;
    if (event.key === 'ArrowDown' && titleSuggestionOpen()) {
      event.preventDefault();
      moveTitleSuggestion(1);
    } else if (event.key === 'ArrowUp' && titleSuggestionOpen()) {
      event.preventDefault();
      moveTitleSuggestion(-1);
    } else if (event.key === 'Enter') {
      if (titleSuggestionOpen() && activeTitleSuggestionIndex >= 0) {
        event.preventDefault();
        applyTitleSuggestion(activeTitleSuggestionIndex);
      } else {
        closeTitleSuggestions();
      }
    } else if (event.key === 'Escape') {
      closeTitleSuggestions();
    }
  });
  form.addEventListener('submit', closeTitleSuggestions);
  document.addEventListener('pointermove', updateBlockDrag);
  document.addEventListener('pointerup', finishBlockDrag);
  document.querySelectorAll('.confirmed-block').forEach(function(block){
    block.addEventListener('pointermove', function(event){
      updateBlockCursor(block, event);
    });
    block.addEventListener('pointerleave', function(){
      block.style.cursor = 'move';
    });
    block.addEventListener('pointerdown', function(event){
      if (event.button !== 0) return;
      const selectedBoundary = selectedBoundaryCandidate(event);
      if (selectedBoundary) {
        startBlockDrag(selectedBoundary.block, event, selectedBoundary.mode);
      } else {
        startBlockDrag(block, event);
      }
    });
    block.addEventListener('click', function(event){
      if (event.button !== 0) return;
      if (suppressBlockClick) {
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      hideMenu();
      selectWorkLog(block.dataset.worklogId);
      event.stopPropagation();
    });
  });
  document.querySelectorAll(\"form[data-auto-submit='category']\").forEach(function(categoryForm){
    const select = categoryForm.querySelector(\"select[name='category-id']\");
    if (!select) return;
    select.addEventListener('change', function(){
      if (categoryForm.requestSubmit) {
        categoryForm.requestSubmit();
      } else {
        categoryForm.submit();
      }
    });
  });
  document.querySelectorAll('.imported-block').forEach(function(block){
    block.addEventListener('contextmenu', function(event){
      event.preventDefault();
      closeTitleSuggestions();
      const id = block.dataset.sourceEventId;
      menu.querySelector('[data-menu-title]').textContent = block.dataset.title || '';
      menu.querySelector('[data-confirm-form]').action = '/source-events/' + id + '/confirm';
      menu.querySelector('[data-exclude-form]').action = '/source-events/' + id + '/exclude';
      menu.style.left = Math.min(event.clientX, window.innerWidth - 340) + 'px';
      menu.style.top = Math.min(event.clientY, window.innerHeight - 220) + 'px';
      menu.hidden = false;
    });
  });
  document.addEventListener('click', function(event){
    if (!menu.contains(event.target)) hideMenu();
    if (!event.target.closest('.title-suggestion-wrap')) closeTitleSuggestions();
  });
  updateFromInputs();
})();
</script>")

(defn day-page [{:keys [date break-mode work-logs source-events attendance breaks summary]
                 :as state} categories]
  (page (str "worklog-timeblock " date)
        (str "<main class=\"day-workspace\">"
             "<header class=\"workspace-header\"><div class=\"workspace-title-area\">"
             "<div class=\"workspace-title-row\"><h1>" (escape-html date)
             "</h1>" (day-navigation date) "</div>"
             "<div class=\"workspace-meta\">" (count work-logs) " logs</div></div>"
             "<nav class=\"page-actions\">"
             "<a class=\"nav-button\" href=\"/\">Days</a>"
             "<a class=\"nav-button\" href=\"/settings\">Settings</a>"
             "</nav></header>"
             (flash-warning (:flash-warning state))
             "<div class=\"workspace-grid\">"
             "<section class=\"timeline-pane\"><h2 class=\"pane-title\">Timeline</h2>"
             (day-timeline date work-logs source-events attendance breaks)
             (candidate-menu categories)
             "</section>"
             "<section class=\"entry-pane\">"
             (new-work-log-form date categories)
             (attention-queue categories work-logs source-events)
             "<div class=\"work-log-list\">"
             (apply str (map #(work-log-row categories %) work-logs))
             "</div></section>"
             "<aside class=\"summary-pane\">"
             (attendance-panel date attendance summary break-mode breaks)
             (category-totals-panel categories summary)
             (category-settings-panel date categories)
             (when (seq (:warnings summary))
               (str "<section class=\"input-panel warnings-panel\"><h2 class=\"pane-title\">Warnings</h2><ul class=\"warnings\">"
                    (apply str (map warning-item (:warnings summary)))
                    "</ul></section>"))
             "</aside></div></main>"
             timeline-script)))

(defn- selected-attr [current value]
  (when (= current value) " selected"))

(defn- checked-attr [values value]
  (when (contains? values value) " checked"))

(defn- break-mode-settings-form [settings]
  (let [mode (:break-mode settings)]
    (str "<form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/break-mode\">"
         "<h2 class=\"pane-title\">Break mode</h2>"
         "<div class=\"field-help\">Fixed inserts configured default breaks each day. Flexible keeps daily breaks manual and shows Break today on the worklog page.</div>"
         "<input type=\"hidden\" name=\"redirect-to\" value=\"/settings\">"
         "<div class=\"segmented-toggle\" role=\"group\" aria-label=\"Break mode\">"
         "<button class=\"toggle-option" (when (= mode "fixed") " active")
         "\" type=\"submit\" name=\"break-mode\" value=\"fixed\">Fixed</button>"
         "<button class=\"toggle-option" (when (= mode "flexible") " active")
         "\" type=\"submit\" name=\"break-mode\" value=\"flexible\">Flexible</button>"
         "</div></form>")))

(defn- holiday-policy-form [settings]
  (let [policy (:holiday-policy settings)
        mode (:mode policy)
        weekdays (set (:weekdays policy))
        labels {1 "Mon" 2 "Tue" 3 "Wed" 4 "Thu" 5 "Fri" 6 "Sat" 7 "Sun"}]
    (str "<form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/holiday-policy\">"
         "<h2 class=\"pane-title\">Holiday policy</h2>"
         "<div class=\"field-help\">Controls the default workday/holiday status in Days. Manual overrides can still be made from Month edit.</div>"
         "<input type=\"hidden\" name=\"redirect-to\" value=\"/settings\">"
         "<label class=\"field\"><span class=\"field-label\">Policy</span>"
         "<select name=\"holiday-policy-mode\">"
         "<option value=\"complete-two-day\"" (selected-attr mode "complete-two-day") ">Complete two-day</option>"
         "<option value=\"two-day\"" (selected-attr mode "two-day") ">Two-day</option>"
         "<option value=\"manual\"" (selected-attr mode "manual") ">Manual</option>"
         "</select></label>"
         "<div class=\"field\"><span class=\"field-label\">Default holidays</span>"
         "<span class=\"field-help\">Used by Complete two-day and Two-day modes.</span>"
         "<div class=\"weekday-options\">"
         (apply str
                (map (fn [weekday]
                       (str "<label><input type=\"checkbox\" name=\"weekday-"
                            weekday "\" value=\"1\"" (checked-attr weekdays weekday)
                            "> " (get labels weekday) "</label>"))
                     (range 1 8)))
         "</div></div><button type=\"submit\">Save</button></form>")))

(defn- calendar-settings-form [settings]
  (let [week-start-day (:week-start-day settings)
        fiscal-month-start-day (:fiscal-month-start-day settings)]
    (str "<form class=\"input-panel settings-form\" method=\"post\" action=\"/settings/calendar\">"
         "<h2 class=\"pane-title\">Calendar</h2>"
         "<div class=\"field-help\">Controls Week/Month alignment and the company accounting period shown in Days.</div>"
         "<input type=\"hidden\" name=\"redirect-to\" value=\"/settings\">"
         "<label class=\"field\"><span class=\"field-label\">Week starts on</span>"
         "<select name=\"week-start-day\">"
         (apply str
                (map (fn [weekday]
                       (str "<option value=\"" weekday "\""
                            (when (= weekday week-start-day) " selected")
                            ">" (weekday-labels weekday) "</option>"))
                     (range 1 8)))
         "</select></label>"
         "<label class=\"field\"><span class=\"field-label\">Fiscal month start day</span>"
         "<span class=\"field-help\">Use 21 for a period from the previous 21st through the current 20th.</span>"
         "<input name=\"fiscal-month-start-day\" type=\"number\" min=\"1\" max=\"31\" value=\""
         (escape-html fiscal-month-start-day)
         "\"></label>"
         "<button type=\"submit\">Save</button></form>")))

(defn- break-rule-card [rule]
  (str "<article class=\"break-rule-card\" data-break-rule-id=\"" (escape-html (:id rule)) "\">"
       "<form class=\"break-rule-edit-form\" method=\"post\" action=\"/break-rules/"
       (escape-html (:id rule)) "/update\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/settings\">"
       "<label class=\"field\"><span class=\"field-label\">Title</span>"
       "<input name=\"break-title\" value=\"" (escape-html (:title rule)) "\"></label>"
       "<label class=\"field\"><span class=\"field-label\">Start</span>"
       "<input type=\"time\" name=\"start-time\" value=\"" (escape-html (time-string (:start-minute rule))) "\"></label>"
       "<label class=\"field\"><span class=\"field-label\">End</span>"
       "<input type=\"time\" name=\"end-time\" value=\"" (escape-html (time-string (:end-minute rule))) "\"></label>"
       "<button type=\"submit\">Save</button></form>"
       "<form class=\"delete-form\" method=\"post\" action=\"/break-rules/"
       (escape-html (:id rule)) "/delete\">"
       "<input type=\"hidden\" name=\"redirect-to\" value=\"/settings\">"
       "<button type=\"submit\">Delete</button></form>"
       "</article>"))

(defn- break-rules-panel [break-rules]
  (str (daily-break-rule-form "/settings")
       "<section class=\"input-panel break-rule-list-panel\"><h2 class=\"pane-title\">Daily break rules</h2>"
       "<div class=\"field-help\">Edit or delete the default rules used for fixed break materialization.</div>"
       (if (seq break-rules)
         (str "<div class=\"break-rule-cards\">" (apply str (map break-rule-card break-rules)) "</div>")
         "<p class=\"state\">No daily break rules yet.</p>")
       "</section>"))

(defn- import-source-row [source]
  (str "<tr><td>" (escape-html (:name source))
       "</td><td>" (escape-html (name (:kind source)))
       "</td><td>" (escape-html (:uri source))
       "</td><td>" (escape-html (:fetch-interval-minutes source))
       "</td><td>" (escape-html (if (:enabled? source) "enabled" "disabled"))
       "</td><td>"
       "<form method=\"post\" action=\"/import-sources/" (escape-html (:id source)) "/fetch\">"
       "<button type=\"submit\">Fetch</button></form>"
       "</td></tr>"))

(defn- import-sources-panel [sources]
  (str "<section class=\"input-panel import-sources-panel\">"
       "<form class=\"settings-form\" method=\"post\" action=\"/import-sources\">"
       "<h2 class=\"pane-title\">Add iCal source</h2>"
       "<div class=\"field-help\">Import sources use a shared provider format. iCal is the first implemented source.</div>"
       "<input type=\"hidden\" name=\"kind\" value=\"ical\">"
       "<label class=\"field\"><span class=\"field-label\">Name</span>"
       "<input name=\"name\" placeholder=\"Name\"></label>"
       "<label class=\"field\"><span class=\"field-label\">URI</span>"
       "<input name=\"uri\" placeholder=\"File path or URL\"></label>"
       "<label class=\"field\"><span class=\"field-label\">Fetch interval minutes</span>"
       "<input name=\"fetch-interval-minutes\" type=\"number\" min=\"1\" value=\"60\"></label>"
       "<button type=\"submit\">Add source</button></form>"
       "<table><thead><tr><th>Name</th><th>Kind</th><th>URI</th><th>Interval</th><th>Status</th><th>Fetch</th></tr></thead><tbody>"
       (if (seq sources)
         (apply str (map import-source-row sources))
         "<tr><td colspan=\"6\" class=\"state\">No import sources yet.</td></tr>")
       "</tbody></table></section>"))

(defn settings-page [{:keys [break-rules import-sources settings] :as state}]
  (page "worklog-timeblock settings"
        (str "<main class=\"home settings-page\"><h1>Settings</h1>"
             "<nav class=\"settings-nav\"><a class=\"nav-button\" href=\"/\">Days</a></nav>"
             (flash-warning (:flash-warning state))
             "<div class=\"settings-grid\">"
             (break-mode-settings-form settings)
             (holiday-policy-form settings)
             (calendar-settings-form settings)
             (break-rules-panel break-rules)
             (import-sources-panel import-sources)
             "</div></main>")))
