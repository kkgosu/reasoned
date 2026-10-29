// Real pinned LearnHarness Next UI + routes + Agent/store, driven in isolated Chromium.
// Only POST /api/chat injects the existing faux-engine seam. Not Electron or transfer acceptance.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

assert(Number(process.versions.node.split(".")[0]) >= 24, "Use Node.js 24+");
assert(process.argv.length === 10 && process.argv[2] === "--upstream" && process.argv[4] === "--records" &&
  process.argv[6] === "--browser" && process.argv[8] === "--output",
  "Usage: node scripts/check-learnharness-ui.mjs --upstream <full-pinned-checkout> --records <six-authored-desktop-snapshots> --browser <Chromium-executable> --output <new-evidence-directory>");
const upstream = path.resolve(process.argv[3]);
const recordsDir = path.resolve(process.argv[5]);
const executablePath = path.resolve(process.argv[7]);
const output = path.resolve(process.argv[9]);
assert(!fs.existsSync(output), "UI evidence output must be a new directory; never overwrite evidence");
assert(fs.statSync(executablePath).isFile());
const json = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
const manifest = json(fileURLToPath(new URL("../fixtures/learnharness/v1/ui-upstream.json", import.meta.url)));
assert.equal(manifest.commit, json(fileURLToPath(new URL("../fixtures/learnharness/v1/upstream.json", import.meta.url))).commit);
function sourceHash(root = upstream) {
  const names = [...manifest.rootFiles, ...manifest.sourceDirectories.flatMap((dir) =>
    fs.readdirSync(path.join(root, dir), { recursive: true }).map((name) => `${dir}/${name.split(path.sep).join("/")}`))
    .filter((name) => fs.statSync(path.join(root, name)).isFile())].sort();
  assert.equal(names.length, manifest.sourceFiles);
  return digest(names.map((name) => `${name}\0${digest(fs.readFileSync(path.join(root, name)))}\n`).join(""));
}
assert.equal(sourceHash(), manifest.sourceTreeSha256, "Upstream UI/runtime/assets must be byte-identical");
const locked = json(path.join(upstream, "package-lock.json")).packages;
const installed = json(path.join(upstream, "node_modules/.package-lock.json")).packages;
for (const [name, metadata] of Object.entries(installed)) {
  assert.equal(metadata.version, locked[name]?.version, `Installed version differs: ${name}`);
  assert.equal(metadata.integrity, locked[name]?.integrity, `Installed integrity differs: ${name}`);
  assert.equal(json(path.join(upstream, name, "package.json")).version, metadata.version);
}
const names = ["teaching", "practice", "compaction", "failures", "recovery-conversation", "recovery-compaction"];
const originals = new Map(names.map((name) => {
  const bytes = fs.readFileSync(path.join(recordsDir, `${name}.json`));
  assert(bytes.length <= 2_000_000, "Only bounded authored fixtures");
  const record = JSON.parse(bytes);
  assert(record.title.startsWith("[Authored offline runtime acceptance]"), "Never pass personal lessons");
  assert(/^[A-Za-z0-9_-]+$/.test(record.id));
  assert.equal(record.version, 1);
  assert.deepEqual(record.settings.model, { provider: "openai", id: "gpt-4" }, "Use the desktop fake-runtime output");
  return [name, { bytes, record }];
}));
assert.equal(new Set([...originals.values()].map(({ record }) => record.id)).size, 6);
fs.mkdirSync(output);
// Use a disposable checkout, never an installed/dev library with secrets or lessons.
assert(!fs.readdirSync(upstream).some((name) => /^\.env(?:\.|$)|credentials|^auth\.json$|^data$/i.test(name)));
const tsconfigFile = path.join(upstream, "tsconfig.json");
const originalTsconfig = fs.readFileSync(tsconfigFile);
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "reasoned-lh-ui-test-"));
// Whitelist the CHILD'S environment, rather than guessing every provider's credential variable.
for (const key of Object.keys(process.env)) {
  if (!/^(PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|NUMBER_OF_PROCESSORS|PROCESSOR_ARCHITECTURE)$/i.test(key)) delete process.env[key];
}
Object.assign(process.env, {
  NODE_ENV: "test", NEXT_TELEMETRY_DISABLED: "1", LH_DATA_DIR: scratch,
  LH_AUTH_FILE: path.join(scratch, "auth.json"), LH_PROVIDER_EVENTS: "0",
  HOME: path.join(scratch, "home"), USERPROFILE: path.join(scratch, "home"),
  APPDATA: path.join(scratch, "home", "AppData", "Roaming"),
  LOCALAPPDATA: path.join(scratch, "home", "AppData", "Local"),
  OPENAI_API_KEY: "authored-ui-presence-gate-not-a-key",
});
// Windows known-folder lookup fails if the isolated AppData directories do not exist.
fs.mkdirSync(process.env.APPDATA, { recursive: true });
fs.mkdirSync(process.env.LOCALAPPDATA, { recursive: true });
fs.mkdirSync(path.join(scratch, "chats"));
for (const { bytes, record } of originals.values()) fs.writeFileSync(path.join(scratch, "chats", `${record.id}.json`), bytes);
// Explicit test-local setup only, not import policy or a learner model switch.
fs.writeFileSync(path.join(scratch, "settings.json"), JSON.stringify({ version: 1,
  models: Object.fromEntries(["strong", "cheap", "vision"].map((slot) => [slot, { model: { provider: "openai", id: "gpt-4" } }])),
  graph: { enabled: false }, general: { autoUpdate: false },
}));
const load = (name) => import(pathToFileURL(path.join(upstream, name)).href);
let browser, app, server, unregister, page, report;
let checks = 0, requests = 0, posts = 0, fontConnections = 0, missingDictationKeyResponses = 0;
const planned = new Map(), blocked = [], frameworkUpdateChecksBlocked = [], browserBlocked = [], pageErrors = [], apiErrors = [], engineErrors = [];
const originalConnect = net.Socket.prototype.connect;
let allowFontCompilation = true;
const local = (host) => ["127.0.0.1", "localhost", "::1"].includes(host);
// The deadline belongs to this disposable server/browser run, never the installed app.
const deadline = setTimeout(() => {
  console.error("UI acceptance exceeded its two-minute lifetime");
  browser?.process()?.kill();
  process.exit(1);
}, 110_000);
try {
  unregister = (await load("node_modules/tsx/dist/esm/api/index.mjs")).register({ tsconfig: path.join(upstream, "tsconfig.json") });
  net.Socket.prototype.connect = function (...args) {
    const a = Array.isArray(args[0]) ? args[0][0] : args[0];
    const host = typeof a === "object" ? a.host ?? a.hostname ?? "localhost" : typeof args[1] === "string" ? args[1] : "localhost";
    if (!local(host)) {
      // Normal next/font compilation may download public font assets, never model input.
      if (allowFontCompilation && ["fonts.googleapis.com", "fonts.gstatic.com"].includes(host)) fontConnections++;
      else {
        (host === "registry.npmjs.org" ? frameworkUpdateChecksBlocked : blocked).push(host);
        throw new Error(`UI acceptance: outbound connection forbidden: ${host}`);
      }
    }
    return originalConnect.apply(this, args);
  };
  const { chatStore } = await load("src/store/chats.ts");
  const { jobStore } = await load("src/store/jobs.ts");
  const native = await load("src/core/native-messages.ts");
  const questions = await load("src/core/question-core.ts");
  const { activeCompactionOf } = await load("src/core/compaction.ts");
  const { handleChatRequest } = await load("src/server/chat-handler.ts");
  const { runningTurn, settledTurnResponse } = await load("src/server/turn-runs.ts");
  const { createTeacherAgent, createPracticeAgent } = await load("src/agent/teacher-agent.ts");
  const { createFauxCore, fauxAssistantMessage, fauxText, fauxToolCall } = await load("node_modules/@earendil-works/pi-ai/dist/index.js");
  const { default: next } = await load("node_modules/next/dist/server/next.js");
  const { default: puppeteer } = await load("node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js");
  const of = (name) => chatStore.getChat(originals.get(name).record.id);
  const text = (s) => fauxAssistantMessage([fauxText(`[Authored UI acceptance] ${s}`)]);
  const call = (name, args, id) => fauxAssistantMessage([fauxToolCall(name, args, { id })], { stopReason: "toolUse" });
  function plan(name, type, responses, validate = () => {}) {
    const before = of(name);
    assert(!planned.has(before.id));
    const entry = { before, type, responses, validate, contexts: [] };
    entry.done = new Promise((resolve) => { entry.finish = resolve; });
    planned.set(before.id, entry);
    return entry;
  }
  app = next({ dir: upstream, dev: true, hostname: "127.0.0.1" });
  await app.prepare();
  const handler = app.getRequestHandler();
  server = http.createServer(async (req, res) => {
    if (req.url !== "/api/chat" || req.method !== "POST") return handler(req, res);
    let entry;
    try {
      posts++;
      const chunks = [];
      let size = 0;
      for await (const chunk of req) { assert((size += chunk.length) <= 1_000_000); chunks.push(chunk); }
      const body = Buffer.concat(chunks).toString("utf8");
      const { chatId, input } = JSON.parse(body);
      entry = planned.get(chatId);
      assert(entry, "Opening/reopening/Next must never issue an unplanned model request");
      assert.equal(input.type, entry.type);
      planned.delete(chatId);
      const faux = createFauxCore({});
      faux.setResponses(entry.responses.map((response) => (context) => {
        try { entry.validate(chatStore.getChat(chatId), input); }
        catch (error) { engineErrors.push(String(error)); throw error; }
        entry.contexts.push(JSON.parse(JSON.stringify(context.messages)));
        return response;
      }));
      let used = 0;
      const engine = { model: faux.getModel(), streamFn: (...args) => {
        assert(used++ < entry.responses.length, "No extra provider requests"); requests++;
        return faux.streamSimple(...args);
      } };
      const ack = await handleChatRequest(new Request("http://127.0.0.1/api/chat", {
        method: "POST", headers: { "Content-Type": "application/json" }, body,
      }), (opts) => {
        const options = { ...opts, engine };
        return entry.before.kind === "practice"
          ? createPracticeAgent({ chatId, chatStore, graphNodes: () => [] }, options)
          : createTeacherAgent({ chatId, chatStore, jobStore, spawn: () => assert.fail("No worker"), searchAvailable: () => false }, options);
      });
      const turn = runningTurn(chatId);
      void new Response(turn ? turn.subscribe() : (await settledTurnResponse(chatId)).body).text()
        .then(entry.finish, (error) => { apiErrors.push(String(error)); entry.finish(); });
      res.writeHead(ack.status, Object.fromEntries(ack.headers));
      res.end(await ack.text());
    } catch (error) {
      apiErrors.push(String(error));
      entry?.finish();
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "Authored UI fixture guard failed" }));
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  console.log(`Isolated UI server: ${origin}`);
  browser = await puppeteer.launch({ executablePath, headless: true, pipe: true,
    timeout: 15_000, protocolTimeout: 20_000, userDataDir: path.join(scratch, "chromium-profile"),
    args: ["--disable-background-networking", "--disable-sync", "--no-first-run", "--disable-default-apps",
      "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1"],
  });
  async function open(name) {
    await page?.close();
    page = await browser.newPage();
    await page.setViewport({ width: 1440, height: 1000, deviceScaleFactor: 1 });
    page.setDefaultTimeout(20_000);
    page.on("pageerror", (error) => pageErrors.push(String(error)));
    page.on("response", (response) => {
      if (!response.url().startsWith(`${origin}/api/`) || response.status() < 400) return;
      const route = new URL(response.url()).pathname;
      // The unconfigured dictation capability honestly reports 503; dictation is not tested.
      if (route === "/api/stt/token" && response.status() === 503) missingDictationKeyResponses++;
      else apiErrors.push(`${response.status()} ${route}`);
    });
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      if (request.url().startsWith(`${origin}/`) || /^(data:|blob:|about:)/.test(request.url())) void request.continue();
      else { browserBlocked.push(request.url()); void request.abort(); }
    });
    await page.goto(`${origin}/chat/${of(name).id}`, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('[data-testid="phase-chip"]', { visible: true });
    // The phase chip is SSR too; wait for the existing client layout effect, not just HTML.
    await page.waitForFunction(() => document.body.innerText.includes("[Authored") &&
      document.querySelector('[data-testid="dock-spacer"]')?.getBoundingClientRect().height > 0);
    return page;
  }
  const visible = () => page.evaluate(() => document.body.innerText);
  const shot = (name) => page.screenshot({ path: path.join(output, `${name}.png`) });
  async function settled(name, entry, marker) {
    await entry.done;
    assert.deepEqual(apiErrors, []);
    assert.deepEqual(engineErrors, [], "The faux provider must not swallow fixture assertions");
    assert.deepEqual(blocked, [], "No real provider request, even during preflight/settlement");
    assert.equal(entry.contexts.length, entry.responses.length);
    await page.waitForFunction((s) => document.body.innerText.includes(s), {}, marker);
    const after = of(name);
    assert.equal(runningTurn(after.id), undefined);
    assert.deepEqual(after.messages.slice(0, entry.before.messages.length), entry.before.messages);
    assert.deepEqual(after.turns.slice(0, entry.before.turns.length), entry.before.turns);
    assert.equal(after.turns.length, entry.before.turns.length + 1);
    for (const [key, value] of Object.entries(entry.before)) {
      if (!["messages", "turns", "updatedAt"].includes(key)) assert.deepEqual(after[key], value, `Preserve ${key}`);
    }
    return after;
  }
  const click = (selector) => page.locator(selector).click();
  async function check(label, run) { await run(); checks++; console.log(`ok - ${label}`); }
  await check("six desktop continuations render locally without any model POST", async () => {
    for (const name of names) await open(name);
    assert.equal(posts, 0);
    assert.equal(requests, 0);
    allowFontCompilation = false;
  });
  await check("quiz reveal, deterministic options, confidence and both thinking fields persist before inference", async () => {
    await open("teaching");
    const pending = native.danglingQuestionOf(of("teaching").messages);
    assert.equal(pending.tool, "quiz");
    assert(!(await visible()).includes(pending.input.explanation));
    await page.type('[data-testid="reading-thinking-field"] textarea', "Authored UI reading thought");
    await shot("quiz-before-reveal");
    await click('[data-testid="teach-check-reveal"]');
    const options = questions.shuffleOptions(questions.normalizeOptions(pending.input.options), questions.seededRandom(pending.toolCallId));
    assert.deepEqual(await page.$$eval('[data-testid="question-dock"] [role="radio"]', (items) => items.map((e) => e.innerText.trim())), options.map((o) => o.label));
    const correct = questions.resolveCorrect(pending.input.correctAnswer, options).indices[0];
    // Native answer indices are one-based; DOM collections are zero-based.
    await (await page.$$('[data-testid="question-dock"] [role="radio"]'))[correct - 1].click();
    await page.focus('[data-testid="confidence-slider"]');
    await page.keyboard.press("End");
    assert.equal(await page.$eval('[data-testid="confidence-slider"]', (e) => e.value), "100");
    await page.type('[data-testid="thinking-field"] textarea', "Authored UI question thought");
    const entry = plan("teaching", "answer", [text("Answer accepted.")], (record, input) => {
      const answer = record.messages.find((m) => m.role === "toolResult" && m.toolCallId === pending.toolCallId);
      assert(answer, "Write-ahead answer");
      assert.equal(answer.details.correct, true);
      assert.equal(answer.details.confidence, 100);
      assert.equal(answer.details.thinking, "Authored UI question thought");
      assert.equal(answer.details.readingThinking, "Authored UI reading thought");
      assert.equal(input.toolCallId, pending.toolCallId);
    });
    await click('[data-testid="question-dock"] button[type="submit"]');
    await settled("teaching", entry, "Answer accepted.");
    const cards = await page.$$('[data-testid="answered-card"]');
    await (await cards.at(-1).$('[data-testid="tucked-explanation"] button')).click();
    await page.waitForFunction((s) => document.body.innerText.includes(s), {}, pending.input.explanation);
    await shot("quiz-answered");
    const count = requests;
    await open("teaching");
    assert.equal(requests, count);
    assert.equal(native.danglingQuestionOf(of("teaching").messages), null);
  });
  await check("practice hides commitment, reveals exact solution, waits for Next and silently discards", async () => {
    await open("practice");
    const active = native.activeProblemOf(of("practice").messages);
    assert.equal(active.toolCallId, "desktop_problem");
    assert(!(await visible()).includes(active.input.modelSolution));
    await shot("practice-hidden");
    const entry = plan("practice", "thinking", [call("conclude", {
      outcome: "solved", verdict: "Authored UI judgment, not mastery", rung: null, missClass: null, retest: false,
    }, "ui_conclude"), text("Practice concluded.")], (record) => {
      assert(record.messages.some((m) => m.customType === "practice-thinking" && m.content.includes("Authored UI practice thought")));
    });
    await page.type('[data-testid="practice-dock"] textarea', "Authored UI practice thought");
    await click('[data-testid="practice-send"]');
    await settled("practice", entry, "Practice concluded.");
    await page.waitForSelector('[data-testid="next-problem-button"]', { visible: true });
    assert((await page.$$eval('[data-testid="conclude-card"]', (items) => items.map((e) => e.innerText))).at(-1).includes(active.input.modelSolution));
    await shot("practice-concluded");
    const count = requests;
    await open("practice");
    assert.equal(requests, count, "Reopen cannot advance practice");
    const next = plan("practice", "next", [call("problem", { statement: "[Authored UI acceptance] Next problem",
      mode: "composition", modelSolution: "AUTHORED_UI_HIDDEN_NEXT_SOLUTION" }, "ui_next_problem")]);
    await click('[data-testid="next-problem-button"]');
    await settled("practice", next, "[Authored UI acceptance] Next problem");
    await page.waitForSelector('[data-testid="practice-dock"]', { visible: true });
    assert(!(await visible()).includes("AUTHORED_UI_HIDDEN_NEXT_SOLUTION"));
    await page.evaluate(() => document.activeElement?.blur());
    await page.keyboard.press("Escape");
    await page.waitForSelector('[data-testid="abort-turn-dialog"]', { visible: true });
    assert((await visible()).includes("Discard this problem?"));
    const noRequest = requests;
    // The dismissal card is optimistic: wait for the real write-ahead HTTP acknowledgement.
    const dismissal = page.waitForResponse((response) => response.url() ===
      `${origin}/api/chats/${of("practice").id}/dismiss` && response.request().method() === "POST");
    await click('[data-testid="abort-turn-confirm"]');
    assert.equal((await dismissal).status(), 200);
    await page.waitForFunction(() => document.body.innerText.includes("Problem dismissed."));
    assert.equal(native.activeProblemOf(of("practice").messages), null);
    assert.equal(requests, noRequest);
    assert(!(await visible()).includes("AUTHORED_UI_HIDDEN_NEXT_SOLUTION"));
  });
  await check("compaction dividers render without turning summary into learner-visible prose", async () => {
    await open("compaction");
    const marker = activeCompactionOf(of("compaction").messages);
    assert(marker);
    await page.waitForSelector('[data-testid="compaction-divider"]');
    await page.waitForSelector('[data-testid="compaction-cut-divider"]');
    assert(!(await visible()).includes(marker.content));
    assert((await visible()).includes("Authored compaction history 0"));
    await shot("compaction-history");
  });
  await check("a failed fake response exposes Retry; explicit retry preserves failed history and usage", async () => {
    await open("failures");
    const failed = plan("failures", "text", [fauxAssistantMessage([], { stopReason: "error", errorMessage: "AUTHORED_UI_RETRY_ERROR" })]);
    await page.type('[data-testid="composer"] textarea', "[Authored UI acceptance] Test failure");
    await page.keyboard.press("Enter");
    await settled("failures", failed, "AUTHORED_UI_RETRY_ERROR");
    await page.waitForSelector('[data-testid="retry-failed-turn"]', { visible: true });
    await shot("retry-error");
    const before = requests;
    await open("failures");
    assert.equal(requests, before, "Reopening a failure must not retry automatically");
    const retry = plan("failures", "retry", [text("Explicit retry complete.")]);
    await click('[data-testid="retry-failed-turn"]');
    await settled("failures", retry, "Explicit retry complete.");
    assert(!JSON.stringify(retry.contexts).includes("AUTHORED_UI_RETRY_ERROR"));
    assert(of("failures").messages.some((m) => m.stopReason === "error" && m.errorMessage === "AUTHORED_UI_RETRY_ERROR"));
  });
  assert.equal(planned.size, 0);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(apiErrors, []);
  assert.deepEqual(blocked, []);
  assert.deepEqual(browserBlocked, []);
  assert.equal(jobStore.listJobs().length, 0);
  for (const [name, { bytes }] of originals) assert.deepEqual(fs.readFileSync(path.join(recordsDir, `${name}.json`)), bytes);
  report = { commit: manifest.commit, checks, fakeRequests: requests, modelRequests: 0,
    publicFontCompilationConnections: fontConnections, browser: await browser.version(),
    electronShellTested: false, transferTested: false, screenshots: fs.readdirSync(output).filter((name) => name.endsWith(".png")),
    pageErrors, apiErrors, engineErrors, blocked, frameworkUpdateChecksBlocked, browserBlocked, missingDictationKeyResponses,
  };
} catch (error) {
  console.error("UI acceptance failed:", error);
  process.exitCode = 1;
  fs.writeFileSync(path.join(output, "failure.json"), JSON.stringify({ error: String(error), checks, requests, posts,
    engineErrors, blocked, frameworkUpdateChecksBlocked, browserBlocked, pageErrors, apiErrors, nodeEnv: process.env.NODE_ENV }, null, 2));
  if (page && !page.isClosed()) {
    await page.screenshot({ path: path.join(output, "failure.png"), timeout: 5000 }).catch(() => {});
    fs.writeFileSync(path.join(output, "failure-visible.txt"), await page.evaluate(() => document.body.innerText).catch(String));
  }
  for (const [name, { record }] of originals) {
    const file = path.join(scratch, "chats", `${record.id}.json`);
    if (fs.existsSync(file)) fs.copyFileSync(file, path.join(output, `${name}-failure.json`));
  }
} finally {
  try {
    await browser?.close();
    server?.closeAllConnections();
    server?.close();
    await app?.close();
    await unregister?.();
    fs.rmSync(scratch, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    // Next dev adds a generated-type glob (and reformats JSON). Accept nothing else.
    const actual = json(tsconfigFile), expected = JSON.parse(originalTsconfig);
    assert(actual.include.every((name) => expected.include.includes(name) || name === ".next/dev/dev/types/**/*.ts"));
    actual.include = actual.include.filter((name) => name !== ".next/dev/dev/types/**/*.ts");
    assert.deepEqual(actual, expected, "Never overwrite unrelated source/config changes");
    fs.writeFileSync(tsconfigFile, originalTsconfig);
    assert.equal(sourceHash(), manifest.sourceTreeSha256, "All pinned bytes restored; UI/runtime source unchanged");
  } catch (error) {
    console.error("UI cleanup failed:", error);
    process.exitCode = 1;
  } finally {
    browser?.process()?.kill();
    net.Socket.prototype.connect = originalConnect;
    clearTimeout(deadline);
  }
}
if (!process.exitCode && report) {
  fs.writeFileSync(path.join(output, "result.json"), JSON.stringify(report, null, 2));
  console.log(`PASS: ${checks} UI checks, ${requests} faux requests, zero real model requests. Not Electron shell or transfer acceptance.`);
}
// Next dev retains watcher handles after close(); this process owns ONLY the disposable harness.
process.exit(process.exitCode ?? 0);
