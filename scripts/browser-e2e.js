#!/usr/bin/env node

const childProcess = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const repo = path.resolve(__dirname, "..");
const playwrightPath = process.env.PLAYWRIGHT_CORE_PATH;

if (!playwrightPath) {
  throw new Error("PLAYWRIGHT_CORE_PATH is required");
}

const { chromium } = require(playwrightPath);

let assertions = 0;

function assert(condition, message) {
  assertions += 1;
  if (!condition) {
    throw new Error(message);
  }
}

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function requestJson(baseUrl, method, uri, body) {
  const response = await fetch(`${baseUrl}${uri}`, {
    method,
    headers: { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : {};
  return { status: response.status, body: json };
}

async function waitForHealth(baseUrl, server) {
  for (let i = 0; i < 80; i += 1) {
    if (server.exitCode !== null) {
      throw new Error(`server exited early with code ${server.exitCode}`);
    }
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) {
        return;
      }
    } catch (_) {
      // Server is still starting.
    }
    await sleep(250);
  }
  throw new Error("server did not become healthy");
}

async function ensureChromium() {
  try {
    const browser = await chromium.launch({ headless: true });
    await browser.close();
  } catch (error) {
    if (!String(error.message).includes("Executable doesn't exist")) {
      throw error;
    }
    childProcess.execFileSync(process.execPath, [path.join(playwrightPath, "cli.js"), "install", "chromium"], {
      cwd: repo,
      stdio: "inherit",
    });
  }
}

async function seed(baseUrl) {
  const dev = await requestJson(baseUrl, "POST", "/api/categories", { name: "Development" });
  const meetings = await requestJson(baseUrl, "POST", "/api/categories", { name: "Meetings" });
  const engineering = await requestJson(baseUrl, "POST", "/api/categories", { name: "Engineering" });
  assert(engineering.status === 200, "engineering parent category should be created");
  const frontend = await requestJson(baseUrl, "POST", "/api/categories", {
    name: "Frontend",
    "parent-id": engineering.body.id,
  });
  const backend = await requestJson(baseUrl, "POST", "/api/categories", {
    name: "Backend",
    "parent-id": engineering.body.id,
  });
  assert(dev.status === 200, "development category should be created");
  assert(meetings.status === 200, "meetings category should be created");
  assert(frontend.status === 200, "frontend child category should be created");
  assert(backend.status === 200, "backend child category should be created");

  const worklog = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
    title: "Build",
    "start-minute": 540,
    "end-minute": 600,
    "category-id": dev.body.id,
  });
  assert(worklog.status === 200, "confirmed worklog should be created");
  const attendance = await requestJson(baseUrl, "PUT", "/api/days/2026-07-06/attendance", {
    "clock-in-minute": 540,
    "clock-out-minute": 1080,
  });
  assert(attendance.status === 200, "attendance should be stored");
  const breakRule = await requestJson(baseUrl, "POST", "/api/break-rules", {
    title: "Lunch",
    "start-minute": 720,
    "end-minute": 780,
    enabled: true,
  });
  assert(breakRule.status === 200, "daily break rule should be stored");
  const childWorklog = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
    title: "Backend plan",
    "start-minute": 780,
    "end-minute": 840,
    "category-id": backend.body.id,
  });
  assert(childWorklog.status === 200, "child category worklog should be created");
  let deepWorklogId = childWorklog.body.id;
  for (let i = 0; i < 8; i += 1) {
    const start = 870 + i * 45;
    const extra = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
      title: `Focus ${i + 1}`,
      "start-minute": start,
      "end-minute": start + 30,
      "category-id": dev.body.id,
    });
    assert(extra.status === 200, `extra worklog ${i + 1} should be created`);
    if (i === 4) {
      deepWorklogId = extra.body.id;
    }
  }
  const boundaryLeft = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
    title: "Boundary left",
    "start-minute": 1260,
    "end-minute": 1290,
    "category-id": dev.body.id,
  });
  const boundaryRight = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
    title: "Boundary right",
    "start-minute": 1290,
    "end-minute": 1320,
    "category-id": dev.body.id,
  });
  assert(boundaryLeft.status === 200, "left boundary worklog should be created");
  assert(boundaryRight.status === 200, "right boundary worklog should be created");

  const imported = await requestJson(baseUrl, "POST", "/api/candidates/import", {
    events: [
      {
        "source-id": "browser",
        "external-id": "evt-candidate",
        title: "Candidate block",
        "starts-at": "2026-07-06T10:00+09:00",
        "ends-at": "2026-07-06T10:30+09:00",
        timezone: "Asia/Tokyo",
        "updated-at": "2026-07-06T00:00:00Z",
      },
      {
        "source-id": "browser",
        "external-id": "evt-overlap",
        title: "Overlap candidate",
        "starts-at": "2026-07-06T09:30+09:00",
        "ends-at": "2026-07-06T10:30+09:00",
        timezone: "Asia/Tokyo",
        "updated-at": "2026-07-06T00:05:00Z",
      },
    ],
  });
  assert(imported.status === 200, "candidate import should succeed");
  assert(imported.body["source-events-upserted"] === 2, "two source events should be stored");

  return {
    devId: dev.body.id,
    meetingsId: meetings.body.id,
    engineeringId: engineering.body.id,
    frontendId: frontend.body.id,
    backendId: backend.body.id,
    deepWorklogId,
    buildId: worklog.body.id,
    boundaryLeftId: boundaryLeft.body.id,
    boundaryRightId: boundaryRight.body.id,
  };
}

