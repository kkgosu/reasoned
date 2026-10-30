#!/usr/bin/env node
// Explicit synthetic producer/reader check; opt-in install uses a fresh disposable desktop library.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createRequire } from "node:module";
import { createHash } from "node:crypto";

const [phase, desktopArg, outputArg, ...extra] = process.argv.slice(2);
if (!["prepare", "verify", "install"].includes(phase) || !desktopArg || !outputArg || extra.length)
	throw Error("Usage: node scripts/check-learnharness-transfer-export.mjs prepare|verify|install <desktop-checkout> <fresh-synthetic-directory>");
const desktop = path.resolve(desktopArg), output = path.resolve(outputArg);
const marker = "synthetic-transfer-export-v1";
if (phase === "prepare") fs.mkdirSync(output); // Must be new; never an installed library.
else assert.equal(fs.readFileSync(path.join(output, "fixture-kind.txt"), "utf8"), marker);
process.env.LH_DATA_DIR = path.join(output, phase === "prepare" ? "source" : phase === "install" ? "installed" : "returned");
process.env.LH_AUTH_FILE = path.join(output, "unused-auth.json");
let network = 0;
globalThis.fetch = async () => { network++; throw Error("Provider/network requests forbidden"); };
const load = relative => import(pathToFileURL(path.join(desktop, relative)).href);
const { downloadExport, previewExport } = await load("src/store/lesson-export.ts");
const root = process.env.LH_DATA_DIR;
fs.mkdirSync(path.join(root, "chats"), { recursive: true });
fs.mkdirSync(path.join(root, "graph"), { recursive: true });
if (phase === "prepare") {
	const raw = JSON.stringify({ version: 1, id: "chat_transfer_fixture", title: "Authored transfer check", createdAt: "2026-10-30T00:00:00Z", updatedAt: "2026-10-30T00:00:00Z",
		messages: [{ id: "u_fixture", role: "user", content: "Authored offline transfer test, not learner evidence.", timestamp: 1 }], opaque: { preserve: "Копия 🧪" } }, null, "\t") + "\n";
	const node = id => ({ id, name: id, statement: id, aliases: [], createdAt: "2026-10-30", provenance: [{ chatId: "chat_transfer_fixture", messageId: "u_fixture" }], opaque: true });
	const nodes = [node("n_fixture_1"), node("n_fixture_2")].map(n => JSON.stringify(n)).join("\n") + "\n";
	const chains = JSON.stringify({ id: "c_fixture", premises: ["n_fixture_1"], conclusion: "n_fixture_2", createdAt: "2026-10-30", provenance: [{ chatId: "chat_transfer_fixture", messageId: "u_fixture" }] }) + "\n";
	fs.writeFileSync(path.join(root, "chats/chat_transfer_fixture.json"), raw);
	fs.writeFileSync(path.join(root, "graph/nodes.jsonl"), nodes); fs.writeFileSync(path.join(root, "graph/chains.jsonl"), chains);
	const preview = previewExport(root, ["chat_transfer_fixture"]);
	const file = downloadExport(root, ["chat_transfer_fixture"], "zip", { revision: preview.revision, files: [], allowIncomplete: true });
	fs.writeFileSync(path.join(output, "desktop.zip"), file.bytes);
	fs.writeFileSync(path.join(output, "original-chat.json"), raw);
	fs.writeFileSync(path.join(output, "original-nodes.jsonl"), nodes); fs.writeFileSync(path.join(output, "original-chains.jsonl"), chains);
	fs.writeFileSync(path.join(output, "fixture-kind.txt"), marker);
	console.log("Prepared synthetic desktop ZIP; run NativeTransferPackageTest with REASONED_NATIVE_TRANSFER_DIR=" + output);
} else {
	const { unzipSync } = createRequire(path.join(desktop, "package.json"))("fflate");
	const bytes = fs.readFileSync(path.join(output, "android.zip"));
	assert.ok(bytes.length <= 32 * 1024 * 1024);
	const expected = new Map([
		["chats/chat_transfer_fixture.json", fs.readFileSync(path.join(output, "original-chat.json"))],
		["graph/nodes.jsonl", fs.readFileSync(path.join(output, "original-nodes.jsonl"))],
		["graph/chains.jsonl", fs.readFileSync(path.join(output, "original-chains.jsonl"))],
	]);
	let members = 0;
	// This is a check of our JVM test's output only. Still bound member allocation and
	// reject duplicates/unknowns before decompression; never a general package importer.
	const seen = new Set();
	const files = unzipSync(bytes, { filter: entry => {
		assert.ok(++members <= 4 && !seen.has(entry.name)); seen.add(entry.name);
		assert.ok(entry.name === "manifest.json" ? entry.originalSize <= 524288 : expected.has(entry.name) && entry.originalSize === expected.get(entry.name).length);
		return true;
	} });
	assert.equal(members, 4);
	const manifest = JSON.parse(Buffer.from(files["manifest.json"]).toString("utf8"));
	assert.equal(manifest.format, "learnharness-transfer"); assert.equal(manifest.nativeChatVersion, 1); assert.equal(manifest.nativeGraphVersion, 1);
	assert.deepEqual(manifest.chats, ["chat_transfer_fixture"]); assert.equal(manifest.report.continuationAuthorized, false);
	assert.equal(manifest.files.length, expected.size);
	for (const [name, original] of expected) {
		assert.deepEqual(Buffer.from(files[name]), original);
		const item = manifest.files.find(f => f.path === name);
		assert.equal(item.bytes, original.length); assert.equal(item.sha256, createHash("sha256").update(original).digest("hex"));
		if (phase === "verify") fs.writeFileSync(path.join(root, name), original); // Disposable scratch, fixed names only.
	}
	if (phase === "install") {
		assert.deepEqual(fs.readdirSync(path.join(root, "chats")), []);
		assert.deepEqual(fs.readdirSync(path.join(root, "graph")), []);
		const { previewImport, installImport, importPause, importVersions } = await load("src/store/lesson-import.ts");
		const preview = previewImport(root, bytes); assert.deepEqual(preview.blockers, []);
		assert.deepEqual(installImport(root, bytes, { revision: preview.revision, choices: {}, acknowledged: true }).installed, ["chat_transfer_fixture"]);
		assert.ok(importPause(root, "chat_transfer_fixture")); assert.equal(importVersions(root).length, 1);
		assert.deepEqual(installImport(root, bytes, { revision: previewImport(root, bytes).revision, choices: {}, acknowledged: true }).installed, []);
		for (const [name, original] of expected) assert.deepEqual(fs.readFileSync(path.join(root, name)), original);
	}
	const { chatStore } = await load("src/store/chats.ts");
	const { graphStore } = await load("src/store/graph.ts");
	const { getGraph } = await load("src/core/graph.ts");
	assert.equal(chatStore.getChat("chat_transfer_fixture").opaque.preserve, "Копия 🧪");
	const folded = getGraph(graphStore);
	assert.equal(folded.nodes.length, 2); assert.equal(folded.chains.length, 1);
	if (phase === "install") {
		assert.ok(chatStore.pendingImport("chat_transfer_fixture"));
		assert.deepEqual(chatStore.getChat("chat_transfer_fixture").settings, {});
		const preview = previewExport(root, manifest.chats);
		const returned = downloadExport(root, manifest.chats, "zip", { revision: preview.revision, files: [], allowIncomplete: true });
		const returnedMembers = unzipSync(returned.bytes);
		for (const [name, original] of expected) assert.deepEqual(Buffer.from(returnedMembers[name]), original);
		fs.writeFileSync(path.join(output, "desktop-reexport.zip"), returned.bytes);
		console.log("PASS: Android ZIP → real desktop receiver installation/reimport → explicit-input pause and graph fold → native ZIP re-export, byte-preserved; zero provider requests. Synthetic source-free store scope only, not browser/Electron or full-transfer acceptance.");
	} else console.log("PASS: Android ZIP native payloads/inventory → real desktop chat reader/graph fold; zero provider requests. This verifier writes fixed synthetic scratch files, not a desktop receiver installation or full-transfer acceptance.");
}
assert.equal(network, 0);
