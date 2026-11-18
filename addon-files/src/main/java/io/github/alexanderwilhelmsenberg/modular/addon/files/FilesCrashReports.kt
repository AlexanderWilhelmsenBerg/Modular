package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.util.UUID

/** Last-resort capture uses its own file, never controller/event-log locks, then delegates to Android. */
internal object FilesCrashReports {
    @Volatile var operation: String? = null
    private var reserve: ByteArray? = null
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "last-java-crash.json"))

    fun install(context: Context) {
        reserve = ByteArray(256 * 1024)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            reserve = null
            try { capture(context, thread, failure) } catch (_: Throwable) { }
            finally {
                if (previous != null) previous.uncaughtException(thread, failure)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
    }

    internal fun capture(context: Context, thread: Thread, failure: Throwable) {
        val text = StringBuilder()
        var truncated = false
        val bounded = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                val count = minOf(length, (24_000 - text.length).coerceAtLeast(0))
                if (count < length) truncated = true
                if (count > 0) text.append(chars, offset, count)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        failure.printStackTrace(PrintWriter(bounded))
        val runtime = Runtime.getRuntime()
        var root = failure
        repeat(20) { root.cause?.takeIf { it !== root }?.let { root = it } }
        val json = JSONObject().put("id", UUID.randomUUID().toString()).put("at", System.currentTimeMillis())
            .put("version", BuildConfig.VERSION_NAME).put("pid", Process.myPid())
            .put("process", Application.getProcessName()).put("thread", thread.name)
            .put("exception", failure.javaClass.name).put("message", failure.message?.take(1000))
            .put("stack", text.toString()).put("stackTruncated", truncated)
            .put("rootException", root.javaClass.name).put("rootMessage", root.message?.take(1000))
            .put("rootFrames", org.json.JSONArray(root.stackTrace.take(12).map { it.toString() }))
            .put("heapUsed", runtime.totalMemory() - runtime.freeMemory())
            .put("heapLimit", runtime.maxMemory()).put("operation", operation?.let { runCatching { JSONObject(it) }.getOrNull() })
        val storage = file(context)
        val stream = storage.startWrite()
        try { stream.write(json.toString().toByteArray()); storage.finishWrite(stream) }
        catch (error: Throwable) { storage.failWrite(stream); throw error }
    }

    fun read(context: Context): JSONObject? = runCatching { JSONObject(String(file(context).readFully())) }.getOrNull()
    fun clear(context: Context) { file(context).delete() }
}