async function timelineBox(page) {
  const box = await page.locator(".timeline-track").boundingBox();
  assert(Boolean(box), "timeline track should have a box");
  return box;
}

function yForMinute(box, minute) {
  return box.y + (box.height * minute) / 1440;
}

async function dragConfirmedBlock(page, selector, edge, targetMinute, options = {}) {
  const block = page.locator(selector).first();
  const blockBox = await block.boundingBox();
  assert(Boolean(blockBox), `${selector} should have a block box`);
  const trackBox = await timelineBox(page);
  const x = blockBox.x + blockBox.width / 2;
  const y = edge === "top"
    ? blockBox.y + 2
    : edge === "bottom"
      ? blockBox.y + blockBox.height - 2
      : blockBox.y + blockBox.height / 2;
  if (options.shift) {
    await page.keyboard.down("Shift");
  }
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, yForMinute(trackBox, targetMinute));
  await page.mouse.up();
  if (options.shift) {
    await page.keyboard.up("Shift");
  }
}

async function waitForBlockText(page, selector, expected) {
  await page.waitForFunction(
    ({ selector: blockSelector, expected: expectedText }) =>
      document.querySelector(blockSelector)?.textContent.includes(expectedText),
    { selector, expected },
  );
}

async function centerOf(locator) {
  const box = await locator.boundingBox();
  assert(Boolean(box), "locator should have a box");
  return { x: box.x + box.width / 2, y: box.y + box.height / 2, box };
}

