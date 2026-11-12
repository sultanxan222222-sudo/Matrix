package org.matrixvpn.recovery

import android.content.Context
import org.json.JSONObject

data class NodeHealthSnapshot(
    val successes: Int,
    val failures: Int,
    val averageLatencyMs: Long,
    val lastSuccessAt: Long,
    val lastTestAt: Long
) {
    val sampleCount: Int get() = successes + failures
}

/** Evidence-based ranking from HTTPS reachability and response time, not peer-handshake proof. */
object NodeHealthScorer {
    fun score(successes: Int, failures: Int, latencyMs: Long, lastSuccessAgeMs: Long): Int {
        val total = successes.coerceAtLeast(0) + failures.coerceAtLeast(0)
        if (total == 0) return 45
        val reliability = (successes.coerceAtLeast(0).toDouble() / total.toDouble()) * 65.0
        val latency = when {
            latencyMs <= 0L -> 8.0
            latencyMs <= 300L -> 20.0
            latencyMs <= 700L -> 17.0
            latencyMs <= 1_500L -> 13.0
            latencyMs <= 3_000L -> 8.0
            else -> 3.0
        }
        val freshness = when {
            lastSuccessAgeMs < 60L * 60L * 1000L -> 15.0
            lastSuccessAgeMs < 24L * 60L * 60L * 1000L -> 12.0
            lastSuccessAgeMs < 7L * 24L * 60L * 60L * 1000L -> 7.0
            else -> 0.0
        }
        return (reliability + latency + freshness).toInt().coerceIn(0, 100)
    }
}

object NodeHealthStore {
    private const val PREFS = "matrix_node_health"

    fun snapshot(context: Context, profileId: String): NodeHealthSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val item = try { JSONObject(prefs.getString(profileId, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return NodeHealthSnapshot(
            successes = item.optInt("successes", 0),
            failures = item.optInt("failures", 0),
            averageLatencyMs = item.optLong("averageLatencyMs", 0L),
            lastSuccessAt = item.optLong("lastSuccessAt", 0L),
            lastTestAt = item.optLong("lastTestAt", 0L)
        )
    }

    fun score(context: Context, profileId: String): Int {
        val health = snapshot(context, profileId)
        val age = if (health.lastSuccessAt == 0L) Long.MAX_VALUE else
            (System.currentTimeMillis() - health.lastSuccessAt).coerceAtLeast(0L)
        return NodeHealthScorer.score(health.successes, health.failures, health.averageLatencyMs, age)
    }

    fun record(context: Context, profileId: String, reachable: Boolean, latencyMs: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = snapshot(context, profileId)
        val now = System.currentTimeMillis()
        val nextLatency = if (reachable) {
            if (current.averageLatencyMs <= 0L) latencyMs.coerceAtLeast(1L)
            else ((current.averageLatencyMs * 3L) + latencyMs.coerceAtLeast(1L)) / 4L
        } else current.averageLatencyMs
        val next = JSONObject()
            .put("successes", current.successes + if (reachable) 1 else 0)
            .put("failures", current.failures + if (reachable) 0 else 1)
            .put("averageLatencyMs", nextLatency)
            .put("lastSuccessAt", if (reachable) now else current.lastSuccessAt)
            .put("lastTestAt", now)
        prefs.edit().putString(profileId, next.toString()).apply()
    }

    fun describe(context: Context, profileId: String): String {
        val health = snapshot(context, profileId)
        if (health.sampleCount == 0) return "ещё не проверен"
        val percent = (health.successes * 100) / health.sampleCount
        val latency = if (health.averageLatencyMs > 0L) "${health.averageLatencyMs} мс" else "нет замера"
        return "успех ${percent}% • ${latency} • тестов ${health.sampleCount}"
    }
}
