package org.matrixvpn.ai

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional diagnostic for the Android OpenCL-on-Vulkan runtime.
 *
 * This class checks the clvk device path and a small SPIR-V kernel. It is not the
 * MATRIX AI readiness check: GarlicMatrixPolicy loads the bundled Qwen3.5 weights,
 * initializes the Garlic engine and performs a real model token-pass before it reports
 * the policy engine as ready.
 */
class GarlicAndroidBackend : Closeable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-garlic-gpu-probe").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    fun probe(context: Context): CompletableFuture<ProbeResult> {
        val future = CompletableFuture<ProbeResult>()
        if (closed.get()) {
            future.completeExceptionally(IllegalStateException("Garlic Android backend is closed."))
            return future
        }
        try {
            executor.execute {
                if (closed.get()) {
                    future.completeExceptionally(IllegalStateException("Garlic Android backend is closed."))
                    return@execute
                }
                try {
                    // libOpenCL is built from clvk and packaged per ABI by the native build workflow.
                    System.loadLibrary("OpenCL")
                    System.loadLibrary("matrix_garlic")
                    val raw = nativeProbe(context.assets)
                    val json = JSONObject(raw)
                    future.complete(
                        ProbeResult(
                            ready = json.optBoolean("ready", false),
                            passed = json.optString("probe") == "passed",
                            garlicKernelPassed = json.optBoolean("garlic_kernel_passed", false),
                            platform = json.optString("platform").takeIf { it.isNotBlank() },
                            device = json.optString("device").takeIf { it.isNotBlank() },
                            deviceVersion = json.optString("device_version").takeIf { it.isNotBlank() },
                            computeUnits = json.optInt("compute_units").takeIf { it > 0 },
                            maxWorkGroup = json.optLong("max_work_group").takeIf { it > 0L },
                            globalMemoryBytes = json.optLong("global_memory_bytes").takeIf { it > 0L },
                            message = json.optString("message").ifBlank {
                                if (json.optBoolean("ready")) "Vulkan/OpenCL GPU smoke-test прошёл."
                                else "GPU backend пока не готов."
                            },
                            rawJson = raw
                        )
                    )
                } catch (error: Throwable) {
                    future.complete(
                        ProbeResult(
                            ready = false,
                            passed = false,
                            message = error.message ?: error.javaClass.simpleName,
                            rawJson = null
                        )
                    )
                }
            }
        } catch (error: RuntimeException) {
            future.completeExceptionally(error)
        }
        return future
    }

    private external fun nativeProbe(assetManager: AssetManager): String

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }

    data class ProbeResult(
        val ready: Boolean,
        val passed: Boolean,
        val garlicKernelPassed: Boolean = false,
        val platform: String? = null,
        val device: String? = null,
        val deviceVersion: String? = null,
        val computeUnits: Int? = null,
        val maxWorkGroup: Long? = null,
        val globalMemoryBytes: Long? = null,
        val message: String,
        val rawJson: String?
    )
}
