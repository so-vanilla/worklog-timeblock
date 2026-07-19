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
  assert(dev.status === 200, "development category should be created");
  assert(meetings.status === 200, "meetings category should be created");

  const worklog = await requestJson(baseUrl, "POST", "/api/days/2026-07-06/worklogs", {
    title: "Build",
    "start-minute": 540,
    "end-minute": 600,
    "category-id": dev.body.id,
  });
  assert(worklog.status === 200, "confirmed worklog should be created");

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

  return { devId: dev.body.id, meetingsId: meetings.body.id };
}

async function timelineBox(page) {
  const box = await page.locator(".timeline-track").boundingBox();
  assert(Boolean(box), "timeline track should have a box");
  return box;
}

function yForMinute(box, minute) {
  return box.y + (box.height * minute) / 1440;
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
    await page.goto(`${baseUrl}/days/2026-07-06`);

    const ratio = await page.locator(".timeline-pane").evaluate((pane) => {
      const grid = pane.closest(".workspace-grid");
      return pane.getBoundingClientRect().width / grid.getBoundingClientRect().width;
    });
    assert(ratio >= 0.28 && ratio <= 0.36, `timeline width ratio should be about one third, got ${ratio}`);
    assert(await page.locator(".entry-pane").count() === 1, "entry pane should exist");
    assert(await page.locator(".summary-pane").count() === 1, "summary pane should exist");

    let box = await timelineBox(page);
    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 540));
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, yForMinute(box, 600));
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

    console.log(`browser-e2e cases=6 assertions=${assertions} failures=0`);
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
