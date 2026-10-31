package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Native v1 payloads plus transfer bookkeeping, never an installer or permission to continue.
 * Callers must capture stopped, coherent snapshots. No paths are resolved, files extracted,
 * jobs resumed or providers called. Unknown native data stays inside its original record.
 */
internal object NativeTransferPackage {
    private const val FORMAT = "learnharness-transfer"
    private const val MANIFEST_LIMIT = 512 * 1024
    const val MAX_BYTES = 32 * 1024 * 1024 // Both compressed input and total expanded payload.
    private val chatPath = Regex("chats/[A-Za-z0-9_-]{1,200}\\.json")

    data class Issue(val code: String, val chatId: String = "", val messageId: String = "", val reference: String = "")
    data class Report(val issues: List<Issue>) {
        fun json(): JSONObject = JSONObject()
            .put("dependencyAudit", "partial")
            .put("externalFiles", "not-included")
            .put("coverage", "not-included")
            .put("localCheckpoints", "not-included")
            .put("continuationAuthorized", false)
            .put("issues", JSONArray(issues.map { issue -> JSONObject()
                .put("code", issue.code).put("chatId", issue.chatId)
                .put("messageId", issue.messageId).put("reference", issue.reference) }))
    }
    data class Contents(
        val chats: List<NativeChat>, val nodes: String, val chains: String, val graphFormat: String?,
        val report: Report,
    )

