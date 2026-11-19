package org.matrixvpn.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private, on-device MATRIX decision engine.
 *
 * This class does not display a chat, make network requests, or control the VPN directly.
 * The model can only recommend a bounded action. The VPN controller must independently
 * validate every recommendation before probing or switching a configured profile.
 *
 * The initial Android backend is CPU-only: MATRIX's target Realme Note 50 uses a Mali GPU,
 * and the available Qwen3.5 LiteRT-LM GPU builds do not yet have verified Mali-device support.
 */
class MatrixAiRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val owner: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-ai-runtime").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    // These native resources are accessed only on [owner].
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Loads a previously downloaded .litertlm model from app-private storage.
     * Model downloading/updates are deliberately handled by a separate model-store component.
     */
    fun initialize(modelFile: File): CompletableFuture<Unit> = onOwner {
        check(engine == null && conversation == null) { "MATRIX AI is already initialized." }
        require(modelFile.isFile && modelFile.canRead() && modelFile.length() > 0L) {
            "MATRIX AI model file is missing or unreadable."
        }

        val cacheDirectory = File(appContext.cacheDir, "matrix-ai").apply {
            check(isDirectory || mkdirs()) { "Cannot create MATRIX AI cache directory." }
        }
        val createdEngine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(threadCount = 2),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirectory.absolutePath
            )
        )

        try {
            // Engine loading is blocking, so it is performed on the dedicated background thread.
            createdEngine.initialize()
            val createdConversation = createdEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    automaticToolCalling = false
                )
            )
            engine = createdEngine
            conversation = createdConversation
        } catch (error: Throwable) {
            runCatching { createdEngine.close() }
            throw error
        }
    }

    /**
     * Asks the local model for a constrained recommendation.
     * This method passes only aggregate metrics and opaque profile IDs, never VPN keys,
     * raw configuration text, endpoint addresses, DNS contents, or user browsing data.
     */
    fun assess(snapshot: MatrixNetworkSnapshot): CompletableFuture<MatrixAiDecision?> = onOwner {
        val activeConversation = checkNotNull(conversation) {
            "MATRIX AI has not been initialized."
        }

        val allowedCandidates = snapshot.candidateProfileIds
            .asSequence()
            .filter(::isSafeProfileId)
            .filter { it != snapshot.currentProfileId }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()

        val prompt = buildObservation(snapshot, allowedCandidates)
        val answer = activeConversation.sendMessage(
            prompt,
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false)
        ).toString()

        MatrixAiDecision.parse(answer, allowedCandidates)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Preserve the single-owner rule and release Conversation before Engine.
            owner.submit {
                runCatching { conversation?.close() }
                conversation = null
                runCatching { engine?.close() }
                engine = null
            }.get(20, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // The runtime is being shut down; no further model calls are accepted.
        } finally {
            owner.shutdownNow()
        }
    }

    private fun <T> onOwner(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (closed.get()) {
            result.completeExceptionally(IllegalStateException("MATRIX AI runtime is closed."))
            return result
        }
        try {
            owner.execute {
                if (closed.get()) {
                    result.completeExceptionally(IllegalStateException("MATRIX AI runtime is closed."))
                } else {
                    try {
                        result.complete(action())
                    } catch (error: Throwable) {
                        result.completeExceptionally(error)
                    }
                }
            }
        } catch (error: RuntimeException) {
            result.completeExceptionally(error)
        }
        return result
    }

    private fun buildObservation(
        snapshot: MatrixNetworkSnapshot,
        allowedCandidates: List<String>
    ): String {
        val data = JSONObject()
            .put("tunnel_connected", snapshot.tunnelConnected)
            .put("validated_internet", snapshot.validatedInternet)
            .put("current_profile_id", snapshot.currentProfileId ?: JSONObject.NULL)
            .put("candidate_profile_ids", JSONArray(allowedCandidates))
            .put("sample_count", snapshot.sampleCount.coerceIn(0, 10_000))
            .put("recent_disconnects", snapshot.recentDisconnects.coerceIn(0, 100))
            .put("metrics_age_seconds", snapshot.metricsAgeSeconds.coerceIn(0, 86_400))

        data.put("rtt_ms", snapshot.rttMs?.takeIf { it in 0..120_000 } ?: JSONObject.NULL)
        data.put("jitter_ms", snapshot.jitterMs?.takeIf { it in 0..120_000 } ?: JSONObject.NULL)
        data.put(
            "packet_loss_percent",
            snapshot.packetLossPercent?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: JSONObject.NULL
        )
        data.put(
            "downlink_mbps",
            snapshot.downlinkMbps?.takeIf { it.isFinite() && it in 0.0..100_000.0 } ?: JSONObject.NULL
        )
        data.put(
            "uplink_mbps",
            snapshot.uplinkMbps?.takeIf { it.isFinite() && it in 0.0..100_000.0 } ?: JSONObject.NULL
        )
        return data.toString()
    }

    private fun isSafeProfileId(value: String): Boolean =
        value.length in 1..48 && value.all { it.isLetterOrDigit() || it in "._:-" }

    companion object {
        private const val MAX_CONTEXT_TOKENS = 2_048
        private const val MAX_OUTPUT_TOKENS = 72
        private const val MAX_CANDIDATES = 8

        private const val SYSTEM_INSTRUCTION = """
            You are MATRIX VPN's private network-policy module, not a conversational assistant.
            You receive aggregate connection metrics and opaque IDs for already configured VPN profiles.
            Return exactly one JSON object and no Markdown:
            {"action":"KEEP_CURRENT|PROBE_PROFILE|PROPOSE_FAILOVER","profile_id":null,"confidence":0.0,"reason_code":"CODE"}
            Use only the listed candidate profile IDs. Never invent an ID.
            KEEP_CURRENT must use profile_id null.
            PROBE_PROFILE and PROPOSE_FAILOVER must use one listed candidate profile ID.
            If evidence is insufficient, choose KEEP_CURRENT.
            You do not execute actions, access the network, change VPN settings, manage keys,
            alter DNS, disable a kill switch, or bypass any security control.
            The host application validates every recommendation and performs any permitted action.
            Keep the output short and valid JSON.
        """
    }
}

