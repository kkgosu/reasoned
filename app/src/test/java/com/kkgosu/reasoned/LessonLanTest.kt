package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.LanConnection
import com.kkgosu.reasoned.api.LanPairing
import com.kkgosu.reasoned.api.privateLanAddress
import com.kkgosu.reasoned.api.lanJson
import org.junit.Assert.*
import org.junit.Test

class LessonLanTest {
    private val uri = "learnharness-lan://pair?host=192.168.1.2&port=42000&sha256=${"a".repeat(64)}&key=${"A".repeat(43)}"
    @Test fun pairingIsBoundedPrivatePinnedAndDoesNotResolveDnsOrExposeSecrets() {
        val pairing = LanPairing(uri)
        assertEquals("192.168.1.2", pairing.host)
        assertEquals(42000, pairing.port)
        assertFalse(pairing.toString().contains(pairing.key))
        for (host in listOf("10.0.2.2", "172.16.1.2", "172.31.255.254", "192.168.1.2")) assertTrue(privateLanAddress(host))
        for (host in listOf("127.0.0.1", "169.254.1.1", "8.8.8.8", "10.01.1.1", "10.0.0.256", "172.32.0.1", "localhost", "a.local", "::1", "192.168.1.2.evil.test")) assertFalse(host, privateLanAddress(host))
        val bad = listOf(uri + "&key=second", uri + "#fragment", uri + "&unknown=x", uri.replace("192.168.1.2", "8.8.8.8"), uri.replace("192.168.1.2", "name.local"), uri.replace("42000", "443"), uri.replace("42000", "65536"), uri.replace("learnharness-lan", "https"), uri.replace("//pair", "//pair/path"), uri.replace("sha256=" + "a".repeat(64), "sha256=bad"), uri + "a".repeat(2048))
        for (raw in bad) { try { LanPairing(raw); fail("Accepted unsafe pairing") } catch (_: IllegalArgumentException) { } }
        val connection = LanConnection(pairing.host, pairing.port, pairing.fingerprint, pairing.key)
        assertEquals(connection.token, LanConnection.parse(connection.json()).token)
        assertFalse(connection.toString().contains(connection.token))
        try { lanJson(("{\"nested\":".repeat(65) + "0" + "}".repeat(65)).toByteArray()); fail("Deep LAN JSON") } catch (_: IllegalArgumentException) { }
        try { lanJson(byteArrayOf(0xff.toByte())); fail("Malformed UTF-8") } catch (_: java.nio.charset.CharacterCodingException) { }
    }
}
