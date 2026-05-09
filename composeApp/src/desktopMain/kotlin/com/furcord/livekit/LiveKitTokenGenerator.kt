package com.furcord.livekit

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Date

/**
 * LiveKit Access Token üretir.
 *
 * Token, LiveKit Cloud'un beklediği `video` claim'i içeren HS256 JWT'dir.
 * ~10 kişilik iç kullanım için istemci tarafında üretmek güvenlidir.
 */
object LiveKitTokenGenerator {

    fun generateToken(
        apiKey: String,
        apiSecret: String,
        roomName: String,
        identity: String,
        displayName: String = identity,
        canPublish: Boolean = true,
        canSubscribe: Boolean = true,
        ttlSeconds: Long = 6 * 3600L
    ): String {
        val now = System.currentTimeMillis()
        val key = Keys.hmacShaKeyFor(apiSecret.toByteArray(Charsets.UTF_8))

        return Jwts.builder()
            .issuer(apiKey)
            .subject(identity)
            .issuedAt(Date(now))
            .expiration(Date(now + ttlSeconds * 1000L))
            .claim("name", displayName)
            .claim("video", mapOf(
                "room"           to roomName,
                "roomJoin"       to true,
                "canPublish"     to canPublish,
                "canSubscribe"   to canSubscribe,
                "canPublishData" to true
            ))
            .signWith(key)
            .compact()
    }
}
