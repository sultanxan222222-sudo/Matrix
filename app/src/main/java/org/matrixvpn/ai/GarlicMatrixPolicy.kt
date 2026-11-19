package org.matrixvpn.ai

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private MATRIX policy adapter backed by the original Garlic Qwen3.5 engine.
 *
 * This is an internal network-policy component: it has no chat UI, voice path or
 * external model API. It accepts only the small aggregate observation JSON prepared
 * by the VPN controller. Its output is still untrusted and must be validated by
 * MATRIX before any candidate VPN profile is probed or activated.
 */
class GarlicMatrixPolicy(
    context: Context,
    private val modelDirectory: File
) : Closeable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-garlic-policy").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    @Volatile private var nativeHandle: Long = 0L

    fun initialize(): CompletableFuture<InitializationResult> = submit {
        check(nativeHandle == 0L) { "Garlic MATRIX policy is already initialized." }
        require(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
            "Cannot create private model directory."
        }
        require(modelDirectory.usableSpace > 256L * 1024L * 1024L) {
            "Not enough free storage for the Garlic model pack."
        }

        // The native library loads the model from app-private storage. The Qwen weights
        // and model.idx must first be installed there using a separately verified model pack.
        System.loadLibrary("OpenCL")
        System.loadLibrary("matrix_garlic")
        val handle = nativeCreate(appContext.assets, modelDirectory.absolutePath)
        check(handle != 0L) {
            "Garlic could not initialize. Check that the model pack, model.idx, tokenizer and Vulkan runtime are installed."
        }
        nativeHandle = handle
        InitializationResult(
            ready = true,
            modelDirectory = modelDirectory.absolutePath,
            deviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        )
    }

    /**
     * Evaluates a pre-filtered snapshot. This does not activate or switch a VPN profile.
     * The caller must validate the returned JSON against its configured candidate IDs,
     * confidence policy, profile authentication and traffic protection state.
     */
    fun assess(observationJson: String): CompletableFuture<String> = submit {
        require(observationJson.isNotBlank() && observationJson.length <= 64 * 1024) {
            "Network observation is empty or too large."
        }
        val handle = nativeHandle
        check(handle != 0L) { "Garlic MATRIX policy is not initialized." }
        nativeEvaluate(handle, observationJson)
    }

    private fun <T> submit(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (closed.get()) {
            future.completeExceptionally(IllegalStateException("Garlic MATRIX policy is closed."))
            return future
        }
        try {
            executor.execute {
                if (closed.get()) {
                    future.completeExceptionally(IllegalStateException("Garlic MATRIX policy is closed."))
                } else {
                    try {
                        future.complete(action())
                    } catch (error: Throwable) {
                        future.completeExceptionally(error)
                    }
                }
            }
        } catch (error: RuntimeException) {
            future.completeExceptionally(error)
        }
        return future
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.submit {
                val handle = nativeHandle
                nativeHandle = 0L
                if (handle != 0L) nativeClose(handle)
            }.get(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Process shutdown is already underway; no further model requests are accepted.
        } finally {
            executor.shutdownNow()
        }
    }

    private external fun nativeCreate(assetManager: android.content.res.AssetManager, modelDirectory: String): Long
    private external fun nativeEvaluate(handle: Long, observationJson: String): String
    private external fun nativeClose(handle: Long)

    data class InitializationResult(
        val ready: Boolean,
        val modelDirectory: String,
        val deviceAbi: String
    )
}
