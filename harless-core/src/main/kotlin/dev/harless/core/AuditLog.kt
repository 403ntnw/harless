/*
 * Copyright (C) 2026 Enrico-Antonio Busuioc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.harless.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

/**
 * One tamper-evident audit entry. [prevHash] chains each entry to the one
 * before it, so a single altered record breaks verification for everything
 * after it. Auditability is a release condition here, not a debugging aid:
 * every consequential action answers "who did what, when, and what came back".
 */
@Serializable
data class AuditEntry(
    val seq: Long,
    val timestamp: String,
    val actor: String,
    val action: String,
    val detail: String,
    val prevHash: String,
    val hash: String,
)

/**
 * Append-only, hash-chained audit log.
 *
 * Deliberately minimal: an in-memory chain with JSONL export. Persistence is
 * the embedder's decision; the chain semantics are the point. [verify] walks
 * the chain and reports the first broken link, which is the property a
 * reviewer actually needs: not "we log", but "you can prove nobody edited it".
 */
class AuditLog(private val clock: () -> Instant = Instant::now) {

    private val entries = ArrayList<AuditEntry>()
    private val json = Json { prettyPrint = false }

    val size: Int get() = entries.size

    @Synchronized
    fun append(actor: String, action: String, detail: String): AuditEntry {
        val prev = entries.lastOrNull()?.hash ?: GENESIS
        val seq = entries.size.toLong()
        val timestamp = clock().toString()
        val hash = sha256("$seq|$timestamp|$actor|$action|$detail|$prev")
        val entry = AuditEntry(seq, timestamp, actor, action, detail, prev, hash)
        entries.add(entry)
        return entry
    }

    @Synchronized
    fun snapshot(): List<AuditEntry> = entries.toList()

    /** Returns the sequence number of the first tampered entry, or null when the chain holds. */
    fun verify(chain: List<AuditEntry> = snapshot()): Long? {
        var prev = GENESIS
        for (entry in chain) {
            val expected = sha256("${entry.seq}|${entry.timestamp}|${entry.actor}|${entry.action}|${entry.detail}|$prev")
            if (expected != entry.hash || entry.prevHash != prev) return entry.seq
            prev = entry.hash
        }
        return null
    }

    fun toJsonl(): String = snapshot().joinToString("\n") { json.encodeToString(it) }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val GENESIS = "genesis"
    }
}
