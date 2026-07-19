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
       ".home{max-width:880px;margin:40px auto;padding:0 24px;}.day-workspace{min-height:100vh;display:grid;grid-template-rows:auto minmax(0,1fr);}"
       ".workspace-header{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;padding:18px 28px;border-bottom:1px solid var(--line);background:var(--surface);}"
       ".workspace-header h1{font-size:22px;line-height:1.2;margin:0;letter-spacing:0;}.workspace-meta{color:var(--muted);font-size:13px;}"
       ".header-actions{display:grid;gap:8px;justify-items:end;}.day-navigation{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:flex-end;}.nav-button{display:inline-flex;align-items:center;min-height:34px;padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:#fff;color:var(--text);text-decoration:none;}.day-navigation form{display:flex;gap:6px;align-items:center;}.day-navigation input{width:142px;}"
       ".workspace-grid{display:grid;grid-template-columns:minmax(280px,.95fr) minmax(340px,1.25fr) minmax(300px,.85fr);min-height:0;}"
       ".timeline-pane,.entry-pane,.summary-pane{min-width:0;overflow:auto;padding:18px 22px 28px;}.entry-pane,.summary-pane{border-left:1px solid var(--line);background:var(--surface);}"
       ".input-panel{display:grid;gap:10px;margin:0 0 18px;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--surface);}.input-grid{display:grid;grid-template-columns:repeat(2,minmax(120px,1fr));gap:8px;}.input-grid .wide{grid-column:1/-1;}.inline-form{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0;}.inline-form input{min-width:160px;}"
       ".draft-summary-preview{min-height:24px;color:var(--muted);font-size:13px;font-variant-numeric:tabular-nums;}"
       ".day-timeline{display:grid;grid-template-columns:48px minmax(160px,1fr);gap:10px;height:calc(100vh - 142px);min-height:640px;max-height:1080px;}.timeline-hours{position:relative;color:var(--muted);font-size:11px;font-variant-numeric:tabular-nums;}.timeline-hour-label{position:absolute;right:0;transform:translateY(-50%);}"
       ".timeline-track{position:relative;min-height:100%;border:1px solid var(--line);border-radius:8px;background:repeating-linear-gradient(to bottom,#fff 0,#fff calc(100% / 24 - 1px),#e8edf3 calc(100% / 24 - 1px),#e8edf3 calc(100% / 24));touch-action:none;}"
       ".timeline-selection{position:absolute;left:8px;right:8px;border:2px solid #0f766e;background:rgba(15,118,110,.14);border-radius:6px;pointer-events:none;box-shadow:0 0 0 2px rgba(15,118,110,.08);}"
       ".timeline-selection[hidden]{display:none;}.timeline-block{position:absolute;border-radius:6px;padding:5px 7px;overflow:hidden;font-size:12px;line-height:1.2;border:1px solid transparent;}"
       ".confirmed-block{left:8px;width:66%;background:#dbeafe;border-color:#93c5fd;color:#172554;cursor:pointer;}.confirmed-block.selected{outline:2px solid #1d4ed8;outline-offset:2px;}.imported-block{left:12%;width:78%;background:rgba(15,118,110,.12);border-color:rgba(15,118,110,.34);color:#064e3b;cursor:context-menu;}.overlap-block{left:76%;width:20%;padding-inline:4px;background:rgba(154,52,18,.12);border-color:rgba(154,52,18,.45);}"
       ".block-time{font-variant-numeric:tabular-nums;font-weight:650;}.block-title{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}.candidate-menu{position:fixed;z-index:20;width:min(320px,calc(100vw - 24px));padding:12px;border:1px solid var(--line);border-radius:8px;background:#fff;box-shadow:0 12px 30px rgba(23,32,42,.18);}.candidate-menu[hidden]{display:none;}.candidate-menu form{display:grid;gap:8px;margin-top:8px;}"
       ".attention-queue{display:grid;gap:8px;margin-bottom:18px;}.candidate-card{border:1px solid var(--line);border-radius:8px;padding:10px;background:#fbfcfd;}.candidate-card.covered{border-color:rgba(154,52,18,.45);}.candidate-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px;}.candidate-actions form{display:flex;gap:6px;align-items:center;}.candidate-badge{font-size:11px;color:var(--warn);text-transform:uppercase;}"
       ".pane-title{font-size:15px;margin:0 0 14px;}.work-log-list{display:grid;gap:10px;}.work-log-row{display:grid;gap:10px;border:1px solid var(--line);border-radius:8px;background:var(--surface);padding:10px;overflow:hidden;}.work-log-row.selected{border-color:#1d4ed8;box-shadow:0 0 0 2px rgba(29,78,216,.14);}.work-log-main{display:grid;grid-template-columns:112px minmax(0,1fr) 92px;gap:10px;align-items:center;}.work-log-actions{display:grid;grid-template-columns:minmax(0,1fr);gap:8px;align-items:start;}.work-log-category-label{font-size:12px;color:var(--muted);}"
       ".time-range{font-variant-numeric:tabular-nums;font-weight:650;}.title{min-width:0;overflow-wrap:anywhere;}.state{color:var(--muted);}.state-excluded{opacity:.66;}.controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;min-width:0;}.controls select{min-width:0;max-width:100%;}.category-form select{flex:1 1 180px;}.range-form{display:flex;flex-wrap:wrap;gap:6px;align-items:center;min-width:0;}.range-form input{width:92px;}.exclude-form button{border-color:#747b86;background:#747b86;}"
       ".category-list{display:grid;gap:4px;list-style:none;margin:0 0 18px;padding:0;}.category-row{display:grid;grid-template-columns:minmax(0,1fr) 48px auto;gap:8px;align-items:center;border:1px solid var(--line);border-left:5px solid var(--group-color,var(--line));border-radius:6px;padding:7px 8px;background:#fff;}.category-child{margin-left:20px;}.category-row .controls{justify-content:flex-end;}.category-row button{padding:4px 8px;}.summary-row{border-left:5px solid var(--group-color,var(--line));}.summary-child td:first-child{padding-left:22px;}.summary-parent td:first-child{font-weight:700;}"
       ".warn{color:var(--warn);font-weight:650;}.warnings{padding-left:18px;}@media (max-width:980px){.workspace-header{align-items:flex-start;flex-direction:column;}.header-actions{justify-items:start;}.day-navigation{justify-content:flex-start;}.workspace-grid{grid-template-columns:1fr;}.entry-pane,.summary-pane{border-left:0;border-top:1px solid var(--line);}.day-timeline{height:720px;}.work-log-main,.work-log-actions,.input-grid{grid-template-columns:1fr;}.range-form input{width:100%;}.controls,.range-form,.inline-form{align-items:stretch;}.controls form,.range-form,.inline-form input,.inline-form button{width:100%;}.controls select,.controls button,.range-form input,.range-form button{width:100%;}}"
       "</style>"
       "</head><body>" body "</body></html>"))

