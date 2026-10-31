// Isolated real HTTPS transport acceptance. Requires a disposable emulator and built APKs.
// node --import ../learnharness-desktop/node_modules/tsx/dist/loader.mjs scripts/check-learnharness-lan.mjs ../learnharness-desktop emulator-5554
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { setTimeout as delay } from "node:timers/promises";

const [desktop, serial] = process.argv.slice(2);
assert.ok(desktop && /^emulator-[0-9]+$/.test(serial ?? ""), "Pass a desktop checkout and explicit disposable emulator serial");
const execute = promisify(execFile);
const adb = (...args) => execute("adb", ["-s", serial, ...args], { timeout: 25000, maxBuffer: 65536, windowsHide: true });
assert.equal((await adb("shell", "getprop", "ro.kernel.qemu")).stdout.trim(), "1", "Not an emulator");
// Enable Wi-Fi on this disposable emulator before running; do not race network startup.
await adb("shell", "ip", "route", "get", "10.0.2.2");
const root = fs.mkdtempSync(path.join(os.tmpdir(), "lh-lan-bridge-"));
const originalFetch = globalThis.fetch;
let outbound = 0;
globalThis.fetch = async () => { outbound++; throw new Error("Provider/public fetch forbidden in LAN fixture"); };
const { createLessonLan } = await import(pathToFileURL(path.resolve(desktop, "src/server/lesson-lan.ts")).href);
const record = { version: 1, id: "chat_lan_desktop", title: "Authored desktop LAN fixture", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z", opaque: "PRESERVE_LAN", messages: [{ id: "desktop_u", role: "user", content: "Synthetic desktop lesson", timestamp: 1 }] };
fs.mkdirSync(path.join(root, "chats")); fs.mkdirSync(path.join(root, "graph"));
const raw = JSON.stringify(record, null, "\t") + "\n";
fs.writeFileSync(path.join(root, "chats/chat_lan_desktop.json"), raw);
const nodes = JSON.stringify({ id: "n_lan", name: "Authored LAN node", statement: "Synthetic statement", aliases: [], createdAt: "2026-01-01T00:00:00Z", provenance: [{ chatId: record.id, messageId: "desktop_u" }] }) + "\n";
fs.writeFileSync(path.join(root, "graph/nodes.jsonl"), nodes);
fs.writeFileSync(path.join(root, "graph/chains.jsonl"), "");
fs.writeFileSync(path.join(root, "graph/format.json"), '{"version":1,"opaque":"LAN_FORMAT"}');
const lan = createLessonLan(root, { name: "Synthetic LAN acceptance library", lessons: () => fs.readdirSync(path.join(root, "chats")).map(file => JSON.parse(fs.readFileSync(path.join(root, "chats", file), "utf8"))).map(c => ({ id: c.id, title: c.title })), idle() {}, changed() {} });
const remote = "/data/local/tmp/reasoned-lan-fixture.json";
const pkg = "com.kkgosu.reasoned.debug";
try {
	await lan.start("127.0.0.1");
	const pairing = await lan.pairing("127.0.0.1");
	const connection = new URL(pairing.connection); connection.searchParams.set("host", "10.0.2.2");
	const fixture = path.join(root, "fixture.json");
	fs.writeFileSync(fixture, JSON.stringify({ connection: connection.href }), { mode: 0o600 });
	await adb("push", fixture, remote);
	await adb("shell", "run-as", pkg, "cp", remote, "files/lesson-lan-fixture.json");
	const result = await adb("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.kkgosu.reasoned.LessonLanBridgeTest", "com.kkgosu.reasoned.debug.test/androidx.test.runner.AndroidJUnitRunner");
	process.stdout.write(result.stdout);
	assert.match(result.stdout, /OK \(1 test\)/, "Instrumentation did not report success");
	assert.doesNotMatch(result.stdout, /FAILURES|INSTRUMENTATION_FAILED|AssumptionViolated|status: -3/);
	assert.equal(fs.readFileSync(path.join(root, "chats/chat_lan_desktop.json"), "utf8"), raw);
	assert.equal(fs.readFileSync(path.join(root, "graph/nodes.jsonl"), "utf8"), nodes);
	assert.equal(JSON.parse(fs.readFileSync(path.join(root, "chats/chat_lan_phone.json"), "utf8")).opaque, "PHONE_PRESERVE");
	assert.equal(fs.readdirSync(path.join(root, "lesson-transfers/receipts")).length, 1);
	assert.equal(outbound, 0);
	assert.equal(lan.status().devices[0]?.online, true, "Foreground device heartbeat did not stay fresh");
	await delay(3200);
	assert.equal(lan.status().devices[0]?.online, false, "Stopped/background device must go offline");
	console.log("PASS: real pinned HTTPS desktop → Android runtime import/receipt → Android ZIP → desktop install/receipt/retry → Android download; native history/graph preserved; foreground heartbeat/stop verified; zero provider/public fetches. Synthetic stores/transport only, not camera/browser/Electron UI acceptance.");
} finally {
	await lan.stop();
	await adb("shell", "run-as", pkg, "rm", "-f", "files/lesson-lan-fixture.json").catch(() => {});
	await adb("shell", "rm", "-f", remote).catch(() => {});
	globalThis.fetch = originalFetch;
	fs.rmSync(root, { recursive: true, force: true });
}
