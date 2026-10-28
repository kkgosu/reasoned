package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.publicResearchAddress
import com.kkgosu.reasoned.api.publicResearchUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class ResearchWebPolicyTest {
    private fun rejected(block: () -> Unit) {
        try {
            block(); fail("Unsafe/invalid data accepted")
        } catch (_: ProviderFailure) {
        }
    }

    @Test
    fun publicWebPolicyRejectsLiteralAndResolvedPrivateAndTransitionAddresses() {
        for (url in listOf(
            "file:///etc/passwd",
            "http://example.com",
            "https://localhost",
            "https://foo.local/a",
            "https://127.0.0.1",
            "https://127.1",
            "https://10.0.0.1",
            "https://169.254.169.254/latest",
            "https://100.100.100.200",
            "https://192.168.1.1",
            "https://[::1]",
            "https://[::ffff:127.0.0.1]",
            "https://example.com:8443",
            "https://user:password@example.com",
            "https://[2002:7f00:1::]"
        )) rejected { publicResearchUrl(url) }
        for (ip in listOf(
            "0.0.0.0",
            "127.0.0.1",
            "10.1.2.3",
            "172.31.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "198.18.0.1",
            "203.0.113.1",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2002:7f00:1::1"
        ))
            assertFalse(ip, publicResearchAddress(InetAddress.getByName(ip)))
        assertTrue(publicResearchAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(publicResearchAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertEquals(
            "https://example.com/article",
            publicResearchUrl("https://example.com/article#heading").toString()
        )
    }
}
