package org.matrixvpn.ai

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject
import org.matrixvpn.profile.SavedVpnProfile
import org.matrixvpn.profile.SecureProfileVault
import org.matrixvpn.recovery.NodeHealthStore

/**
 * Host-owned bridge between the Garlic model and MATRIX's saved VPN profile pool.
 * Garlic can rank only profiles supplied by the host; it never receives configuration
 * text, credentials, private keys, raw endpoints or arbitrary network tools.
 */
class GarlicProfileSelector(context: Context) : Closeable {
    private val app = context.applicationContext
    private val latch = AutopilotSafetyLatch()
    private val preferences = app.getSharedPreferences("matrix_garlic_controller", Context.MODE_PRIVATE)

    @Volatile private var policy: GarlicMatrixPolicy? = null
    @Volatile var isReady: Boolean = false
        private set

    fun restoreAutopilot(): Long {
        val enabled = preferences.getBoolean("autopilot_enabled", false)
        val stopped = preferences.getBoolean("emergency_stop", false)
        return if (enabled && !stopped) latch.enable() else 0L
    }

    fun beginAutopilot(): Long {
        preferences.edit()
            .putBoolean("autopilot_enabled", true)
            .putBoolean("emergency_stop", false)
            .apply()
        return latch.enable()
    }

    fun isActive(token: Long): Boolean = token != 0L && latch.isActive(token)

    fun emergencyStop(): Long {
        preferences.edit()
            .putBoolean("autopilot_enabled", false)
            .putBoolean("emergency_stop", true)
            .apply()
        return latch.emergencyStop()
    }

    fun initialize(modelDirectory: File): CompletableFuture<GarlicMatrixPolicy.InitializationResult> {
        val next = GarlicMatrixPolicy(app, modelDirectory)
        val previous = policy
        policy = next
        isReady = false
        previous?.close()
        return next.initialize().thenApply { result ->
            if (policy === next) isReady = result.ready
            result
        }
    }

    fun recommend(
        suppliedProfiles: List<SavedVpnProfile>,
        tunnelActive: Boolean,
        currentProfileId: String?,
        excludedProfileIds: Set<String>,
        token: Long
    ): CompletableFuture<SavedVpnProfile?> {
        val currentPolicy = policy
        if (!isReady || currentPolicy == null || !isActive(token)) {
            return CompletableFuture.completedFuture(null)
        }

        val candidates = suppliedProfiles
            .filter { it.id !in excludedProfileIds && it.id != currentProfileId }
            .distinctBy { it.id }
            .take(12)
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(null)

        val allowedIds = candidates.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val descriptors = JSONArray()
        val health = JSONArray()

        for (profile in candidates) {
            val sample = NodeHealthStore.snapshot(app, profile.id)
            val total = sample.sampleCount
            val failurePercent = if (total > 0) sample.failures * 100 / total else -1
            val ageSeconds = if (sample.lastTestAt > 0L) {
                (now - sample.lastTestAt).coerceAtLeast(0L) / 1000L
            } else {
                -1L
            }

            descriptors.put(
                JSONObject()
                    .put("profile_id", profile.id)
                    .put("provider", profile.provider.take(40))
                    .put("format", profile.format.take(24))
                    .put("health_score", NodeHealthStore.score(app, profile.id))
                    .put("sample_count", total)
            )
            health.put(
                JSONObject()
                    .put("profile_id", profile.id)
                    .put("success_count", sample.successes)
                    .put("failure_count", sample.failures)
                    .put("probe_failure_percent", failurePercent)
                    .put("https_average_ms", sample.averageLatencyMs)
                    .put("sample_count", total)
                    .put("age_seconds", ageSeconds)
            )
        }

        val observation = JSONObject()
            .put("policy_scope", if (tunnelActive) "vpn_failover" else "vpn_candidate_selection")
            .put("automatic_connection_authorized", true)
            .put(
                "selection_objective",
                "Choose only among MATRIX-authorized saved profiles. Prefer recent successful HTTPS checks, fewer failures and lower measured average HTTPS duration. Never infer unmeasured performance."
            )
            .put("tunnel_active", tunnelActive)
            .put("current_profile_id", currentProfileId ?: JSONObject.NULL)
            .put(
                "connectivity_known",
                currentProfileId?.let { NodeHealthStore.snapshot(app, it).sampleCount > 0 } ?: false
            )
            .put("candidate_count", candidates.size)
            .put("candidate_profile_ids", JSONArray().apply { candidates.forEach { put(it.id) } })
            .put("candidate_descriptors", descriptors)
            .put("candidate_health", health)
            .put("failed_profile_ids", JSONArray().apply { excludedProfileIds.forEach { put(it) } })
            .toString()

        return currentPolicy.assess(observation).thenApply { raw ->
            if (!isActive(token)) return@thenApply null
            try {
                val advice = GarlicPolicyOutputGate.validateForTunnelState(
                    raw = raw,
                    allowedCandidateIds = allowedIds,
                    tunnelActive = tunnelActive,
                    currentProfileId = currentProfileId
                )
                require(advice.confidence >= 0.60) {
                    "Garlic confidence is below the MATRIX connection threshold."
                }
                val expectedAction = if (tunnelActive) {
                    GarlicPolicyOutputGate.Action.PROPOSE_FAILOVER
                } else {
                    GarlicPolicyOutputGate.Action.CONNECT_PROFILE
                }
                require(advice.action == expectedAction) {
                    "Garlic returned an action that does not match the tunnel state."
                }
                candidates.firstOrNull { it.id == advice.profileId }
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun close() {
        emergencyStop()
        val old = policy
        policy = null
        isReady = false
        old?.close()
    }
}