async function run() {
  await ensureChromium();

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "worklog-timeblock-browser-"));
  const dbPath = path.join(tmp, "app.db");
  const port = 3300 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;
  const server = childProcess.spawn("clojure", [
    "-M:run",
    "--db",
    dbPath,
    "--host",
    "127.0.0.1",
    "--port",
    String(port),
  ], {
    cwd: repo,
    env: {
      ...process.env,
      WORKLOG_TIMEBLOCK_IMPORTER_SCHEDULER: "0",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  const logs = [];
  server.stdout.on("data", (chunk) => logs.push(chunk.toString()));
  server.stderr.on("data", (chunk) => logs.push(chunk.toString()));

  let browser;
  try {
    await waitForHealth(baseUrl, server);
    const ids = await seed(baseUrl);
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1440, height: 920 } });

    await page.goto(`${baseUrl}/?view=month&date=2026-07-06`);
    assert(await page.locator(".days-calendar[data-calendar-view='month'][data-calendar-edit='inactive']").count() === 1, "month calendar should render in inactive edit mode by default");
    assert(await page.locator(".calendar-day[data-date='2026-07-06'] a[href='/days/2026-07-06']").count() === 1, "inactive month day should link to the day workspace");
    await Promise.all([
      page.waitForURL(`${baseUrl}/days/2026-07-06`),
      page.locator(".calendar-day[data-date='2026-07-06'] a").click(),
    ]);

    await page.goto(`${baseUrl}/?view=week&date=2026-07-06`);
    assert(await page.locator(".week-calendar[data-calendar-view='week']").count() === 1, "week calendar should render");
    assert(await page.locator(".week-day-card[data-date='2026-07-07'] a[href='/days/2026-07-07']").count() === 1, "week day should link to the day workspace");
    await Promise.all([
      page.waitForURL(`${baseUrl}/days/2026-07-07`),
      page.locator(".week-day-card[data-date='2026-07-07'] a").click(),
    ]);

    await page.goto(`${baseUrl}/?view=month&date=2026-07-06&edit=1`);
    assert(await page.locator(".days-calendar[data-calendar-view='month'][data-calendar-edit='active']").count() === 1, "active month edit mode should render");
    const july13 = await centerOf(page.locator(".calendar-day[data-date='2026-07-13']"));
    const july15 = await centerOf(page.locator(".calendar-day[data-date='2026-07-15']"));
    await page.mouse.move(july13.x, july13.y);
    await page.mouse.down();
    await page.mouse.move(july15.x, july15.y);
    await page.mouse.up();
    assert(await page.locator("#range-action-popover:not([hidden])").count() === 1, "month drag should open workday/holiday choices");
    assert(await page.locator("#day-status-range-form input[name='start-date']").inputValue() === "2026-07-13", "drag range should set start date");
    assert(await page.locator("#day-status-range-form input[name='end-date']").inputValue() === "2026-07-15", "drag range should set end date");
    await Promise.all([
      page.waitForNavigation({ waitUntil: "domcontentloaded" }),
      page.locator("#day-status-range-form button[value='holiday']").click(),
    ]);
    assert(await page.locator(".calendar-day.day-status-holiday[data-date='2026-07-13']").count() === 1, "bulk holiday should update first selected date");
    assert(await page.locator(".calendar-day.day-status-holiday[data-date='2026-07-15']").count() === 1, "bulk holiday should update last selected date");

    await page.goto(`${baseUrl}/import-sources`);
    await page.waitForURL(`${baseUrl}/settings`);
    assert(await page.locator("form[action='/settings/break-mode']").count() === 1, "settings should own break mode form");
    assert(await page.locator("form[action='/settings/holiday-policy']").count() === 1, "settings should own holiday policy form");
    assert(await page.locator(".import-sources-panel form[action='/import-sources']").count() === 1, "settings should own import source form");
    assert(await page.locator("a[href='/import-sources']").count() === 0, "standalone import source page should not be linked from settings");

    await page.goto(`${baseUrl}/days/2026-07-06`);

    const ratio = await page.locator(".timeline-pane").evaluate((pane) => {
      const grid = pane.closest(".workspace-grid");
      return pane.getBoundingClientRect().width / grid.getBoundingClientRect().width;
    });
    assert(ratio >= 0.28 && ratio <= 0.36, `timeline width ratio should be about one third, got ${ratio}`);
    assert(await page.locator(".entry-pane").count() === 1, "entry pane should exist");
    assert(await page.locator(".summary-pane").count() === 1, "summary pane should exist");
    const paneScroll = await page.locator(".day-workspace").evaluate(() => {
      const timeline = document.querySelector(".timeline-pane");
      const entry = document.querySelector(".entry-pane");
      const summary = document.querySelector(".summary-pane");
      window.scrollTo(0, 0);
      timeline.scrollTop = 0;
      entry.scrollTop = 0;
      summary.scrollTop = 0;
      entry.scrollTop = Math.min(160, Math.max(0, entry.scrollHeight - entry.clientHeight));
      return {
        bodyScroll: window.scrollY,
        timelineScroll: timeline.scrollTop,
        entryScroll: entry.scrollTop,
        summaryScroll: summary.scrollTop,
        overflow: [timeline, entry, summary].map((pane) => getComputedStyle(pane).overflowY),
      };
    });
    assert(paneScroll.overflow.every((value) => value === "auto"), `workspace panes should have independent auto overflow: ${JSON.stringify(paneScroll)}`);
    assert(paneScroll.entryScroll > 0, "entry pane should scroll independently when it overflows");
    assert(paneScroll.timelineScroll === 0, "entry pane scrolling should not move timeline pane");
    assert(paneScroll.summaryScroll === 0, "entry pane scrolling should not move summary pane");
    assert(paneScroll.bodyScroll === 0, "workspace pane scrolling should not scroll the body");
    assert(await page.locator(".day-navigation").count() === 1, "day navigation should exist");
    assert(await page.locator(".day-navigation a[href='/days/2026-07-05']").count() === 1, "previous day link should exist");
    assert(await page.locator(".day-navigation a[href='/days/2026-07-07']").count() === 1, "next day link should exist");
    assert(await page.locator(".day-navigation input[name='date']").inputValue() === "2026-07-06", "goto date should default to current day");
    assert(await page.locator(".manual-entry-output").count() === 0, "manual entry output should be removed");
    assert(await page.locator("a[href='/settings']").count() >= 1, "settings link should exist on day page");
    assert(await page.locator(".attendance-panel").count() === 1, "attendance panel should exist");
    assert((await page.locator(".attendance-panel").textContent()).includes("09:00-18:00"), "attendance panel should show clock range");
    assert((await page.locator(".attendance-panel").textContent()).includes("Unallocated"), "attendance panel should show unallocated time");
    assert(await page.locator("form[action='/days/2026-07-06/attendance/clock-in-now']").count() === 1, "clock-in-now form should exist");
    assert(await page.locator("form[action='/days/2026-07-06/attendance/clock-out-now']").count() === 1, "clock-out-now form should exist");
    assert(await page.locator("input[name='clock-in-time']").inputValue() === "09:00", "manual clock-in input should render stored time");
    assert(await page.locator("input[name='clock-out-time']").inputValue() === "18:00", "manual clock-out input should render stored time");
    assert(await page.locator(".attendance-band[data-attendance-start-minute='540'][data-attendance-end-minute='1080']").count() === 1, "attendance should render on timeline");
    assert((await page.locator(".attendance-band").textContent()).includes("09:00-18:00"), "attendance timeline band should show clock range");
    assert(await page.locator("form[action='/break-rules']").count() === 0, "daily break rule form should be moved off the day page");
    assert(await page.locator(".timeline-block.break-block").count() === 1, "break should render on timeline");
    assert((await page.locator(".timeline-block.break-block").textContent()).includes("Lunch"), "break timeline block should show title");
    assert(await page.locator(".break-row form[action*='/range']").count() === 1, "break range form should exist");
    assert(await page.locator(".break-row form[action*='/convert']").count() === 0, "break convert form should not be exposed");
    assert(!(await page.locator(".attendance-panel").textContent()).includes("Convert to work"), "break category conversion should not be visible");
    assert(await page.locator(".summary-pane > .attendance-panel").count() === 1, "attendance should be first right-pane panel");
    const paneOrder = await page.locator(".summary-pane > .input-panel").evaluateAll((panels) =>
      panels.slice(0, 3).map((panel) =>
        panel.classList.contains("attendance-panel")
          ? "attendance"
          : panel.classList.contains("category-totals-panel")
            ? "totals"
            : panel.classList.contains("category-settings-panel")
              ? "categories"
              : "other",
      ),
    );
    assert(JSON.stringify(paneOrder) === JSON.stringify(["attendance", "totals", "categories"]), `right pane order should be attendance, totals, categories: ${paneOrder}`);

    await page.goto(`${baseUrl}/settings`);
    assert(await page.locator("form[action='/break-rules']").count() === 1, "settings page should expose daily break form");
    assert((await page.locator(".break-rule-list-panel").textContent()).includes("Lunch"), "settings page should list existing daily break rule");
    assert((await page.locator(".break-rule-list-panel").textContent()).includes("12:00-13:00"), "settings page should show daily break range");
    await page.goto(`${baseUrl}/days/2026-07-06`);

    const categoryRows = await page.locator(".category-list .category-row").evaluateAll((rows) =>
      rows.map((row) => ({
        name: row.querySelector("input[name='category-name']")?.value || "",
        isRoot: row.classList.contains("category-root"),
        isChild: row.classList.contains("category-child"),
        left: row.getBoundingClientRect().left,
        borderLeftColor: getComputedStyle(row).borderLeftColor,
      })),
    );
    const engineeringRow = categoryRows.find((row) => row.name === "Engineering");
    const backendRow = categoryRows.find((row) => row.name === "Backend");
    assert(Boolean(engineeringRow), "category list should include parent category");
    assert(Boolean(backendRow), "category list should include child category");
    assert(engineeringRow.isRoot, "parent category row should use root styling");
    assert(backendRow.isChild, "child category row should use child styling");
    assert(!categoryRows.some((row) => row.name === "Engineering / Backend"), "child category should not repeat parent name");
    assert(backendRow.left > engineeringRow.left + 12, "child category should be indented");
    const childBorderColor = backendRow.borderLeftColor;
    assert(childBorderColor !== "rgb(215, 221, 229)", "child category should use a group color");

    const parentSummary = page.locator(`tr[data-summary-category-id='${ids.engineeringId}']`);
    const childSummary = page.locator(`tr[data-summary-category-id='${ids.backendId}']`);
    assert(await parentSummary.count() === 1, "parent category subtotal row should render");
    assert((await parentSummary.textContent()).includes("1.00h"), "parent category subtotal should include child hours");
    assert(await childSummary.count() === 1, "child category total row should render");
    const summaryOrder = await page.locator("tr[data-summary-category-id]").evaluateAll((rows) =>
      rows.map((row) => row.getAttribute("data-summary-category-id")),
    );
    assert(
      summaryOrder.indexOf(String(ids.engineeringId)) < summaryOrder.indexOf(String(ids.backendId)),
      "summary order should put parent before child",
    );
    assert(await page.locator(".work-log-row form[data-auto-submit='category']").count() >= 1, "work log category form should auto-submit");
    assert(await page.locator(".work-log-row .category-form button:has-text('Set')").count() === 0, "work log category rows should not show Set buttons");
    assert(await page.locator(".work-log-category-label").count() === 0, "work log rows should not render duplicate category labels");
    const rangeLineGeometry = await page.locator(".work-log-row").first().evaluate((row) => {
      const range = row.querySelector(".range-form").getBoundingClientRect();
      const exclude = row.querySelector(".exclude-form").getBoundingClientRect();
      return { rangeRight: range.right, excludeLeft: exclude.left };
    });
    assert(rangeLineGeometry.excludeLeft >= rangeLineGeometry.rangeRight, "exclude should sit to the right of the range form");
    const rowOverflowDetails = await page.locator(".work-log-row").evaluateAll((rows) =>
      rows
        .map((row) => ({
          id: row.getAttribute("data-worklog-id"),
          width: Math.ceil(row.getBoundingClientRect().width),
          scrollWidth: row.scrollWidth,
        }))
        .filter((row) => row.scrollWidth > row.width + 1),
    );
    assert(
      rowOverflowDetails.length === 0,
      `work log edit rows should not overflow horizontally: ${JSON.stringify(rowOverflowDetails)}`,
    );

    const buildCategorySelect = page.locator(`.work-log-row[data-worklog-id='${ids.buildId}'] form[data-auto-submit='category'] select`);
    await Promise.all([
      page.waitForNavigation({ waitUntil: "domcontentloaded" }),
      buildCategorySelect.selectOption(String(ids.meetingsId)),
    ]);
    assert(
      await page.locator(`.work-log-row[data-worklog-id='${ids.buildId}'] select[name='category-id']`).inputValue() === String(ids.meetingsId),
      "category dropdown change should persist without a Set button",
    );

    const selectedId = String(ids.deepWorklogId);
    assert(await page.locator(".work-log-row.selected").count() === 0, "no work log row should be selected before clicking a block");
    const draftStartBeforeSelection = await page.locator("#new-work-log-form input[name='start-time']").inputValue();
    await page.locator(`.confirmed-block[data-worklog-id='${selectedId}']`).click();
    assert(await page.locator(`.confirmed-block.selected[data-worklog-id='${selectedId}']`).count() === 1, "clicked confirmed block should become selected");
    assert(await page.locator(`.work-log-row.selected[data-worklog-id='${selectedId}']`).count() === 1, "matching work log row should become selected");
    assert(await page.locator(".entry-pane").getAttribute("data-selected-worklog-id") === selectedId, "entry pane should track selected worklog id");
    assert(await page.locator("#new-work-log-form input[name='start-time']").inputValue() === draftStartBeforeSelection, "block click should not rewrite draft start time");
    const selectedGeometry = await page.locator(`.work-log-row.selected[data-worklog-id='${selectedId}']`).evaluate((row) => {
      const pane = row.closest(".entry-pane");
      const paneRect = pane.getBoundingClientRect();
      const rowRect = row.getBoundingClientRect();
      return {
        paneCenter: paneRect.top + paneRect.height / 2,
        rowCenter: rowRect.top + rowRect.height / 2,
        paneHeight: paneRect.height,
      };
    });
    assert(
      Math.abs(selectedGeometry.rowCenter - selectedGeometry.paneCenter) < selectedGeometry.paneHeight * 0.35,
      "selected work log row should be scrolled near the middle of the entry pane",
    );
    const selectedRows = await page.locator(".work-log-row.selected").count();
    assert(selectedRows === 1, "only one work log row should be highlighted");

    let box = await timelineBox(page);
    const draftLaneX = box.x + box.width * 0.98;
    await page.mouse.move(draftLaneX, yForMinute(box, 540));
    await page.mouse.down();
    await page.mouse.move(draftLaneX, yForMinute(box, 600));
    await page.mouse.up();
    assert(await page.locator("#new-work-log-form input[name='start-time']").inputValue() === "09:00", "drag should fill start time");
    assert(await page.locator("#new-work-log-form input[name='end-time']").inputValue() === "10:00", "drag should fill end time");
    assert(await page.locator(".timeline-selection").getAttribute("data-start-minute") === "540", "selection start minute should be tracked");
    assert(await page.locator(".timeline-selection").getAttribute("data-end-minute") === "600", "selection end minute should be tracked");
    assert((await page.locator("#draft-summary-preview").textContent()).includes("Overlaps confirmed work"), "overlapping draft should be visible");
    assert(await page.locator("#new-work-log-form button[type='submit']").isDisabled(), "overlapping draft should disable add");

    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 660));
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 720));
    await page.mouse.up();
    await page.selectOption("#new-work-log-form select[name='category-id']", String(ids.devId));
    assert(await page.locator("#new-work-log-form input[name='start-time']").inputValue() === "11:00", "non-overlap drag should fill start time");
    assert(await page.locator("#new-work-log-form input[name='end-time']").inputValue() === "12:00", "non-overlap drag should fill end time");
    assert(!(await page.locator("#new-work-log-form button[type='submit']").isDisabled()), "non-overlap draft should enable add");
    assert((await page.locator("#draft-summary-preview").textContent()).includes("Development 1.00h"), "draft summary preview should update");

    await page.fill("#new-work-log-form input[name='start-time']", "10:15");
    await page.fill("#new-work-log-form input[name='end-time']", "10:45");
    assert(await page.locator(".timeline-selection").getAttribute("data-start-minute") === "615", "manual start edit should update highlight");
    assert(await page.locator(".timeline-selection").getAttribute("data-end-minute") === "645", "manual end edit should update highlight");

    const candidate = page.locator(".imported-block[data-external-id='evt-candidate']").first();
    await candidate.click({ button: "right" });
    assert(await page.locator("#candidate-menu:not([hidden])").count() === 1, "right click should open candidate menu");
    assert((await page.locator("#candidate-menu").textContent()).includes("Candidate block"), "candidate menu should show candidate title");

    await page.locator("body").click({ position: { x: 5, y: 5 } });
    box = await timelineBox(page);
    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 600));
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 630));
    await page.mouse.up();
    assert(await page.locator("#candidate-menu[hidden]").count() === 1, "dragging over imported block should not open menu");
    assert(await page.locator("#new-work-log-form input[name='start-time']").inputValue() === "10:00", "drag over candidate should start a manual draft");
    assert(await page.locator("#new-work-log-form input[name='end-time']").inputValue() === "10:30", "drag over candidate should size a manual draft");

    assert(await page.locator(".confirmed-block").count() >= 1, "confirmed block should render");
    assert(await page.locator(".imported-block.overlap-block[data-external-id='evt-overlap']").count() === 1, "overlap candidate should render as fallback stripe");
    assert((await page.locator(".attention-queue").textContent()).includes("Overlap candidate"), "overlap candidate should be reachable from attention queue");
    assert((await page.locator(".attention-queue").textContent()).includes("covered"), "attention queue should mark covered candidates");

    const submit = await page.locator(".candidate-card[data-external-id='evt-candidate'] form[action*='/confirm']").count();
    assert(submit === 1, "candidate card should expose confirm action");

    const buildSelector = `.confirmed-block[data-worklog-id='${ids.buildId}']`;
    const buildBlockBox = await page.locator(buildSelector).boundingBox();
    assert(Boolean(buildBlockBox), "build block should have geometry for cursor checks");
    await page.mouse.move(buildBlockBox.x + buildBlockBox.width / 2, buildBlockBox.y + 2);
    assert(await page.locator(buildSelector).evaluate((block) => block.style.cursor) === "ns-resize", "top edge hover should show resize cursor");
    await page.mouse.move(buildBlockBox.x + buildBlockBox.width / 2, buildBlockBox.y + buildBlockBox.height - 2);
    assert(await page.locator(buildSelector).evaluate((block) => block.style.cursor) === "ns-resize", "bottom edge hover should show resize cursor");
    await page.mouse.move(buildBlockBox.x + buildBlockBox.width / 2, buildBlockBox.y + buildBlockBox.height / 2);
    assert(await page.locator(buildSelector).evaluate((block) => block.style.cursor) === "move", "middle hover should show move cursor");

    await dragConfirmedBlock(page, buildSelector, "middle", 630);
    await waitForBlockText(page, buildSelector, "10:00-11:00");
    assert((await page.locator(buildSelector).textContent()).includes("10:00-11:00"), "middle drag should move a confirmed block");

    await dragConfirmedBlock(page, buildSelector, "top", 585);
    await waitForBlockText(page, buildSelector, "09:45-11:00");
    assert((await page.locator(buildSelector).textContent()).includes("09:45-11:00"), "top edge drag should resize start time");

    await dragConfirmedBlock(page, buildSelector, "bottom", 645, { shift: true });
    await waitForBlockText(page, buildSelector, "09:45-10:45");
    assert((await page.locator(buildSelector).textContent()).includes("09:45-10:45"), "shift bottom edge drag should shrink the block");

    await page.locator(buildSelector).click();
    box = await timelineBox(page);
    const selectedBuildBox = await page.locator(buildSelector).boundingBox();
    assert(Boolean(selectedBuildBox), "selected build block should have geometry");
    const outsideSelectedLaneX = box.x + box.width * 0.96;
    await page.mouse.move(outsideSelectedLaneX, selectedBuildBox.y + selectedBuildBox.height - 1);
    await page.mouse.down();
    await page.mouse.move(outsideSelectedLaneX, yForMinute(box, 630));
    await page.mouse.up();
    await waitForBlockText(page, buildSelector, "09:45-10:30");
    assert((await page.locator(buildSelector).textContent()).includes("09:45-10:30"), "selected boundary drag should resize the selected block even outside its lane");

    const leftSelector = `.confirmed-block[data-worklog-id='${ids.boundaryLeftId}']`;
    const rightSelector = `.confirmed-block[data-worklog-id='${ids.boundaryRightId}']`;
    await dragConfirmedBlock(page, leftSelector, "bottom", 1305);
    await waitForBlockText(page, leftSelector, "21:00-21:45");
    assert((await page.locator(leftSelector).textContent()).includes("21:00-21:45"), "boundary drag should extend the left block");
    assert((await page.locator(rightSelector).textContent()).includes("21:45-22:00"), "boundary drag should move the right block start");

    await dragConfirmedBlock(page, buildSelector, "middle", 795);
    assert(await page.locator(".timeline-warning-bubble:not([hidden])").count() === 1, "overlap drag should show warning bubble");
    assert((await page.locator(".timeline-warning-bubble").textContent()).includes("Overlaps confirmed work"), "warning bubble should explain overlap");
    assert((await page.locator(buildSelector).textContent()).includes("09:45-10:30"), "overlap drag should not save the move");

    await dragConfirmedBlock(page, buildSelector, "bottom", 690, { shift: true });
    assert(await page.locator(".timeline-warning-bubble:not([hidden])").count() === 1, "shift expansion should show warning bubble");
    assert((await page.locator(".timeline-warning-bubble").textContent()).includes("only shrinks"), "shift expansion warning should explain shrink-only behavior");
    assert((await page.locator(buildSelector).textContent()).includes("09:45-10:30"), "shift expansion should not save");

    console.log(`browser-e2e cases=24 assertions=${assertions} failures=0`);
  } finally {
    if (browser) {
      await browser.close();
    }
    server.kill("SIGTERM");
    await sleep(250);
    if (server.exitCode === null) {
      server.kill("SIGKILL");
    }
    if (process.env.WORKLOG_KEEP_BROWSER_E2E_TMP !== "1") {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  }
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