/** Aggregate, non-secret telemetry supplied to MATRIX AI. */
data class MatrixNetworkSnapshot(
    val tunnelConnected: Boolean,
    val validatedInternet: Boolean,
    val rttMs: Int? = null,
    val jitterMs: Int? = null,
    val packetLossPercent: Double? = null,
    val downlinkMbps: Double? = null,
    val uplinkMbps: Double? = null,
    val currentProfileId: String? = null,
    val candidateProfileIds: List<String> = emptyList(),
    val sampleCount: Int = 0,
    val recentDisconnects: Int = 0,
    val metricsAgeSeconds: Int = 0
)

/** A recommendation only; it is not permission to change the active tunnel. */
data class MatrixAiDecision(
    val action: Action,
    val profileId: String?,
    val confidence: Double,
    val reasonCode: String
) {
    enum class Action {
        KEEP_CURRENT,
        PROBE_PROFILE,
        PROPOSE_FAILOVER
    }

    companion object {
        private val jsonObjectPattern = Regex("\\{[\\s\\S]*\\}")
        private val reasonPattern = Regex("[A-Z0-9_]{1,32}")

        fun parse(raw: String, allowedCandidateIds: List<String>): MatrixAiDecision? {
            val jsonText = jsonObjectPattern.find(raw)?.value ?: return null
            val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
            val action = runCatching {
                Action.valueOf(json.optString("action").uppercase(Locale.US))
            }.getOrNull() ?: return null

            val requestedId = json.optString("profile_id").takeUnless {
                it.isBlank() || it.equals("null", ignoreCase = true)
            }
            val profileId = when (action) {
                Action.KEEP_CURRENT -> null
                Action.PROBE_PROFILE, Action.PROPOSE_FAILOVER -> {
                    requestedId?.takeIf { it in allowedCandidateIds } ?: return null
                }
            }

            val rawConfidence = json.optDouble("confidence", 0.0)
            val confidence = if (rawConfidence.isFinite()) rawConfidence.coerceIn(0.0, 1.0) else 0.0
            val reason = json.optString("reason_code", "UNKNOWN")
                .takeIf { reasonPattern.matches(it) } ?: "UNKNOWN"

            return MatrixAiDecision(action, profileId, confidence, reason)
        }
    }
}
