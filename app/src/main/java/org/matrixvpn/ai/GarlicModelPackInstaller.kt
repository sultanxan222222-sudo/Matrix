package org.matrixvpn.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Installs the official Garlic model pack into MATRIX private storage.
 * Only known flat file names are accepted. Files are staged, SHA-256 checked,
 * the PMQ5 index is validated, and only then is the previous model replaced.
 */
class GarlicModelPackInstaller(context: Context) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "matrix-garlic-model-installer").apply { isDaemon = true }
    }

    private val requiredFiles = setOf(
        "model.idx", "tokenizer.bin", "qwen3.5-0.8b-q4i.bin",
        "qwen3.5-0.8b-q4i.json", "QWEN-LICENSE", "MODEL-INFO.txt", "SHA256SUMS.json"
    )

    fun install(uri: Uri): CompletableFuture<InstallResult> {
        val future = CompletableFuture<InstallResult>()
        executor.execute {
            try {
                future.complete(installNow(uri))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }

    private fun installNow(uri: Uri): InstallResult {
        val root = File(app.filesDir, "models")
        require(root.isDirectory || root.mkdirs()) { "Не удалось создать приватную папку моделей." }
        require(StatFs(root.absolutePath).availableBytes >= MIN_FREE_BYTES) {
            "Для установки модели нужно не менее 600 МиБ свободного места."
        }

        val stage = File(root, ".garlic-stage-" + UUID.randomUUID())
        val target = File(root, "garlic")
        val backup = File(root, ".garlic-backup-" + UUID.randomUUID())
        require(stage.mkdir()) { "Не удалось создать временную папку установки." }

        try {
            val (manifest, actual) = extract(uri, stage)
            verifyHashes(manifest, actual)
            validateIndex(File(stage, "model.idx"))

            var oldMoved = false
            if (target.exists()) {
                require(target.renameTo(backup)) { "Не удалось сохранить предыдущую модель." }
                oldMoved = true
            }
            if (!stage.renameTo(target)) {
                if (oldMoved) backup.renameTo(target)
                throw IllegalStateException("Не удалось завершить установку модели.")
            }
            if (oldMoved) backup.deleteRecursively()

            return InstallResult(
                directory = target.absolutePath,
                weightsBytes = File(target, "qwen3.5-0.8b-q4i.bin").length(),
                modelName = "Garlic Qwen3.5-0.8B Q4i",
                maxContext = 2048,
                verifiedFiles = actual.size
            )
        } catch (error: Throwable) {
            stage.deleteRecursively()
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun extract(uri: Uri, stage: File): Pair<Map<String, String>, Map<String, String>> {
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Не удалось открыть ZIP-файл модели.")
        val seen = linkedSetOf<String>()
        val actualHashes = linkedMapOf<String, String>()
        var declaredHashes: Map<String, String>? = null
        var total = 0L

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && name in requiredFiles) {
                    "В архиве обнаружен неизвестный файл: " + name
                }
                require(!name.contains('/') && !name.contains('\\') && name != "." && name != "..") {
                    "В ZIP обнаружен небезопасный путь."
                }
                require(seen.add(name)) { "В архиве повторяется файл: " + name }

                val destination = File(stage, name)
                if (name == "SHA256SUMS.json") {
                    val bytes = readBounded(zip, 64 * 1024)
                    declaredHashes = parseManifest(bytes)
                    FileOutputStream(destination).use { out ->
                        out.write(bytes)
                        out.fd.sync()
                    }
                    total += bytes.size
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var fileSize = 0L
                    FileOutputStream(destination).use { out ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileSize += count
                            total += count
                            require(fileSize <= maxFileBytes(name)) { "Файл " + name + " слишком большой." }
                            require(total <= MAX_TOTAL_BYTES) { "Распакованный архив превышает лимит." }
                            digest.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                        }
                        out.fd.sync()
                    }
                    actualHashes[name] = digest.digest().toHex()
                }
                zip.closeEntry()
            }
        }

        require(seen == requiredFiles) {
            "В архиве отсутствуют обязательные файлы: " + (requiredFiles - seen).joinToString()
        }
        return Pair(declaredHashes ?: throw IllegalArgumentException("Нет SHA256SUMS.json."), actualHashes)
    }

    private fun verifyHashes(expected: Map<String, String>, actual: Map<String, String>) {
        require(expected.keys == requiredFiles - "SHA256SUMS.json") {
            "Манифест контрольных сумм содержит неверный список файлов."
        }
        for ((name, digest) in expected) {
            require(digest.matches(Regex("[0-9a-f]{64}")) && actual[name] == digest) {
                "Контрольная сумма файла " + name + " не совпала."
            }
        }
    }

    private fun validateIndex(file: File) {
        require(file.isFile && file.length() >= 224L) { "model.idx отсутствует или повреждён." }
        val bytes = ByteArray(224)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count <= 0) break
                offset += count
            }
            require(offset == bytes.size) { "Не удалось прочитать заголовок model.idx." }
        }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "PMQ5") {
            "Нужен формат Garlic Qwen3.5 PMQ5."
        }
        fun u32(offset: Int): Long =
            (bytes[offset].toLong() and 255L) or
            ((bytes[offset + 1].toLong() and 255L) shl 8) or
            ((bytes[offset + 2].toLong() and 255L) shl 16) or
            ((bytes[offset + 3].toLong() and 255L) shl 24)

        require(u32(4) in 1..128 && u32(8) in 64..16384) {
            "Размерности модели в model.idx недопустимы."
        }
        require(u32(32) == 2048L) {
            "Контекст модели не совпадает с Android-ядрами MATRIX (2048)."
        }
        val tensorCount = u32(188)
        require(tensorCount in 10..2048 && file.length() == 224L + tensorCount * 36L) {
            "Таблица тензоров model.idx повреждена."
        }
    }

    private fun parseManifest(bytes: ByteArray): Map<String, String> {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            result[name] = json.getJSONObject(name).getString("sha256")
        }
        return result
    }

    private fun readBounded(zip: ZipInputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            require(out.size() + count <= max) { "Манифест контрольных сумм слишком большой." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun maxFileBytes(name: String): Long = when (name) {
        "qwen3.5-0.8b-q4i.bin" -> 700L * 1024 * 1024
        "tokenizer.bin" -> 64L * 1024 * 1024
        "qwen3.5-0.8b-q4i.json" -> 16L * 1024 * 1024
        "model.idx" -> 4L * 1024 * 1024
        else -> 1024L * 1024
    }

    data class InstallResult(
        val directory: String,
        val weightsBytes: Long,
        val modelName: String,
        val maxContext: Int,
        val verifiedFiles: Int
    )

    companion object {
        private const val MIN_FREE_BYTES = 600L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 800L * 1024 * 1024
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 255) }