(defn home-page [dates]
  (page "worklog-timeblock"
        (str "<main class=\"home\"><h1>worklog-timeblock</h1>"
             "<form class=\"inline-form\" method=\"post\" action=\"/days\">"
             "<input type=\"date\" name=\"date\" value=\"2026-07-06\">"
             "<button type=\"submit\">Open day</button></form>"
             "<p><a href=\"/import-sources\">Import sources</a></p>"
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
  (or (get-in categories [category-id :name]) category-id "(uncategorized)"))

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
  (str "<div class=\"timeline-block confirmed-block\" style=\""
       (escape-html (block-style (:start-minute log) (:end-minute log)))
       "\" data-worklog-id=\"" (escape-html (:id log))
       "\" data-start-minute=\"" (escape-html (:start-minute log))
       "\" data-end-minute=\"" (escape-html (:end-minute log)) "\">"
       "<div class=\"block-time\">" (escape-html (str (time-string (:start-minute log))
                                                      "-"
                                                      (time-string (:end-minute log))))
       "</div><div class=\"block-title\">" (escape-html (:title log))
       "</div></div>"))

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
       "<input class=\"wide\" name=\"title\" placeholder=\"Title\">"
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
  (str "<form class=\"input-panel\" method=\"post\" action=\"/categories\">"
       "<h2 class=\"pane-title\">Add category</h2>"
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

(defn- category-management-row [date category]
  (str "<li class=\"category-row category-"
       (if (:parent-id category) "child" "root")
       "\" style=\"" (escape-html (group-style category)) "\">"
       "<span>" (escape-html (:name category)) "</span>"
       "<span class=\"state\">" (if (:parent-id category) "child" "root") "</span>"
       "<div class=\"controls\">"
       (move-category-form date category "up" "Up")
       (move-category-form date category "down" "Down")
       "</div></li>"))

(defn- category-management-list [date categories]
  (str "<h2 class=\"pane-title\">Categories</h2>"
       "<ul class=\"category-list\">"
       (apply str (map #(category-management-row date %) categories))
       "</ul>"))

(defn- work-log-row [categories categories-map log]
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
         "</div></div><div class=\"work-log-actions\"><form class=\"category-form controls\" method=\"post\" action=\"/worklogs/"
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
         "</div><div class=\"work-log-category-label\">"
         (escape-html (category-name categories-map (:category-id log)))
         "</div></article>")))

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
         (root-categories categories))
        orphan-hours (keep (fn [[category-id hours]]
                             (when (and (pos? (double hours))
                                        (not (contains? known-ids category-id)))
                               {:category {:id category-id
                                           :name (category-name categories-map category-id)}
                                :hours hours
                                :row-kind :root}))
                           (:category-hours summary))]
    (concat ordered-rows orphan-hours)))

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

