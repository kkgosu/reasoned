package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeGraphSelection
import com.kkgosu.reasoned.model.NativeTransferPackage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NativeTransferPackageTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1"))
        .first { it.isDirectory }
    private fun chat(name: String) = NativeChat.parse(File(fixtures, "data/chats/$name.json").readText())
    private fun graph(name: String) = File(fixtures, "data/graph/$name").readText()
    private fun simple(id: String = "chat_package") = NativeChat.parse("{\"id\":\"$id\",\"messages\":[],\"opaque\":\"Сохранить 🧪\"}\n")
    private fun members(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
        }
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        output.toByteArray()
    }
    private fun changed(bytes: ByteArray, edit: (LinkedHashMap<String, ByteArray>) -> Unit) = zip(members(bytes).apply(edit))
    private fun manifest(files: LinkedHashMap<String, ByteArray>, edit: (JSONObject) -> Unit) {
        files["manifest.json"] = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
            .apply(edit).toString().toByteArray(Charsets.UTF_8)
    }
    private fun refused(bytes: ByteArray) { assertThrows(Exception::class.java) { NativeTransferPackage.read(bytes) } }
    private fun reseal(files: LinkedHashMap<String, ByteArray>) = manifest(files) { root ->
        val inventory = root.getJSONArray("files")
        for (i in 0 until inventory.length()) {
            val entry = inventory.getJSONObject(i)
            val bytes = files.getValue(entry.getString("path"))
            entry.put("bytes", bytes.size).put("sha256", MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) })
        }
    }

    /** Explicit cross-repository producer check; never loads or installs a real library. */
    @Test fun authoredDesktopExportCanBeReadAndReexportedWithoutRewritingNativePayloads() {
        val root = System.getenv("REASONED_NATIVE_TRANSFER_DIR")?.let(::File)
        org.junit.Assume.assumeTrue("Explicit synthetic export directory required", root?.isDirectory == true)
        root!!
        assertEquals("synthetic-transfer-export-v1", File(root, "fixture-kind.txt").readText())
        val contents = NativeTransferPackage.read(File(root, "desktop.zip").readBytes())
        assertEquals(listOf("chat_transfer_fixture"), contents.chats.map { it.id })
        assertArrayEquals(File(root, "original-chat.json").readBytes(), contents.chats.single().raw.toByteArray(Charsets.UTF_8))
        assertEquals(File(root, "original-nodes.jsonl").readText(), contents.nodes)
        assertEquals(File(root, "original-chains.jsonl").readText(), contents.chains)
        val returned = NativeTransferPackage.write(contents.chats, contents.nodes, contents.chains, contents.graphFormat)
        assertEquals(contents.chats.map { it.raw }, NativeTransferPackage.read(returned).chats.map { it.raw })
        File(root, "android.zip").writeBytes(returned)
    }

    @Test fun standaloneJsonUsesTheSameValidationAndPreservesTheOriginalWithoutInventingAGraph() {
        val original = chat("chat_contract_teach")
        val contents = NativeTransferPackage.read(original.raw.toByteArray(Charsets.UTF_8))
        assertEquals(original.raw, contents.chats.single().raw)
        assertEquals("", contents.nodes)
        assertEquals("", contents.chains)
        assertNull(contents.graphFormat)
        assertEquals(original.raw, NativeTransferPackage.read(NativeTransferPackage.write(contents.chats)).chats.single().raw)
        refused("{\"id\":\"../unsafe\",\"messages\":[]}".toByteArray())
        refused("{\"id\":\"chat_new\",\"version\":2,\"messages\":[]}".toByteArray())
        refused(byteArrayOf(0xc3.toByte(), 0x28))
        refused(("[".repeat(65) + "0" + "]".repeat(65)).toByteArray())
        refused(ByteArray(NativeChat.MAX_BYTES + 1) { 32 })
    }

    @Test fun nativeBytesUnknownFieldsAndSelectedGraphSurviveWithoutPullingOtherChats() {
        val chats = listOf(chat("chat_contract_teach"), chat("chat_contract_practice"))
        val nodes = graph("nodes.jsonl"); val chains = graph("chains.jsonl"); val format = graph("format.json")
        val before = chats.map { it.raw }
        val expected = NativeGraphSelection.select(chats, nodes, chains, format)
        val bytes = NativeTransferPackage.write(chats, nodes, chains, format)
        val contents = NativeTransferPackage.read(bytes)
        assertEquals(before, contents.chats.map { it.raw })
        assertEquals(format, contents.graphFormat)
        assertEquals(expected.nodes, contents.nodes.lines().filter { it.isNotBlank() })
        assertEquals(expected.chains, contents.chains.lines().filter { it.isNotBlank() })
        assertFalse(contents.nodes.contains("n_unrelated"))
        val files = members(bytes)
        assertEquals(setOf("manifest.json", "chats/${chats[0].id}.json", "chats/${chats[1].id}.json",
            "graph/nodes.jsonl", "graph/chains.jsonl", "graph/format.json"), files.keys)
        chats.forEach { assertArrayEquals(it.raw.toByteArray(Charsets.UTF_8), files.getValue("chats/${it.id}.json")) }
        assertTrue(contents.report.issues.any { it.code == "external-provenance" && it.chatId == "src_contract_notes" })
        assertEquals("partial", contents.report.json().getString("dependencyAudit"))
        assertFalse(contents.report.json().getBoolean("continuationAuthorized"))
        assertEquals(before, chats.map { it.raw })
        assertEquals(nodes, graph("nodes.jsonl")); assertEquals(chains, graph("chains.jsonl"))
    }

    @Test fun lazyVersionsWhitespaceUnicodeAndGraphFormatMetadataAreNotRewritten() {
        val chat = simple()
        val contents = NativeTransferPackage.read(NativeTransferPackage.write(listOf(chat)))
        assertEquals(chat.raw, contents.chats.single().raw)
        assertFalse(contents.chats.single().snapshot().has("version"))
        assertNull(contents.graphFormat)
        assertTrue(contents.report.issues.isEmpty())
        val node = " \t{\"id\":\"n\",\"provenance\":[{\"chatId\":\"${chat.id}\",\"messageId\":\"\"}],\"opaque\":\"x\"}\r"
        val format = " {\"version\":1,\"opaque\":{\"untouched\":true}} "
        val withGraph = NativeTransferPackage.read(NativeTransferPackage.write(listOf(chat), node, graphFormat = format))
        assertEquals(node + "\n", withGraph.nodes)
        assertEquals(format, withGraph.graphFormat)
    }

    @Test fun artifactsAndImagesStayInlineWhileMissingSourcesAndJobsAreReportedWithoutExecution() {
        val chat = chat("chat_contract_artifacts")
        val bytes = NativeTransferPackage.write(listOf(chat))
        val contents = NativeTransferPackage.read(bytes)
        assertEquals(chat.raw, contents.chats.single().raw)
        assertEquals(4, members(bytes).size) // Manifest, chat and two ledgers; no inferred attachments.
        val issues = contents.report.issues
        assertTrue(issues.any { it.code == "android-projection-unsupported" })
        assertTrue(issues.any { it.code == "external-source-not-included" && it.reference.endsWith("missing-source.pdf") })
        assertTrue(issues.any { it.code == "external-source-not-included" && it.reference.endsWith("inline-image.png") })
        assertEquals(listOf("call_pdf_pending"), issues.filter { it.code == "job-state-not-transferred" }.map { it.reference })
        assertTrue(contents.chats.single().raw.contains("<script>")) // Inert data, not a renderer.
        val forged = changed(bytes) { files -> manifest(files) { it.put("report", JSONObject()
            .put("continuationAuthorized", true).put("dependencyAudit", "complete").put("issues", JSONArray())) } }
        assertEquals(contents.report, NativeTransferPackage.read(forged).report)
    }

    @Test fun unsafeDuplicateMissingUnlistedAndCorruptMembersAreRefused() {
        val bytes = NativeTransferPackage.write(listOf(simple()))
        val payload = "chats/chat_package.json"
        for (name in listOf("../escape", "/absolute", "chats/../escape.json", "chats\\evil.json", "graph/", "auth.json", "chats/%2e%2e.json")) {
            refused(changed(bytes) { it[name] = "{}".toByteArray() })
        }
        refused(changed(bytes) { it.remove(payload) })
        refused(changed(bytes) { it.remove("manifest.json") })
        refused(changed(bytes) { it[payload] = simple("different_id").raw.toByteArray() })
        refused(changed(bytes) { it["chats/unselected.json"] = simple("unselected").raw.toByteArray() })
        refused(changed(bytes) { files -> manifest(files) { it.getJSONArray("files").getJSONObject(0).put("bytes", "1") } })
        refused(changed(bytes) { files -> manifest(files) { val list = it.getJSONArray("files"); list.put(list.getJSONObject(0)) } })
        val duplicate = zip(linkedMapOf("manifest.json" to "{}".toByteArray(), "manifest.jsox" to "{}".toByteArray()))
            .toString(Charsets.ISO_8859_1).replace("manifest.jsox", "manifest.json").toByteArray(Charsets.ISO_8859_1)
        refused(duplicate)
        refused(bytes.copyOf(40)) // Interrupted local header/member, not just an omitted ZIP directory.
    }

    @Test fun validHashesDoNotAuthorizeChangedIdentitiesUnselectedAssertionsOrUnknownNativeVersions() {
        val bytes = NativeTransferPackage.write(listOf(simple()))
        val renamed = changed(bytes) { files ->
            files["chats/chat_package.json"] = simple("different_id").raw.toByteArray(); reseal(files)
        }
        assertEquals("Transfer chat identity mismatch", assertThrows(Exception::class.java) {
            NativeTransferPackage.read(renamed)
        }.message)
        val unrelated = changed(bytes) { files ->
            files["graph/nodes.jsonl"] = "{\"id\":\"external\",\"provenance\":[{\"chatId\":\"other\",\"messageId\":\"\"}]}\n".toByteArray()
            reseal(files)
        }
        assertEquals("Unselected graph assertions", assertThrows(Exception::class.java) {
            NativeTransferPackage.read(unrelated)
        }.message)
        refused(changed(bytes) { files ->
            files["chats/chat_package.json"] = simple().snapshot().put("version", 2).toString().toByteArray(); reseal(files)
        })
        val deep = "{\"id\":\"n\",\"provenance\":[],\"opaque\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertEquals("Transfer JSON structure limit", assertThrows(Exception::class.java) {
            NativeTransferPackage.write(listOf(simple()), nodes = deep)
        }.message)
        // org.json's relaxed quotes/comments must not bypass the nesting preflight.
        for (raw in listOf("{'a\\\"':" + "[".repeat(70) + "0" + "]".repeat(70) + "}", "{/* comment */\"a\":1}")) {
            refused(changed(bytes) { it["manifest.json"] = raw.toByteArray() })
        }
    }

    @Test fun unknownVersionsInvalidIdentitiesAndNestingOrInflationLimitsAreRefused() {
        val bytes = NativeTransferPackage.write(listOf(simple()))
        for (version in listOf<Any>(2, "1", true, JSONObject.NULL)) {
            refused(changed(bytes) { files -> manifest(files) { it.put("version", version) } })
        }
        for (key in listOf("nativeChatVersion", "nativeGraphVersion")) {
            refused(changed(bytes) { files -> manifest(files) { it.put(key, 2) } })
            refused(changed(bytes) { files -> manifest(files) { it.put(key, "1") } })
        }
        refused(changed(bytes) { files -> manifest(files) { it.put("chats", JSONArray().put("chat_package").put("chat_package")) } })
        refused(changed(bytes) { files -> manifest(files) { it.put("chats", JSONArray().put("../unsafe")) } })
        refused(changed(bytes) { it["manifest.json"] = "{\"nested\":".plus("[".repeat(65)).plus("0").plus("]".repeat(65)).plus("}").toByteArray() })
        refused(changed(bytes) { it["manifest.json"] = byteArrayOf(0xc3.toByte(), 0x28) })
        refused(zip(mapOf("graph/nodes.jsonl" to ByteArray(NativeChat.MAX_BYTES + 1) { 32 })))
        refused(ByteArray(NativeTransferPackage.MAX_BYTES + 1))
        assertThrows(Exception::class.java) { NativeTransferPackage.write(emptyList()) }
        assertThrows(Exception::class.java) { NativeTransferPackage.write(listOf(simple(), simple())) }
        assertThrows(Exception::class.java) { NativeTransferPackage.write(listOf(simple("../unsafe"))) }
        val deep = NativeChat.parse("{\"id\":\"deep\",\"messages\":[],\"opaque\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}")
        assertThrows(Exception::class.java) { NativeTransferPackage.write(listOf(deep)) }
        val surrogate = NativeChat.parse("{\"id\":\"surrogate\",\"messages\":[],\"opaque\":\"\uD800\"}")
        assertThrows(Exception::class.java) { NativeTransferPackage.write(listOf(surrogate)) }
    }
}
