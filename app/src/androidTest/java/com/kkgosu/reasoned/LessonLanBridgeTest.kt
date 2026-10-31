package com.kkgosu.reasoned

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.LanPairing
import com.kkgosu.reasoned.api.LessonLan
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTransferPackage
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.storage.TransferReceiptKey
import com.kkgosu.reasoned.storage.previewTransfer
import com.kkgosu.reasoned.storage.transferReceipt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in loopback-host fixture only. Never points at an installed desktop library or a model. */
@RunWith(AndroidJUnit4::class)
class LessonLanBridgeTest {
    @Test fun actualPinnedHttpsTransfersBothWaysThroughRuntimeAndReconcilesReceipt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        val fixture = File(app.filesDir, "lesson-lan-fixture.json")
        assumeTrue("Run scripts/check-learnharness-lan.mjs with an isolated desktop fixture", fixture.isFile)
        val raw = JSONObject(fixture.readText()).getString("connection")
        val api = LessonLan(app, "lesson-lan-fixture")
        try {
            // The real Android TLS stack, not a trust-all mock, must reject the wrong QR pin.
            val wrong = raw.replace(Regex("sha256=[a-f0-9]{64}"), "sha256=" + "0".repeat(64))
            try { api.pair(LanPairing(wrong), "Synthetic emulator"); fail("Wrong certificate accepted") } catch (e: java.io.IOException) { assertTrue("Expected TLS pin rejection", e.cause is javax.net.ssl.SSLHandshakeException) }
            val connection = api.pair(LanPairing(raw), "Synthetic emulator")
            assertEquals(connection.fingerprint, api.connection()!!.fingerprint)
            val listed = api.lessons(connection)
            assertEquals(connection.id, listed.getString("id")); assertEquals("chat_lan_desktop", listed.getJSONArray("lessons").getJSONObject(0).getString("id"))
            val bytes = api.export(connection, listOf("chat_lan_desktop"))
            val native = NativeTransferPackage.read(bytes)
            assertEquals(1, native.chats.size); assertEquals("PRESERVE_LAN", native.chats.single().snapshot().getString("opaque"))
            SessionStore(app, null).use { store ->
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ -> fail("LAN transfer invoked a provider"); error("unreachable") })
                try {
                    val receiptKey = TransferReceiptKey(UUID.randomUUID().toString(), connection.fingerprint)
                    assertEquals(listOf("chat_lan_desktop"), runtime.installTransfer(bytes, store.previewTransfer(bytes), emptySet(), receiptKey))
                    assertEquals(listOf("chat_lan_desktop"), store.transferReceipt(receiptKey)!!.second)
                    val phone = NativeChat.parse(JSONObject().put("id", "chat_lan_phone").put("title", "Authored phone LAN fixture")
                        .put("createdAt", "2026-01-01T00:00:00Z").put("updatedAt", "2026-01-01T00:00:00Z").put("opaque", "PHONE_PRESERVE")
                        .put("messages", JSONArray().put(JSONObject().put("id", "phone_u").put("role", "user").put("content", "Synthetic phone lesson").put("timestamp", 1))).toString())
                    store.saveNative(RuntimeSession(id = phone.id, goal = "Authored phone LAN fixture", model = "", connection = "imported", nativeRecord = phone))
                    val exported = runtime.exportTransfer(listOf("chat_lan_desktop", "chat_lan_phone"))
                    assertEquals(native.nodes, NativeTransferPackage.read(exported).nodes)
                    val p = api.preview(connection, exported)
                    assertEquals(0, p.getJSONArray("blockers").length())
                    val operation = UUID.randomUUID().toString()
                    val first = api.install(connection, exported, operation, p.getString("revision"), JSONObject())
                    assertEquals("chat_lan_phone", first.getJSONArray("installed").getString(0))
                    val receipt = api.receipt(connection, operation)!!
                    assertEquals(first.getJSONArray("installed").toString(), receipt.getJSONArray("installed").toString())
                    val retried = api.install(connection, exported, operation, p.getString("revision"), JSONObject())
                    assertEquals(first.getJSONArray("installed").toString(), retried.getJSONArray("installed").toString())
                    assertEquals(phone.raw, NativeTransferPackage.read(api.export(connection, listOf(phone.id))).chats.single().raw)
                } finally { runtime.stop().join() }
            }
            api.setForeground(true)
            withTimeout(5000) { api.online.first { it } }
            delay(4100) // Host checks freshness beyond its three-second presence window.
            assertTrue(api.online.value)
            api.setForeground(false)
            assertFalse(api.online.value)
        } finally { api.setForeground(false); api.forget() }
    }
}
