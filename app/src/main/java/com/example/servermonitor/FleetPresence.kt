package com.example.servermonitor

import android.content.Context

/**
 * Device-local last-successful-contact ledger for fleet visibility.
 * It stores timestamps only; server credentials and telemetry remain in their
 * existing secure/API stores.
 */
object FleetPresenceStore {
    private const val PREFS = "fleet_presence"
    private const val STALE_AFTER_MS = 5 * 60 * 1000L

    fun key(server: Server): String =
        (server.url.trimEnd('/') + "|" + server.name.trim()).hashCode().toString()

    fun recordSuccess(context: Context, server: Server, timestamp: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key(server), timestamp).apply()
    }

    fun lastSeen(context: Context, server: Server): Long? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(server), 0L)
        return value.takeIf { it > 0L }
    }

    fun isStale(timestamp: Long?, now: Long = System.currentTimeMillis()): Boolean =
        timestamp != null && now - timestamp >= STALE_AFTER_MS

    fun formatAge(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - timestamp).coerceAtLeast(0L) / 60_000L)
        return when {
            minutes < 1 -> "Last seen just now"
            minutes < 60 -> "Last seen ${minutes}m ago"
            minutes < 24 * 60 -> "Last seen ${minutes / 60}h ago"
            else -> "Last seen ${minutes / (24 * 60)}d ago"
        }
    }
}
