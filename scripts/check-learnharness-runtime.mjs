// Android-authored records → real pinned LearnHarness handler/Agent/tools/fs persistence.
// Fake provider only. Not Electron/UI, transfer installation, or a live-model test.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

assert(Number(process.versions.node.split(".")[0]) >= 24, "Use Node.js 24+");
assert([6, 8].includes(process.argv.length) && process.argv[2] === "--upstream" && process.argv[4] === "--android-records" &&
  (process.argv.length === 6 || process.argv[6] === "--desktop-records"),
  "Usage: node scripts/check-learnharness-runtime.mjs --upstream <pinned-checkout> --android-records <authored-test-snapshots> [--desktop-records <new-output-directory>]");
const upstream = path.resolve(process.argv[3]);
const recordsDir = path.resolve(process.argv[5]);
const outputDir = process.argv[7] && path.resolve(process.argv[7]);
if (outputDir) assert(!fs.existsSync(outputDir), "Desktop test output must be a new directory; never overwrite records");
const fixtures = fileURLToPath(new URL("../fixtures/learnharness/v1/", import.meta.url));
const json = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const digest = (bytes) => createHash("sha256").update(bytes).digest("hex");
const manifest = json(path.join(fixtures, "runtime-upstream.json"));
assert.equal(manifest.commit, json(path.join(fixtures, "upstream.json")).commit);
// Aggregate: sorted POSIX relative path + NUL + file SHA-256 + LF. Includes prompts.
function sourceHash() {
  const names = ["LICENSE", "config.ts", "package.json", "package-lock.json",
    ...manifest.sourceDirectories.flatMap((dir) => fs.readdirSync(path.join(upstream, "src", dir), { recursive: true })
      .map((name) => `src/${dir}/${name.split(path.sep).join("/")}`))
      .filter((name) => /\.(ts|tsx|md|json)$/.test(name))].sort();
  assert.equal(names.length, manifest.sourceFiles, "Use the complete pinned runtime source directories");
  return digest(names.map((name) => `${name}\0${digest(fs.readFileSync(path.join(upstream, name)))}\n`).join(""));
}
assert.equal(sourceHash(), manifest.sourceTreeSha256, "Upstream runtime source/prompt bytes differ");
const locked = json(path.join(upstream, "package-lock.json")).packages;
const installed = json(path.join(upstream, "node_modules/.package-lock.json")).packages;
for (const name of manifest.dependencies) {
  assert.equal(json(path.join(upstream, "node_modules", name, "package.json")).version,
    locked[`node_modules/${name}`].version, `Install upstream-locked ${name}`);
}
for (const [name, metadata] of Object.entries(installed)) {
  assert.equal(metadata.version, locked[name]?.version, `Installed package differs from upstream lock: ${name}`);
  assert.equal(metadata.integrity, locked[name]?.integrity, `Installed integrity differs: ${name}`);
}
const names = ["teaching", "practice", "compaction", "failures", "recovery-conversation", "recovery-compaction"];
const originals = new Map(names.map((name) => {
  const bytes = fs.readFileSync(path.join(recordsDir, `${name}.json`));
  assert(bytes.length <= 2_000_000, "Only bounded authored fixtures are accepted");
  const record = JSON.parse(bytes);
  assert(/^[A-Za-z0-9_-]+$/.test(record.id));
  assert(record.title.startsWith("[Authored offline runtime acceptance]"), "Do not pass learner records");
  assert.equal(record.version, 1);
  return [name, { bytes, record }];
}));
assert.equal(new Set([...originals.values()].map(({ record }) => record.id)).size, names.length);
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "reasoned-lh-runtime-test-"));
// Before importing any upstream module: no installed data/home, credentials, or requests.
for (const key of Object.keys(process.env)) {
  if (/API_?KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|^(LH_|AWS_|AZURE_|GOOGLE_)/i.test(key)) delete process.env[key];
}
process.env.NODE_ENV = "test"; // Upstream's extraction/automatic-compaction test gate.
process.env.LH_DATA_DIR = scratch;
process.env.LH_PROVIDER_EVENTS = "0";
process.env.HOME = process.env.USERPROFILE = path.join(scratch, "home");
fs.mkdirSync(process.env.HOME);
// Satisfies the handler's presence/catalog gate only; the injected faux engine owns EVERY call.
process.env.OPENAI_API_KEY = "authored-offline-presence-gate-not-a-key";
const networkAttempts = [];
const denyNetwork = () => {
  const error = new Error("Runtime acceptance: network forbidden");
  networkAttempts.push(error.stack);
  throw error;
};
const load = (name) => import(pathToFileURL(path.join(upstream, name)).href);
let unregister;
try {
  // Use upstream's existing TS runner; its setup probes a local parent IPC pipe.
  unregister = (await load("node_modules/tsx/dist/esm/api/index.mjs")).register();
  // Install before ANY upstream import; also blocks SDK HTTP(S), not just fetch.
  globalThis.fetch = denyNetwork;
  net.Socket.prototype.connect = denyNetwork;
  const { PATHS } = await load("config.ts");
  assert.equal(PATHS.data, scratch);
  const { chatStore } = await load("src/store/chats.ts");
  const { jobStore } = await load("src/store/jobs.ts");
  const native = await load("src/core/native-messages.ts");
  const questions = await load("src/core/question-core.ts");
  const inputs = await load("src/core/input-events.ts");
  const { activeCompactionOf } = await load("src/core/compaction.ts");
  const { projectView } = await load("src/core/view.ts");
  const { createTeacherAgent, createPracticeAgent } = await load("src/agent/teacher-agent.ts");
  const { handleChatRequest } = await load("src/server/chat-handler.ts");
  const { runningTurn, settledTurnResponse } = await load("src/server/turn-runs.ts");
  const { steerLiveChat } = await load("src/server/jobs.ts");
  const { createFauxCore, fauxAssistantMessage, fauxToolCall, fauxText } =
    await load("node_modules/@earendil-works/pi-ai/dist/index.js");
  fs.mkdirSync(PATHS.chats, { recursive: true });
  for (const { bytes, record } of originals.values()) {
    fs.writeFileSync(path.join(PATHS.chats, `${record.id}.json`), bytes);
    assert.deepEqual(chatStore.getChat(record.id), record, "Initial native read must be lossless");
    // Explicit test-only catalog pin in scratch; never alter source snapshots or real sessions.
    chatStore.saveSettings(record.id, { ...record.settings, model: { provider: "openai", id: "gpt-4" } });
  }
  let requests = 0;
  let checks = 0;
  const recordOf = (name) => chatStore.getChat(originals.get(name).record.id);
  const text = (value) => fauxAssistantMessage([fauxText(value)]);
  const call = (name, args, id) => fauxAssistantMessage([fauxToolCall(name, args, { id })], { stopReason: "toolUse" });
  const quiz = (id) => call("quiz", {
    question: "[Authored desktop acceptance] Which is even?", type: "check", nodeId: "basics",
    options: [{ label: "Two", value: "two" }, { label: "Three", value: "three" }],
    correctAnswer: "two", explanation: "AUTHORED_DESKTOP_HIDDEN_COMMITMENT",
  }, id);
  async function check(name, run) {
    await run();
    console.log(`ok - ${name}`);
    checks++;
  }
  async function turn(name, input, responses, duringRequest) {
    const before = recordOf(name);
    const contexts = [];
    let initialIds;
    const faux = createFauxCore({});
    faux.setResponses(responses.map((response, index) => (context) => {
      // The learner input is already durable BEFORE any fake model request.
      const durable = chatStore.getChat(before.id);
      if (input.type === "answer") assert(durable.messages.some((m) => m.role === "toolResult" && m.toolCallId === input.toolCallId));
      if (input.type === "thinking") assert(durable.messages.some((m) => m.customType === "practice-thinking" && m.content.includes(input.text)));
      contexts.push(JSON.parse(JSON.stringify(context.messages)));
      duringRequest?.(before.id, index);
      return response;
    }));
    let dispatched = 0;
    const engine = { model: faux.getModel(), streamFn: (...args) => {
      requests++;
      assert(dispatched++ < responses.length, "Unexpected extra model request");
      return faux.streamSimple(...args);
    } };
    const ack = await handleChatRequest(new Request("http://offline.invalid/api/chat", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ chatId: before.id, input }),
    }), (opts) => {
      initialIds = opts.messages.map((m) => m.id);
      const options = { ...opts, engine };
      return before.kind === "practice"
        ? createPracticeAgent({ chatId: before.id, chatStore, graphNodes: () => [] }, options)
        : createTeacherAgent({ chatId: before.id, chatStore, jobStore,
          spawn: () => assert.fail("Must not spawn a worker"), searchAvailable: () => false }, options);
    });
    assert.equal(ack.status, 200, await ack.text());
    const handle = runningTurn(before.id);
    const body = await (handle ? new Response(handle.subscribe()) : settledTurnResponse(before.id)).text();
    assert(body.endsWith("data: [DONE]\n\n"));
    const events = body.split("\n").filter((line) => line.startsWith("data: {")).map((line) => JSON.parse(line.slice(6)));
    assert(!events.some((event) => event.type === "error"), "No masked handler/settlement error");
    assert.equal(runningTurn(before.id), undefined);
    const after = recordOf(name);
    assert.equal(dispatched, responses.length);
    assert.equal(contexts.length, responses.length);
    assert.deepEqual(after.messages.slice(0, before.messages.length), before.messages, "Full archived prefix is immutable");
    assert.deepEqual(after.turns.slice(0, before.turns.length), before.turns, "Prior costs, including unknown steps, stay intact");
    assert.equal(after.turns.length, before.turns.length + 1);
    assert.equal(after.turns.at(-1).steps.length, responses.length);
    assert(after.messages.some((m) => m.id === after.turns.at(-1).messageId));
    for (const [key, value] of Object.entries(before)) {
      if (!["messages", "turns", "updatedAt", "tree"].includes(key)) assert.deepEqual(after[key], value, `Preserve ${key}`);
    }
    assert(!after.messages.slice(before.messages.length).some((m) => m.role === "assistant" && ["error", "aborted"].includes(m.stopReason)));
    return { before, after, contexts, initialIds, events };
  }
  function answer(pending) {
    const quiz = pending.tool === "quiz";
    const options = quiz ? questions.shuffleOptions(questions.normalizeOptions(pending.input.options), questions.seededRandom(pending.toolCallId)) : [];
    const data = { id: pending.toolCallId, kind: quiz ? "quiz" : "explain", question: pending.input.question,
      questionType: pending.input.type, mode: quiz ? "single-select" : "text", options,
      correctIndices: quiz ? questions.resolveCorrect(pending.input.correctAnswer, options).indices : undefined,
      explanation: quiz ? pending.input.explanation : undefined,
      committedAnswer: quiz ? undefined : pending.input.correctAnswer, createdAt: new Date().toISOString() };
    return { type: "answer", toolCallId: pending.toolCallId, answer: questions.buildAnswer(data, quiz
      ? { values: ["two"], confidence: 90, thinking: "Authored desktop thinking", readingThinking: "Authored desktop reading thought" }
      : { text: "[Authored desktop acceptance] An integer divisible by two." }, pending.tool) };
  }
  await check("all six Android snapshots open/project locally without inference or liveness", async () => {
    for (const name of names) {
      const record = recordOf(name);
      assert(projectView(record.messages).length > 0);
      assert(native.toLlmMessages(record.messages).length > 0);
      assert.equal(runningTurn(record.id), undefined);
      assert(!((await settledTurnResponse(record.id).text()).includes('"type":"error"')));
    }
    assert.equal(requests, 0);
  });
  await check("Android teaching answer → native invalid-plan repair → new pending question with unique call id", async () => {
    const pending = native.danglingQuestionOf(recordOf("teaching").messages);
    assert.equal(pending.tool, "quiz");
    const submitted = answer(pending);
    assert.equal(submitted.answer.correct, true);
    const result = await turn("teaching", submitted, [
      call("plan_init", { mermaid: "graph TD\nbasics --> basics" }, "desktop_bad_plan"),
      call("plan_init", { mermaid: "graph TD\nbasics[Even integers]" }, "desktop_plan"),
      quiz(pending.toolCallId), // Deliberately collide with the Android-origin call.
    ]);
    assert.match(JSON.stringify(result.contexts[1]), /self|itself/i);
    const next = native.danglingQuestionOf(result.after.messages);
    assert.equal(next.tool, "quiz");
    assert.notEqual(next.toolCallId, pending.toolCallId);
    const savedAnswer = result.after.messages.find((m) => m.role === "toolResult" && m.toolCallId === pending.toolCallId);
    assert.deepEqual(savedAnswer.details, submitted.answer);
    assert.equal(result.after.tree.nodes[0].id, "basics");
    // Native plan authoring refuses through details.ok:false, not the isError channel.
    assert(result.events.some((event) => event.type === "tool_execution_end" && event.toolCallId === "desktop_bad_plan" && event.result.details.ok === false));
    assert.equal(result.after.messages.find((m) => m.role === "toolResult" && m.toolCallId === "desktop_bad_plan").details.ok, false);
  });
  await check("Android practice → write-ahead thinking, conclude original commitment, explicit Next", async () => {
    const active = native.activeProblemOf(recordOf("practice").messages);
    assert(active);
    const result = await turn("practice", { type: "thinking", problemCallId: active.toolCallId,
      text: "[Authored desktop acceptance] Let n = 2k." }, [call("conclude", {
      outcome: "solved", verdict: "Authored test judgment, not mastery", rung: null, missClass: null, retest: false,
    }, "desktop_conclude"), text("[Authored desktop acceptance] Closing prose only.")]);
    assert.equal(native.activeProblemOf(result.after.messages), null);
    const conclude = projectView(result.after.messages).flatMap((m) => m.parts).find((p) => p.toolCallId === "desktop_conclude");
    assert.equal(conclude.input.outcome, "solved");
    assert.equal(active.input.modelSolution, "AUTHORED_HIDDEN_PRACTICE_SOLUTION");
    assert.equal(conclude.reveal, active.input.modelSolution, "Reveal the Android-origin commitment verbatim");
    const posed = await turn("practice", { type: "next" }, [call("problem", {
      statement: "[Authored desktop acceptance] Show 4k is even.", mode: "composition", modelSolution: "4k = 2(2k).",
    }, "desktop_problem")]);
    assert.equal(native.activeProblemOf(posed.after.messages).toolCallId, "desktop_problem");
    assert.match(JSON.stringify(posed.contexts[0]), /NEXT PROBLEM/);
  });
  await check("Android compaction → real Agent sees summary once plus kept tail, archive remains complete", async () => {
    const original = recordOf("compaction");
    const marker = activeCompactionOf(original.messages);
    assert(marker);
    const result = await turn("compaction", { type: "text", text: "[Authored desktop acceptance] Continue the explanation." },
      [text("[Authored desktop acceptance] Compacted continuation.")]);
    const wire = JSON.stringify(result.contexts[0]);
    const summary = JSON.stringify(marker.content).slice(1, -1);
    assert.equal(wire.split(summary).length - 1, 1);
    assert(!wire.includes("[Authored compaction history 0]"));
    assert.deepEqual(activeCompactionOf(result.after.messages), marker);
  });
  await check("Android failure history → explicit text keeps all rejected attempts and their costs", async () => {
    const result = await turn("failures", { type: "text", text: "[Authored desktop acceptance] Continue explicitly." },
      [text("[Authored desktop acceptance] Failure-history continuation.")]);
    assert.equal(result.before.messages.filter((m) => m.stopReason === "error").length, 4);
    assert.equal(result.after.messages.filter((m) => m.stopReason === "error").length, 4);
  });
  await check("Android conversation process-death → explicit native Retry omits failures only in temporary history", async () => {
    const original = recordOf("recovery-conversation");
    const omissions = inputs.retryDrops(original.messages);
    assert(omissions.size > 0);
    const result = await turn("recovery-conversation", { type: "retry" }, [quiz("desktop_recovered")]);
    for (const id of omissions) {
      assert(!result.initialIds.includes(id));
      assert(result.after.messages.some((m) => m.id === id));
    }
    assert.equal(result.after.messages.filter((m) => m.role === "toolResult" && m.details?.kind === "quiz").length,
      original.messages.filter((m) => m.role === "toolResult" && m.details?.kind === "quiz").length);
    assert.equal(native.danglingQuestionOf(result.after.messages).toolCallId, "desktop_recovered");
  });
  await check("Android interrupted compaction → original hidden question answered once, without replacing prior accounting", async () => {
    const original = recordOf("recovery-compaction");
    const pending = native.danglingQuestionOf(original.messages);
    assert.equal(pending.tool, "explain");
    const result = await turn("recovery-compaction", answer(pending), [text("[Authored desktop acceptance] Recall continuation.")]);
    const savedAnswer = result.after.messages.find((m) => m.role === "toolResult" && m.toolCallId === pending.toolCallId);
    assert.equal(savedAnswer.details.committed, pending.input.correctAnswer);
    assert.equal(native.danglingQuestionOf(result.after.messages), null);
    assert.deepEqual(activeCompactionOf(result.after.messages), activeCompactionOf(original.messages));
  });
  // Extra authored scratch chat: do not change the six reverse-continuation fixture outputs.
  const steering = { ...recordOf("compaction"), id: "authored_desktop_steering" };
  originals.set("steering", { record: steering, bytes: Buffer.from(JSON.stringify(steering)) });
  fs.writeFileSync(path.join(PATHS.chats, `${steering.id}.json`), JSON.stringify(steering));
  try {
    const queued = ["[Authored steer] Slow down", "[Authored steer] Keep the original permissions"];
    const inject = (prefix) => (id, index) => {
      if (index === 0) queued.forEach((words, i) => {
        const event = inputs.parseUserSteerInput({ type: "steer", id: `steer_${prefix}_${i}`, text: words });
        assert(steerLiveChat(id, native.newUserSteer(event.text, event.id)));
      });
    };
    await check("live learner steers drain together inside one run, after the current response", async () => {
      const result = await turn("steering", { type: "text", text: "[Authored test] Start a steerable turn" },
        [text("[Authored test] First response"), text("[Authored test] React to both steers")], inject("prose"));
      queued.forEach((words, i) => {
        assert(!JSON.stringify(result.contexts[0]).includes(words));
        assert(JSON.stringify(result.contexts[1]).includes(words));
        assert.equal(result.after.messages.filter((m) => m.id === `steer_prose_${i}`).length, 1);
      });
      assert.deepEqual(result.after.messages.filter((m) => m.id?.startsWith("steer_prose_")).map((m) => m.content), queued);
      assert.equal(result.after.turns.at(-1).steps.length, 2);
    });
    await check("a question parks learner steers until the next explicit answer run; settled runs reject delivery", async () => {
      const stopped = await turn("steering", { type: "text", text: "[Authored test] Ask a question" },
        [quiz("authored_steering_question")], inject("parked"));
      assert(!stopped.after.messages.some((m) => m.id?.startsWith("steer_parked_")));
      assert.equal(steerLiveChat(steering.id, native.newUserSteer("[Authored test] Too late", "steer_settled")), false);
      const resumed = await turn("steering", answer(native.danglingQuestionOf(stopped.after.messages)),
        [text("[Authored test] Explicit answer resumed with parked learner text")]);
      queued.forEach((words, i) => {
        assert(JSON.stringify(resumed.contexts[0]).includes(words));
        assert.equal(resumed.after.messages.filter((m) => m.id === `steer_parked_${i}`).length, 1);
      });
      assert.deepEqual(resumed.after.messages.filter((m) => m.id?.startsWith("steer_parked_")).map((m) => m.content), queued);
    });
  } finally {
    originals.delete("steering");
  }
  for (const [name, { bytes }] of originals) {
    assert.deepEqual(fs.readFileSync(path.join(recordsDir, `${name}.json`)), bytes, "Source Android snapshots stay byte-preserved");
  }
  assert.equal(sourceHash(), manifest.sourceTreeSha256, "Upstream source/prompt bytes stay untouched");
  assert.equal(jobStore.listJobs().length, 0, "No job was started or imported");
  assert.deepEqual(networkAttempts, [], "No network request may be attempted");
  if (outputDir) {
    // Unmodified post-settlement store bytes, only after every acceptance assertion passed.
    fs.mkdirSync(outputDir);
    for (const [name, { record }] of originals) {
      fs.writeFileSync(path.join(outputDir, `${name}.json`), fs.readFileSync(path.join(PATHS.chats, `${record.id}.json`)), { flag: "wx" });
    }
    console.log("Wrote six authored desktop continuations for the opt-in Android fixture harness.");
  }
  console.log(`PASS: ${checks} desktop-runtime checks, ${requests} faux requests, zero network attempts at ${manifest.commit}. Not Electron/UI, live-provider, reverse Android continuation, or transfer acceptance.`);
} finally {
  unregister?.();
  fs.rmSync(scratch, { recursive: true, force: true });
}
