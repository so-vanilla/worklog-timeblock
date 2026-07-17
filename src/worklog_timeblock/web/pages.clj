(ns worklog-timeblock.web.pages
  (:require [clojure.string :as str]))

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
       ".home{max-width:880px;margin:40px auto;padding:0 24px;}.day-workspace{min-height:100vh;display:grid;grid-template-rows:auto minmax(0,1fr);}"
       ".workspace-header{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;padding:18px 28px;border-bottom:1px solid var(--line);background:var(--surface);}"
       ".workspace-header h1{font-size:22px;line-height:1.2;margin:0;letter-spacing:0;}.workspace-meta{color:var(--muted);font-size:13px;}"
       ".workspace-grid{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(320px,.85fr);min-height:0;}"
       ".timeline-pane{min-width:0;overflow:auto;padding:22px 28px 32px;}.summary-pane{min-width:0;overflow:auto;padding:22px 28px 32px;border-left:1px solid var(--line);background:var(--surface);}"
       ".pane-title{font-size:15px;margin:0 0 14px;}.work-log-list{display:grid;gap:10px;}.work-log-row{display:grid;grid-template-columns:116px minmax(140px,1fr) 96px minmax(120px,.8fr) minmax(220px,1.15fr) minmax(246px,1.35fr) 92px;gap:10px;align-items:center;border:1px solid var(--line);border-radius:8px;background:var(--surface);padding:10px;}"
       ".time-range{font-variant-numeric:tabular-nums;font-weight:650;}.title{min-width:0;overflow-wrap:anywhere;}.state{color:var(--muted);}.state-excluded{opacity:.66;}.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;}.range-form{display:flex;gap:6px;align-items:center;}.range-form input{width:92px;}.exclude-form button{border-color:#747b86;background:#747b86;}"
       ".manual-entry-output{white-space:pre-wrap;min-height:96px;margin:10px 0 18px;padding:12px;border:1px solid var(--line);border-radius:8px;background:#f9fafb;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;line-height:1.5;}"
       ".warn{color:var(--warn);font-weight:650;}.warnings{padding-left:18px;}@media (max-width:980px){.workspace-grid{grid-template-columns:1fr;}.summary-pane{border-left:0;border-top:1px solid var(--line);}.work-log-row{grid-template-columns:1fr;}.range-form input{width:100%;}.controls,.range-form{align-items:stretch;}.controls form,.range-form{width:100%;}.controls select,.controls button,.range-form input,.range-form button{width:100%;}}"
       "</style>"
       "</head><body>" body "</body></html>"))

(defn home-page [dates]
  (page "worklog-timeblock"
        (str "<main class=\"home\"><h1>worklog-timeblock</h1>"
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
             "<p><a href=\"/days/2026-07-06\">/days/2026-07-06</a></p></main>")))

(defn- categories-by-id [categories]
  (into {} (map (juxt :id identity)) categories))

(defn- category-name [categories category-id]
  (or (get-in categories [category-id :name]) category-id "(uncategorized)"))

(defn- time-string [minute]
  (format "%02d:%02d" (quot minute 60) (mod minute 60)))

(defn- category-option [selected-id category]
  (let [id (:id category)]
    (str "<option value=\"" (escape-html id) "\""
         (when (= id selected-id) " selected")
         ">" (escape-html (:name category)) "</option>")))

(defn- category-select [categories selected-id]
  (str "<select name=\"category-id\">"
       "<option value=\"\">Uncategorized</option>"
       (apply str (map #(category-option selected-id %) categories))
       "</select>"))

(defn- work-log-row [categories categories-map log]
  (let [id (:id log)
        state-name (name (:state log))]
    (str "<article class=\"work-log-row state-" (escape-html state-name)
         "\" data-worklog-id=\"" (escape-html id) "\">"
         "<div class=\"time-range\">" (escape-html (str (time-string (:start-minute log))
                                                        "-"
                                                        (time-string (:end-minute log))))
         "</div><div class=\"title\">" (escape-html (:title log))
         "</div><div class=\"state\">" (escape-html state-name)
         "</div><form class=\"category-form controls\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/assign-category\">"
         (category-select categories (:category-id log))
         "<button type=\"submit\">Set</button></form>"
         "<form class=\"range-form\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/range\">"
         "<input type=\"time\" name=\"start-time\" value=\"" (escape-html (time-string (:start-minute log))) "\">"
         "<input type=\"time\" name=\"end-time\" value=\"" (escape-html (time-string (:end-minute log))) "\">"
         "<button type=\"submit\">Range</button></form>"
         "<form class=\"exclude-form\" method=\"post\" action=\"/worklogs/"
         (escape-html id) "/exclude\"><button type=\"submit\">Exclude</button></form>"
         "<div class=\"state\">"
         (escape-html (category-name categories-map (:category-id log)))
         "</div></article>")))

(defn- summary-row [categories [category-id hours]]
  (str "<tr><td>" (escape-html (category-name categories category-id))
       "</td><td>" (format "%.2fh" (double hours))
       "</td></tr>"))

(defn- sorted-summary-items [categories summary]
  (sort-by (fn [[category-id _]]
             (category-name categories category-id))
           (:category-hours summary)))

(defn- manual-entry-output [categories summary]
  (let [lines (map (fn [[category-id hours]]
                     (str (category-name categories category-id)
                          "\t"
                          (format "%.2fh" (double hours))))
                   (sorted-summary-items categories summary))]
    (if (seq lines)
      (str/join "\n" lines)
      "No confirmed work.")))

(defn- warning-item [warning]
  (case (:type warning)
    :uncategorized (str "<li class=\"warn\">Uncategorized: "
                        (escape-html (:title warning))
                        "</li>")
    :large-gap (str "<li class=\"warn\">Large gap: "
                    (escape-html (:minutes warning))
                    " minutes</li>")
    (str "<li class=\"warn\">" (escape-html warning) "</li>")))

(defn day-page [{:keys [date work-logs summary]} categories]
  (let [categories-map (categories-by-id categories)]
    (page (str "worklog-timeblock " date)
          (str "<main class=\"day-workspace\">"
               "<header class=\"workspace-header\"><div><h1>" (escape-html date)
               "</h1><div class=\"workspace-meta\">" (count work-logs) " logs</div></div>"
               "<a href=\"/\">Days</a></header>"
               "<div class=\"workspace-grid\">"
               "<section class=\"timeline-pane\"><h2 class=\"pane-title\">Timeline</h2>"
               "<div class=\"work-log-list\">"
               (apply str (map #(work-log-row categories categories-map %) work-logs))
               "</div></section>"
               "<aside class=\"summary-pane\"><h2 class=\"pane-title\">Category totals</h2>"
               "<table><thead><tr><th>Category</th><th>Hours</th></tr></thead><tbody>"
               (apply str (map #(summary-row categories-map %)
                               (sorted-summary-items categories-map summary)))
               "</tbody></table>"
               "<h2 class=\"pane-title\">Manual entry</h2>"
               "<pre class=\"manual-entry-output\">" (escape-html (manual-entry-output categories-map summary)) "</pre>"
               (when (seq (:warnings summary))
                 (str "<h2 class=\"pane-title\">Warnings</h2><ul class=\"warnings\">"
                      (apply str (map warning-item (:warnings summary)))
                      "</ul>"))
               "</aside></div></main>"))))
