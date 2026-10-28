package com.kkgosu.reasoned.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal fun randomOAuthValue(): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal fun oauthChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )

/** Nimbus performs signature/JWK parsing; only RS256 from the pinned issuer's JWKS is accepted. */
internal fun verifyChatGptIdentity(
    token: String,
    jwks: String,
    client: String,
    nonce: String?,
    accessToken: String,
    now: Long = System.currentTimeMillis()
): Pair<String, String> {
    try {
        if (token.length > 32_768) throw IllegalArgumentException()
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.RS256 || !jwt.header.criticalParams.isNullOrEmpty()) throw IllegalArgumentException()
        val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey
            ?: throw IllegalArgumentException()
        if (key.isPrivate || (key.keyUse != null && key.keyUse.identifier() != "sig") || (key.algorithm != null && key.algorithm != JWSAlgorithm.RS256)) throw IllegalArgumentException()
        if (!jwt.verify(RSASSAVerifier(key))) throw IllegalArgumentException()
        val claims = jwt.jwtClaimsSet
        if (claims.issuer != ChatGptAuth.AUTH || client !in claims.audience || claims.subject.isNullOrBlank() || claims.subject.length > 256 ||
            claims.expirationTime == null || claims.expirationTime.time <= now - 5000 || claims.issueTime == null || claims.issueTime.time > now + 5000 ||
            (claims.notBeforeTime?.time
                ?: 0) > now + 5000 || (claims.audience.size > 1 && claims.getStringClaim("azp") != client) ||
            (claims.getStringClaim("azp") != null && claims.getStringClaim("azp") != client) || (nonce != null && claims.getStringClaim(
                "nonce"
            ) != nonce)
        ) throw IllegalArgumentException()
        claims.getStringClaim("at_hash")?.let { hash ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(accessToken.toByteArray(Charsets.US_ASCII))
            if (hash != Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.copyOfRange(0, digest.size / 2))
            ) throw IllegalArgumentException()
        }
        return claims.subject to (claims.getStringClaim("email") ?: "ChatGPT").take(254)
    } catch (_: Exception) {
        throw ProviderFailure("chatgpt_identity_invalid")
    }
}