(defn- day-timeline [date work-logs source-events]
  (str "<div class=\"day-timeline\" data-date=\"" (escape-html date) "\">"
       "<div class=\"timeline-hours\">" (timeline-hour-labels) "</div>"
       "<div class=\"timeline-track\" data-minute-quantum=\"15\">"
       "<div class=\"timeline-selection\" hidden></div>"
       (apply str (map timeline-confirmed-block
                       (filter #(= :confirmed (:state %)) work-logs)))
       (apply str (map #(timeline-imported-block work-logs %) source-events))
       "</div></div>"))

(def timeline-script
  "<script>
(function(){
  const form = document.getElementById('new-work-log-form');
  const track = document.querySelector('.timeline-track');
  const entryPane = document.querySelector('.entry-pane');
  const selection = document.querySelector('.timeline-selection');
  const preview = document.getElementById('draft-summary-preview');
  const menu = document.getElementById('candidate-menu');
  if (!form || !track || !entryPane || !selection || !preview || !menu) return;
  const startInput = form.querySelector(\"input[name='start-time']\");
  const endInput = form.querySelector(\"input[name='end-time']\");
  const categorySelect = form.querySelector(\"select[name='category-id']\");
  const titleInput = form.querySelector(\"input[name='title']\");
  const submitButton = form.querySelector(\"button[type='submit']\");
  const quantum = Number(track.dataset.minuteQuantum || 15);
  let dragging = false;
  let dragStart = null;
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
  function hideMenu(){ menu.hidden = true; }
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
    if (event.target.closest('.confirmed-block')) return;
    hideMenu();
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
  document.querySelectorAll('.confirmed-block').forEach(function(block){
    block.addEventListener('click', function(event){
      if (event.button !== 0) return;
      hideMenu();
      selectWorkLog(block.dataset.worklogId);
      event.stopPropagation();
    });
  });
  document.querySelectorAll('.imported-block').forEach(function(block){
    block.addEventListener('contextmenu', function(event){
      event.preventDefault();
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
  });
  updateFromInputs();
})();
</script>")

(defn day-page [{:keys [date work-logs source-events summary]} categories]
  (let [categories-map (categories-by-id categories)]
    (page (str "worklog-timeblock " date)
          (str "<main class=\"day-workspace\">"
               "<header class=\"workspace-header\"><div><h1>" (escape-html date)
               "</h1><div class=\"workspace-meta\">" (count work-logs) " logs</div></div>"
               "<div class=\"header-actions\">"
               (day-navigation date)
               "<nav><a href=\"/\">Days</a> | <a href=\"/import-sources\">Import sources</a></nav>"
               "</div></header>"
               "<div class=\"workspace-grid\">"
               "<section class=\"timeline-pane\"><h2 class=\"pane-title\">Timeline</h2>"
               (day-timeline date work-logs source-events)
               (candidate-menu categories)
               "</section>"
               "<section class=\"entry-pane\">"
               (new-work-log-form date categories)
               (attention-queue categories work-logs source-events)
               "<div class=\"work-log-list\">"
               (apply str (map #(work-log-row categories categories-map %) work-logs))
               "</div></section>"
               "<aside class=\"summary-pane\"><h2 class=\"pane-title\">Category totals</h2>"
               (new-category-form date categories)
               (category-management-list date categories)
               "<table><thead><tr><th>Category</th><th>Hours</th></tr></thead><tbody>"
               (let [rows (category-summary-rows categories summary)]
                 (if (seq rows)
                   (apply str (map summary-row rows))
                   "<tr><td colspan=\"2\" class=\"state\">No category totals yet.</td></tr>"))
               "</tbody></table>"
               (when (seq (:warnings summary))
                 (str "<h2 class=\"pane-title\">Warnings</h2><ul class=\"warnings\">"
                      (apply str (map warning-item (:warnings summary)))
                      "</ul>"))
               "</aside></div></main>"
               timeline-script))))

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

(defn import-sources-page [sources]
  (page "worklog-timeblock import sources"
        (str "<main class=\"home\"><h1>Import sources</h1>"
             "<p><a href=\"/\">Days</a></p>"
             "<form class=\"input-panel\" method=\"post\" action=\"/import-sources\">"
             "<h2 class=\"pane-title\">Add iCal source</h2>"
             "<input type=\"hidden\" name=\"kind\" value=\"ical\">"
             "<input name=\"name\" placeholder=\"Name\">"
             "<input name=\"uri\" placeholder=\"File path or URL\">"
             "<input name=\"fetch-interval-minutes\" type=\"number\" min=\"1\" value=\"60\">"
             "<button type=\"submit\">Add source</button></form>"
             "<table><thead><tr><th>Name</th><th>Kind</th><th>URI</th><th>Interval</th><th>Status</th><th>Fetch</th></tr></thead><tbody>"
             (apply str (map import-source-row sources))
             "</tbody></table></main>")))
