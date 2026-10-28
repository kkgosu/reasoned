package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.ChatGptAuth
import com.kkgosu.reasoned.api.LoopbackSignIn
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.oauthChallenge
import com.kkgosu.reasoned.api.verifyChatGptIdentity
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Date

class OAuthTest {
    private val pair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
    private val jwks
        get() = JWKSet(
            RSAKey.Builder(pair.public as RSAPublicKey).keyID("fixture").build()
        ).toString()

    private fun signed(
        client: String = "oaiapp_fixture",
        nonce: String = "nonce",
        issuer: String = ChatGptAuth.AUTH,
        expiration: Long = System.currentTimeMillis() + 120_000,
        subject: String = "fixture-user"
    ): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(client).subject(subject)
            .issueTime(Date()).expirationTime(Date(expiration)).claim("nonce", nonce)
            .claim("email", "fixture@example.invalid").build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(),
            claims
        ).apply { sign(RSASSASigner(pair.private)) }.serialize()
    }

    private fun rejects(reason: String? = null, action: () -> Unit) {
        try {
            action(); fail("Invalid input accepted")
        } catch (failure: ProviderFailure) {
            if (reason != null) assertEquals(reason, failure.reason)
        }
    }

    private fun callback(uri: String): String {
        val url = uri.toHttpUrl()
        return Socket("127.0.0.1", url.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream()
                .write("GET ${url.encodedPath}?${url.encodedQuery} HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }

    @Test
    fun pkceAndSignedIdentityRejectWrongNonceAudienceIssuerExpiryAndSignature() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            oauthChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
        val token = signed()
        assertEquals(
            "fixture-user",
            verifyChatGptIdentity(token, jwks, "oaiapp_fixture", "nonce", "fixture-access").first
        )
        rejects { verifyChatGptIdentity(token, jwks, "oaiapp_other", "nonce", "fixture-access") }
        rejects { verifyChatGptIdentity(token, jwks, "oaiapp_fixture", "wrong", "fixture-access") }
        rejects {
            verifyChatGptIdentity(
                signed(issuer = "https://example.invalid"),
                jwks,
                "oaiapp_fixture",
                "nonce",
                "fixture-access"
            )
        }
        rejects {
            verifyChatGptIdentity(
                signed(expiration = System.currentTimeMillis() - 10_000),
                jwks,
                "oaiapp_fixture",
                "nonce",
                "fixture-access"
            )
        }
        rejects {
            verifyChatGptIdentity(
                token.substringBeforeLast('.') + ".AAAA",
                jwks,
                "oaiapp_fixture",
                "nonce",
                "fixture-access"
            )
        }
        rejects {
            verifyChatGptIdentity(
                "eyJhbGciOiJub25lIn0.e30.",
                jwks,
                "oaiapp_fixture",
                "nonce",
                "fixture-access"
            )
        }
    }

    @Test
    fun loopbackRejectsUnmatchedOrDuplicateStateAndCancellationClosesPort() = runBlocking {
        val listener = LoopbackSignIn()
        val pending = async { listener.await("expected", null) }
        val base = listener.redirectUri
        withContext(Dispatchers.IO) {
            assertTrue(
                callback("$base?state=wrong&code=fixture&client_id=oaiapp_fixture").startsWith(
                    "HTTP/1.1 400"
                )
            )
            assertTrue(
                callback("$base?state=expected&state=wrong&code=fixture&client_id=oaiapp_fixture").startsWith(
                    "HTTP/1.1 400"
                )
            )
            assertTrue(
                callback("$base?state=expected&code=fixture&client_id=oaiapp_fixture").startsWith(
                    "HTTP/1.1 200"
                )
            )
        }
        val result = withTimeout(3000) { pending.await() }
        assertEquals("fixture", result.code)
        assertEquals("oaiapp_fixture", result.clientId)
        listener.close()
        val cancelled = LoopbackSignIn()
        val wait = async { cancelled.await("state", "oaiapp_fixture") }
        kotlinx.coroutines.yield()
        withTimeout(3000) { wait.cancelAndJoin() }
        try {
            Socket(
                "127.0.0.1",
                cancelled.redirectUri.toHttpUrl().port
            ).close(); fail("Cancelled listener still bound")
        } catch (_: java.io.IOException) {
        }
    }
}
