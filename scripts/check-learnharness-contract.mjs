// Offline reference checks against real, hash-pinned LearnHarness readers/folds.
// No Android conversion, provider requests, job runner, or artifact execution.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const fixtures = fileURLToPath(new URL("../fixtures/learnharness/v1/", import.meta.url));
const json = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const sha256 = (file) => createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const manifest = json(path.join(fixtures, "upstream.json"));
const cases = json(path.join(fixtures, "cases.json"));
assert(process.versions.node.split(".")[0] >= 24, "Use Node.js 24+ (native TypeScript support)");
assert(process.argv.length === 4 && process.argv[2] === "--upstream",
  "Usage: node scripts/check-learnharness-contract.mjs --upstream <pinned-LearnHarness-checkout>");
const upstream = path.resolve(process.argv[3]);
for (const [file, expected] of Object.entries(manifest.sha256)) {
  assert.equal(sha256(path.join(upstream, file)), expected, `Upstream source differs: ${file}`);
}
const typeboxPackage = path.join(upstream, "node_modules/typebox/package.json");
assert.equal(json(typeboxPackage).version, manifest.typebox.version, "Install the pinned upstream TypeBox version");
const lockedTypebox = json(path.join(upstream, "package-lock.json")).packages["node_modules/typebox"];
assert.equal(lockedTypebox.integrity, manifest.typebox.integrity);

function snapshot(dir) {
  return Object.fromEntries(fs.readdirSync(dir, { recursive: true }).sort()
    .filter((name) => fs.statSync(path.join(dir, name)).isFile())
    .map((name) => [name, sha256(path.join(dir, name))]));
}
const originalFixtures = snapshot(fixtures);
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "reasoned-lh-reference-"));
// Set BEFORE importing config/stores. Never point native stores at user data.
process.env.LH_DATA_DIR = scratch;
process.env.NODE_ENV = "test";
let checks = 0;
async function check(name, run) {
  await run();
  console.log(`ok - ${name}`);
  checks++;
}
const load = (name) => import(pathToFileURL(path.join(upstream, name)).href);