    fun write(chats: List<NativeChat>, nodes: String = "", chains: String = "", graphFormat: String? = null): ByteArray {
        require(chats.size in 1..100 && chats.sumOf { it.raw.length.toLong() } <= MAX_BYTES) { "Transfer chat limit" }
        chats.forEach { boundedJson(it.raw) }
        val selection = NativeGraphSelection.select(chats, boundedJson(nodes), boundedJson(chains), graphFormat?.let(::boundedJson))
        val files = linkedMapOf<String, ByteArray>()
        chats.forEach { chat ->
            val path = "chats/${chat.id}.json"
            require(chatPath.matches(path)) { "Unsupported transfer chat filename" }
            files[path] = utf8(chat.raw)
        }
        fun lines(values: List<String>) = if (values.isEmpty()) "" else values.joinToString("\n", postfix = "\n")
        files["graph/nodes.jsonl"] = utf8(lines(selection.nodes))
        files["graph/chains.jsonl"] = utf8(lines(selection.chains))
        graphFormat?.let { files["graph/format.json"] = utf8(it) }
        require(files.values.sumOf { it.size.toLong() } <= MAX_BYTES - MANIFEST_LIMIT) { "Transfer payload limit" }
        files.forEach { (name, bytes) -> validateText(name, bytes) }
        val manifest = utf8(JSONObject().put("format", FORMAT).put("version", 1)
            .put("nativeChatVersion", 1).put("nativeGraphVersion", 1)
            .put("chats", JSONArray(chats.map { it.id }))
            .put("files", JSONArray(files.map { (name, bytes) -> JSONObject().put("path", name)
                .put("bytes", bytes.size).put("sha256", sha256(bytes)) }))
            .put("report", report(chats, selection).json()).toString())
        require(manifest.size <= MANIFEST_LIMIT) { "Transfer manifest limit" }
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in mapOf("manifest.json" to manifest) + files) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
            }
            output.toByteArray().also { require(it.size <= MAX_BYTES) { "Transfer ZIP limit" } }
        }
    }

    /** Accept one native JSON lesson or verify ZIP members/hashes. Neither authorizes execution. */
    fun read(bytes: ByteArray): Contents {
        require(bytes.size <= MAX_BYTES) { "Transfer file limit" }
        if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
            val chat = NativeChat.parse(validateText("chat.json", bytes))
            return read(write(listOf(chat)))
        }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && allowed(name) && name !in files && files.size < 104) { "Invalid transfer member" }
                val limit = if (name == "manifest.json") MANIFEST_LIMIT else NativeChat.MAX_BYTES
                // Never trust ZIP size headers; bound inflation, including the first oversized member.
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES && data.size().toLong() + count <= limit) { "Transfer expansion limit" }
                    data.write(buffer, 0, count)
                }
                files[name] = data.toByteArray()
                zip.closeEntry() // CRC verification, not extraction.
            }
        }
        val manifest = JSONObject(validateText("manifest.json", files.remove("manifest.json") ?: error("Missing manifest")))
        require(manifest.get("format") == FORMAT && listOf("version", "nativeChatVersion", "nativeGraphVersion").all { key ->
            val version = manifest.get(key)
            version is Number && version.toDouble() == 1.0
        }) { "Unsupported transfer format" }
        val ids = manifest.getJSONArray("chats")
        require(ids.length() in 1..100) { "Transfer chat count" }
        val chatIds = (0 until ids.length()).map { ids.get(it) as? String ?: error("Invalid transfer chat identity") }
        require(chatIds.toSet().size == chatIds.size && chatIds.all { chatPath.matches("chats/$it.json") }) { "Invalid transfer chat identities" }
        val expected = chatIds.map { "chats/$it.json" }.toSet() + setOf("graph/nodes.jsonl", "graph/chains.jsonl") +
            (if ("graph/format.json" in files) setOf("graph/format.json") else emptySet())
        require(files.keys == expected) { "Missing or unselected transfer payload" }
        val inventory = manifest.getJSONArray("files")
        require(inventory.length() == files.size) { "Invalid transfer inventory" }
        val seen = mutableSetOf<String>()
        inventory.objects().forEach { file ->
            val name = file.get("path") as? String ?: error("Invalid transfer path")
            val data = files[name] ?: error("Missing transfer payload")
            val size = file.get("bytes")
            require(seen.add(name) && size is Number && size.toDouble() == data.size.toDouble() &&
                file.get("sha256") == sha256(data)) { "Transfer payload integrity failure" }
        }
        val text = files.mapValues { (name, data) -> validateText(name, data) }
        val chats = chatIds.map { id -> NativeChat.parse(text.getValue("chats/$id.json")).also {
            require(it.id == id) { "Transfer chat identity mismatch" }
        } }
        val nodes = text.getValue("graph/nodes.jsonl")
        val chains = text.getValue("graph/chains.jsonl")
        val format = text["graph/format.json"]
        val selection = NativeGraphSelection.select(chats, nodes, chains, format)
        fun lines(raw: String) = raw.splitToSequence('\n').filter { it.isNotBlank() }.toList()
        require(selection.nodes == lines(nodes) && selection.chains == lines(chains)) { "Unselected graph assertions" }
        return Contents(chats, nodes, chains, format, report(chats, selection))
    }

    private fun allowed(name: String) = name in setOf("manifest.json", "graph/nodes.jsonl", "graph/chains.jsonl", "graph/format.json") || chatPath.matches(name)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun utf8(text: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun validateText(name: String, bytes: ByteArray): String {
        require(bytes.size <= if (name == "manifest.json") MANIFEST_LIMIT else NativeChat.MAX_BYTES) { "Transfer member limit" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return boundedJson(text)
    }

    internal fun boundedJson(text: String): String {
        require(text.length <= NativeChat.MAX_BYTES) { "Transfer text limit" }
        // Bound nesting before org.json parses JSON/JSONL, including opaque fields.
        var depth = 0
        var structures = 0
        var quoted = false
        var escaped = false
        var previous: Char? = null
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') {
                    quoted = false; previous = char
                }
            } else {
                when (char) {
                    '"' -> { require(previous == null || previous in "{[:,") { "Invalid transfer JSON string" }; quoted = true }
                    '{', '[' -> { depth++; structures++; require(depth <= 64 && structures <= 100_000) { "Transfer JSON structure limit" } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Invalid transfer JSON structure" } }
                    '\'', '/', '#', '\\' -> error("Unsupported transfer JSON syntax") // No relaxed quotes/comments that can hide nesting.
                }
                if (!char.isWhitespace()) previous = char
            }
        }
        require(depth == 0 && !quoted) { "Incomplete transfer JSON" }
        return text
    }

    // ponytail: known native references only; audit additional source/artifact schemas before claiming completeness.
    private fun report(chats: List<NativeChat>, graph: NativeGraphSelection.Selection): Report {
        val issues = linkedSetOf<Issue>()
        fun add(issue: Issue) { if (issues.size < 1000) issues += issue else issues += Issue("report-truncated") }
        graph.externalProvenance.forEach { add(Issue("external-provenance", it.chatId, it.messageId)) }
        chats.forEach { chat ->
            try {
                val projection = NativeTeaching.project(RuntimeSession(id = chat.id, goal = "", model = "", nativeRecord = chat,
                    nativeDisplayIds = chat.parts().mapIndexed { index, part -> part to index + 1L }.toMap()))
                if (projection.items.any { it.text.length > 128 * 1024 }) add(Issue("android-display-limit", chat.id))
            } catch (_: ProviderFailure) { add(Issue("android-projection-unsupported", chat.id)) }
            val messages = chat.snapshot().getJSONArray("messages").objects()
            val delivered = messages.filter { it.optString("customType") == "subagent-steer" }
                .mapNotNull { it.optJSONObject("details")?.optString("toolCallId") }.toSet()
            val failed = messages.filter { it.optString("role") == "toolResult" && it.optBoolean("isError") }
                .map { it.optString("toolCallId") }.toSet()
            messages.forEach { message ->
                if (message.optString("role") == "assistant") message.optJSONArray("content")?.objects()
                    ?.filter { it.optString("type") == "toolCall" }?.forEach { call ->
                        val tool = call.optString("name")
                        if (tool in setOf("read", "pdf")) add(Issue("external-source-not-included", chat.id,
                            message.getString("id"), call.optJSONObject("arguments")?.optString("path").orEmpty()))
                        if (tool in setOf("research", "demo", "visual", "pdf") && call.optString("id") !in delivered &&
                            call.optString("id") !in failed && message.optString("stopReason") !in setOf("error", "aborted"))
                            add(Issue("job-state-not-transferred", chat.id, message.getString("id"), call.optString("id")))
                    }
                if (message.optString("customType") == "subagent-steer") {
                    val result = message.optJSONObject("details")?.optJSONObject("result")
                    result?.optString("path")?.takeIf { it.isNotEmpty() }?.let {
                        add(Issue("external-source-not-included", chat.id, message.getString("id"), it))
                    }
                }
            }
        }
        return Report(issues.toList())
    }
}