try {
  fs.cpSync(path.join(fixtures, "data"), scratch, { recursive: true });
  const originalData = snapshot(scratch);
  const native = await load("src/core/native-messages.ts");
  const phase = await load("src/core/phases.ts");
  const plan = await load("src/core/contracts/plan.ts");
  const input = await load("src/core/input-events.ts");
  const questions = await load("src/core/question-core.ts");
  const parsers = await load("src/core/tool-parts.ts");
  const { projectView } = await load("src/core/view.ts");
  const { findPendingToolCall } = await load("src/core/pending.ts");
  const compaction = await load("src/core/compaction.ts");
  const coverage = await load("src/core/coverage.ts");
  const { getGraph, deriveView } = await load("src/core/graph.ts");
  const { chatStore } = await load("src/store/chats.ts");
  const { graphStore } = await load("src/store/graph.ts");
  const { jobStore } = await load("src/store/jobs.ts");
  const { buildSubagentSteer, accumulateJobUsage } = await load("src/core/contracts/jobs.ts");
  const { createTree } = await load("src/core/plan-tree.ts");
  const { parseMermaidPlan } = await load("src/agent/tools/mermaid-plan.ts");
  const { PATHS } = await load("config.ts");
  const { CURRENT_CHAT_VERSION } = await load("src/core/contracts/chat.ts");
  const { CURRENT_GRAPH_FORMAT_VERSION } = await load("src/core/contracts/graph.ts");
  const { Value } = await load("node_modules/typebox/build/value/index.mjs");
  const { jobTools } = await load("src/agent/tools/jobs.ts");
  assert.equal(PATHS.data, scratch);
  assert.equal(CURRENT_CHAT_VERSION, manifest.chatVersion);
  assert.equal(CURRENT_GRAPH_FORMAT_VERSION, manifest.graphVersion);

  const records = new Map(fs.readdirSync(path.join(scratch, "chats")).map((file) => {
    const original = json(path.join(fixtures, "data/chats", file));
    const record = chatStore.getChat(original.id);
    assert.deepEqual(record, original, `Native chat read: ${file}`);
    return [record.id, record];
  }));
  const teach = records.get("chat_contract_teach");
  const practice = records.get("chat_contract_practice");
  const artifacts = records.get("chat_contract_artifacts");
  function through(record, id) {
    const index = record.messages.findIndex((m) => m.id === id);
    assert(index >= 0, `Missing checkpoint message ${id}`);
    return record.messages.slice(0, index + 1);
  }
  const parts = (messages) => projectView(messages).flatMap((m) => m.parts);

  await check("native readers preserve all four records; absent version/kind remain absent", () => {
    assert.equal(records.size, 4);
    assert(!Object.hasOwn(practice, "version"));
    assert(!Object.hasOwn(teach, "kind"));
    const summaries = chatStore.listChats();
    assert.equal(summaries.length, records.size);
    assert.equal(summaries.find((r) => r.id === teach.id).kind, "teach");
    assert.equal(summaries.find((r) => r.id === practice.id).kind, "practice");
    assert.equal(summaries.find((r) => r.id === teach.id).phase, "teach");
  });

  for (const test of cases.checkpoints) {
    await check(test.name, () => {
      const record = records.get(test.chat);
      const messages = through(record, test.through);
      const before = JSON.stringify(messages);
      const statuses = record.tree ? plan.derivePlanStatusesLh(record.tree, messages) : null;
      const actual = {
        phase: phase.phaseAt3Lh(messages),
        pending: native.danglingQuestionOf(messages)?.toolCallId ?? null,
        continuation: native.needsContinuation(messages),
        active: native.activeProblemOf(messages)?.toolCallId ?? null,
        dismissal: native.pendingProblemDismissalOf(messages),
        current: statuses?.current ?? null,
        visited: statuses ? [...statuses.visited] : [],
        retry: input.endsInFailedTurn(messages),
      };
      if (test.expect.retryDrops) actual.retryDrops = [...input.retryDrops(messages)];
      for (const [key, expected] of Object.entries(test.expect)) {
        assert.deepEqual(actual[key], expected, `${test.name}: ${key}`);
      }
      if (record.tree) {
        const view = projectView(messages);
        assert.equal(phase.phaseAt3(view), actual.phase, "Native/view phase parity");
        const viewStatus = plan.derivePlanStatuses(record.tree, view);
        assert.equal(viewStatus.current, actual.current, "Native/view plan parity");
        assert.deepEqual([...viewStatus.visited], actual.visited);
        assert.equal(findPendingToolCall(view)?.toolCallId ?? null, actual.pending);
      }
      assert.equal(JSON.stringify(messages), before, "Folds must not rewrite the archive");
    });
  }

  await check("native question/probe parsers and current teach schemas accept authored calls", () => {
    for (const message of teach.messages) {
      if (message.role !== "assistant") continue;
      for (const call of message.content.filter((b) => b.type === "toolCall")) {
        const parse = ({ probe: parsers.parseQuizInput, quiz: parsers.parseQuizInput,
          explain: parsers.parseExplainInput, probe_done: parsers.parseProbeDoneInput })[call.name];
        if (parse) assert(parse(call.arguments), `Native parser rejected ${call.id}`);
        const schema = ({ probe: questions.probeQuizInputSchema,
          quiz: questions.quizInputObject, explain: questions.explainInputObject })[call.name];
        if (schema) assert(Value.Check(schema, call.arguments), `Native schema rejected ${call.id}`);
      }
    }
    const authored = teach.messages.find((m) => m.id === "m_plan").content[0].arguments;
    const parsed = parseMermaidPlan(authored.mermaid);
    assert(parsed.ok);
    assert.deepEqual(createTree(parsed.plan.nodes, parsed.plan.edges), teach.tree);
    assert.deepEqual(teach.messages.find((m) => m.id === "m_plan_result").details.tree, teach.tree);
  });

  for (const test of cases.answers) {
    await check(test.name, () => {
      assert.deepEqual(questions.compactAnswerForModel(test.answer), test.compact);
      const result = native.newAnswerResult("call_authored_test", test.answer.tool ?? "ask_user_question", test.answer);
      assert.deepEqual(result.details, test.answer);
      assert.deepEqual(JSON.parse(result.content[0].text), test.compact);
    });
  }

  await check("saved answer display order, stable values and exact-set grading agree", () => {
    for (const message of teach.messages.filter((m) => m.role === "toolResult" && m.details?.kind === "quiz")) {
      const call = teach.messages.flatMap((m) => m.role === "assistant" ? m.content : [])
        .find((b) => b.type === "toolCall" && b.id === message.toolCallId);
      const options = questions.shuffleOptions(questions.normalizeOptions(call.arguments.options), questions.seededRandom(call.id));
      const indices = questions.resolveCorrect(call.arguments.correctAnswer, options).indices;
      assert.deepEqual(message.details.options, options.map((o, i) => ({ index: i + 1, label: o.label })));
      assert.deepEqual(message.details.correctIndices, indices);
      for (const answer of message.details.answers) assert.equal(options[answer.index - 1].value, answer.value);
      assert.equal(message.details.correct, questions.isCorrect(message.details.answers.map((a) => a.index), indices));
      assert.equal(native.nativeInputCause(message, through(teach, message.id).slice(0, -1)), "learner");
    }
  });

  await check("native answer append and duplicate rejection; dismissal is a durable pause", () => {
    const messages = through(teach, "m_probe");
    const answer = teach.messages.find((m) => m.id === "m_probe_answer").details;
    const event = { type: "answer", toolCallId: "call_probe_1", answer };
    const appends = input.inputAppends({ messages }, event);
    assert.equal(appends.length, 1);
    assert.deepEqual(appends[0].details, answer);
    assert.equal(native.needsContinuation([...messages, ...appends]), true);
    assert.throws(() => input.inputAppends({ messages: [...messages, ...appends] }, event), /no pending question/);
    const dismissed = input.inputAppends({ messages }, { ...event, answer: { ...answer, dismissed: true } });
    assert(native.endsInDismissal([...messages, ...dismissed]));
    assert.equal(native.needsContinuation([...messages, ...dismissed]), false);
  });

  await check("probe completion requires matching clean result, not a bare or failed call", () => {
    const valid = through(teach, "m_probe_done_result");
    for (const change of [
      (m) => { m.at(-1).details.ok = false; },
      (m) => { m.at(-1).isError = true; },
      (m) => { m.at(-1).toolCallId = "wrong_call"; },
      (m) => { m.at(-1).toolName = "wrong_tool"; },
      (m) => { m.at(-2).stopReason = "aborted"; },
      (m) => { m.at(-2).stopReason = "error"; },
    ]) {
      const messages = structuredClone(valid);
      change(messages);
      assert.equal(native.hasProbeDone(messages), false);
      assert.equal(phase.phaseAt3Lh(messages), "probe");
    }
    const legacy = structuredClone(valid);
    delete legacy.at(-1).details;
    assert.equal(native.hasProbeDone(legacy), true, "Old clean acknowledgments still count");
  });

  await check("practice gates, immutable pose solution and deferred dismissal appends", () => {
    const active = through(practice, "p_pose_ack");
    assert.throws(() => input.inputAppends({ kind: "practice", messages: active }, { type: "next" }), /no active problem/);
    assert.throws(() => input.inputAppends({ messages: active }, { type: "thinking", problemCallId: "wrong", text: "test" }), /no active problem/);
    const appends = input.inputAppends({ kind: "practice", messages: through(practice, "p_dismissed") }, { type: "next" });
    assert.equal(appends.length, 3);
    assert.deepEqual(appends[0].content, practice.messages.find((m) => m.id === "p_dismiss_conclude").content);
    assert.equal(appends[1].details.problemCallId, "call_problem_again");
    assert.equal(appends[2].customType, "practice-next");
    const solution = practice.messages.find((m) => m.id === "p_pose").content[0].arguments.modelSolution;
    assert.equal(native.modelSolutionForConclude(practice.messages, "call_conclude"), solution);
    assert.equal(parts(practice.messages).find((p) => p.toolCallId === "call_conclude").reveal, solution);
    const failedPose = structuredClone(active);
    failedPose.at(-1).isError = true;
    assert.equal(native.activeProblemOf(failedPose), null);
    const failedConclude = structuredClone(through(practice, "p_conclude_ack"));
    failedConclude.at(-1).isError = true;
    assert.equal(native.activeProblemOf(failedConclude).toolCallId, "call_problem");
  });

  await check("interrupted wire repair is ephemeral; failed/aborted calls get no orphan result", () => {
    const pending = through(teach, "m_probe");
    const wire = native.toLlmMessages(pending);
    assert.equal(wire.at(-1).toolCallId, "call_probe_1");
    assert.equal(wire.at(-1).content[0].text, native.INTERRUPTED_TEXT);
    assert.equal(pending.at(-1).role, "assistant");
    const failed = records.get("chat_contract_interrupted").messages;
    assert.equal(native.toLlmMessages(failed).filter((m) => m.role === "toolResult").length, 0);
    assert.deepEqual(input.inputAppends({ messages: through(records.get("chat_contract_interrupted"), "i_error") }, { type: "retry" }), []);
  });

  await check("compaction cuts model input only; missing cut falls back to full archive", () => {
    const marker = compaction.activeCompactionOf(teach.messages);
    assert.equal(marker.details.firstKeptMessageId, "m_teach");
    const wire = native.toLlmMessages(teach.messages);
    assert.equal(wire[0].content[0].text, marker.content);
    assert.deepEqual(wire[1].content, teach.messages.find((m) => m.id === "m_teach").content);
    assert(!wire.some((m) => m.id || m.role === "custom"));
    assert(!wire.some((m) => m.role === "user" && m.content === teach.messages[0].content));
    const broken = structuredClone(teach.messages);
    broken.at(-1).details.firstKeptMessageId = "missing_message";
    assert.equal(native.toLlmMessages(broken)[0].content, teach.messages[0].content);
    assert.equal(teach.messages[0].id, "m_goal", "Full archive still exists");
    assert.equal(native.nativeInputCause(marker, teach.messages), "neutral");
    assert.equal(native.nativeInputCause(teach.messages.find((m) => m.customType === "probe-handoff"), teach.messages), "automatic");
  });

  await check("native graph ledgers merge identity/provenance; canvas arrows are derived", () => {
    assert.equal(graphStore.readNodes().length, cases.graph.rawNodes);
    assert.equal(graphStore.readChains().length, cases.graph.rawChains);
    const graph = getGraph(graphStore);
    assert.deepEqual(graph.nodes.map((n) => n.id), cases.graph.nodeIds);
    assert.deepEqual(graph.chains.map((c) => c.id), cases.graph.chainIds);
    const shared = graph.nodes.find((n) => n.id === "n_shared");
    assert.equal(shared.name, cases.graph.sharedName);
    assert.equal(shared.statement, cases.graph.sharedStatement);
    assert.deepEqual(shared.aliases, cases.graph.sharedAliases);
    assert.deepEqual(shared.provenance, cases.graph.sharedProvenance);
    assert.deepEqual(graph.chains[0].provenance, cases.graph.chainProvenance);
    assert.deepEqual(deriveView(graph).logicalEdges, cases.graph.logicalEdges);
    assert(deriveView(graph).nodes.some((n) => n.id === "arg_selected"));
    assert(!graph.nodes.some((n) => n.id === "arg_selected"));
    assert.deepEqual(graphStore.readSources().map((s) => s.id), cases.dependencies.externalSources);
    for (const id of cases.dependencies.externalChats) assert.equal(chatStore.getChat(id), null);
    assert.equal(graphStore.readCheckpoints()[0].chatId, "src_contract_notes");
  });

  await check("coverage fingerprints exactly native teacher prose, never learner answers", () => {
    const entries = graphStore.readCoverage();
    const state = coverage.coverageOf(teach.messages, entries, teach.id);
    assert.equal(state.coveredCount, 1);
    assert.equal(state.uncoveredCount, 0);
    assert.deepEqual(state.slots.filter((s) => s.tokens > 0).map((s) => s.message.id), cases.graph.coveredMessageIds);
    const changed = structuredClone(teach.messages);
    changed.find((m) => m.id === "m_predict_answer").details.thinking = "Different learner thinking";
    assert.deepEqual(coverage.coverageSlots(changed).map((s) => s.fp), state.slots.map((s) => s.fp));
    changed.find((m) => m.id === "m_teach").content.find((b) => b.type === "toolCall").arguments.message += " New prose.";
    assert.equal(coverage.coverageOf(changed, entries, teach.id).uncoveredCount, 1);
  });

  await check("inline artifacts, image bytes and pending job remain passive data", async () => {
    const steers = artifacts.messages.filter((m) => m.customType === "subagent-steer");
    assert.deepEqual(steers.map((m) => m.details.kind), cases.dependencies.deliveredKinds);
    for (const steer of steers) {
      const rebuilt = buildSubagentSteer({ id: steer.details.jobId, kind: steer.details.kind, toolCallId: steer.details.toolCallId }, steer.details.result);
      assert.equal(steer.id, rebuilt.id);
      assert.equal(steer.content, rebuilt.content);
      assert.deepEqual(steer.details, rebuilt.details);
      assert(parts(artifacts.messages).some((p) => p.type === "data-subagent-artifact" && p.data.kind === steer.details.kind));
    }
    const image = artifacts.messages.find((m) => m.toolCallId === cases.dependencies.inlineImageCall);
    const imagePart = image.content.find((p) => p.type === "image");
    assert.equal(Buffer.from(imagePart.data, "base64").subarray(1, 4).toString(), "PNG");
    const jobs = jobStore.listJobs(artifacts.id);
    assert.equal(jobs.length, 1);
    const job = jobs[0];
    assert.equal(job.id, cases.dependencies.pendingJob);
    assert.equal(job.delivery.status, "pending");
    assert(!steers.some((m) => m.details.jobId === job.id));
    // Existing-job seam: execute only the local ack mapping; spawning is forbidden.
    const tools = jobTools({ chatId: artifacts.id,
      jobStore: { findJobByToolCallId: () => job },
      spawn: () => assert.fail("Reference checks must not spawn jobs") });
    const ack = await tools.pdf.execute(job.toolCallId, job.input);
    const savedAck = artifacts.messages.find((m) => m.id === "a_pdf_pending_ack");
    assert.deepEqual(ack.content, savedAck.content);
    assert.deepEqual(ack.details, savedAck.details);
    const usage = accumulateJobUsage(undefined, "offline-fixture", {});
    assert.equal(usage.steps, 1);
    assert.equal(usage.inputTokens, undefined, "Unreported usage is not zero");
  });

  await check("all native reads/folds leave every source and scratch data byte unchanged", () => {
    assert.deepEqual(snapshot(scratch), originalData);
    assert.deepEqual(snapshot(fixtures), originalFixtures);
  });

  await check("native resave retains opaque fields/full history; version stamp is lazy", () => {
    for (const record of records.values()) {
      const file = path.join(scratch, "chats", `${record.id}.json`);
      const bytes = fs.readFileSync(file);
      try {
        chatStore.saveMessages(record.id, record.messages);
        const saved = chatStore.getChat(record.id);
        assert.deepEqual({ ...saved, updatedAt: record.updatedAt }, { ...record, version: 1 });
        if (record.turns) {
          chatStore.appendTurnUsage(record.id, record.turns[0]);
          assert.deepEqual(chatStore.getChat(record.id).turns, record.turns);
          assert(!Object.hasOwn(chatStore.getChat(record.id).turns[0].steps[0], "cacheReadTokens"));
        }
      } finally {
        fs.writeFileSync(file, bytes);
      }
    }
  });

  await check("future versions are refused without mutation; native torn-line tolerance is explicit", () => {
    const future = path.join(scratch, "chats/chat_future.json");
    fs.writeFileSync(future, JSON.stringify({ ...teach, id: "chat_future", version: 2 }));
    try {
      const before = snapshot(scratch);
      console.log("  (expect the native future-chat refusal diagnostic below)");
      assert.equal(chatStore.getChat("chat_future"), null);
      assert.deepEqual(snapshot(scratch), before);
    } finally {
      fs.unlinkSync(future);
    }
    const format = path.join(scratch, "graph/format.json");
    const originalFormat = fs.readFileSync(format);
    try {
      fs.writeFileSync(format, '{"version":2}\n');
      const before = snapshot(scratch);
      assert.throws(() => graphStore.readNodes(), /format version 2/);
      assert.deepEqual(snapshot(scratch), before);
      fs.unlinkSync(format);
      assert.equal(graphStore.readNodes().length, cases.graph.rawNodes, "Absent stamp is v1");
      assert(!fs.existsSync(format), "Reads never write a lazy stamp");
    } finally {
      fs.writeFileSync(format, originalFormat);
    }
    const nodesFile = path.join(scratch, "graph/nodes.jsonl");
    const nodes = fs.readFileSync(nodesFile);
    try {
      fs.appendFileSync(nodesFile, '{broken\n{"id":"incomplete"}\n');
      assert.equal(graphStore.readNodes().length, cases.graph.rawNodes, "Native reader skips corrupt lines");
    } finally {
      fs.writeFileSync(nodesFile, nodes);
    }
    assert.deepEqual(snapshot(scratch), originalData);
    assert.deepEqual(snapshot(fixtures), originalFixtures);
  });
  console.log(`PASS: ${checks} native reference checks at ${manifest.commit}. Android interoperability is not tested.`);
} finally {
  fs.rmSync(scratch, { recursive: true, force: true });
}
