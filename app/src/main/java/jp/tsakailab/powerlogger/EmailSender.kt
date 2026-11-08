package jp.tsakailab.powerlogger

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object EmailConfig {
    fun urlValid(value: String): Boolean = try {
        val url = URL(value.trim())
        url.protocol == "https" && url.host == "script.google.com" &&
            Regex("^/macros/s/[^/]+/exec$").matches(url.path) && url.query == null
    } catch (_: Exception) { false }

    fun ready(ctx: Context): Boolean = Prefs.recipient(ctx).contains('@') &&
        urlValid(Prefs.endpoint(ctx)) && Prefs.secret(ctx).length >= 40
}

object EmailScheduler {
    private const val JOB_ID = 24711
    fun schedule(ctx: Context) {
        if (!Prefs.emailEnabled(ctx)) return
        val builder = JobInfo.Builder(JOB_ID, ComponentName(ctx, SendJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        scheduler.schedule(builder.build())
    }
    fun cancel(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

object EmailSender {
    private val worker = Executors.newSingleThreadExecutor()
    private val sendLock = Any()

    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute { drain(app) }
    }

    /** true means pending work remains and should be retried. */
    fun drain(ctx: Context): Boolean = synchronized(sendLock) {
        if (!Prefs.emailEnabled(ctx)) return@synchronized false
        val store = PowerStore.get(ctx)
        if (!EmailConfig.ready(ctx)) {
            // Invalid settings must not silently discard the queue.
            return@synchronized store.pendingCount() > 0
        }
        repeat(5) {
            val batch = store.pending(15)
            if (batch.isEmpty()) return@synchronized false
            for (event in batch) {
                if (!Prefs.emailEnabled(ctx)) return@synchronized false
                try {
                    transmit(Prefs.endpoint(ctx), Prefs.secret(ctx), event)
                    store.markSent(event.id)
                } catch (ex: Exception) {
                    store.markFailed(event.id, ex.message ?: ex.javaClass.simpleName)
                    return@synchronized true
                }
            }
        }
        store.pendingCount() > 0
    }

    private fun hmacHex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }

    private fun transmit(endpoint: String, secret: String, event: PowerEvent) {
        if (!EmailConfig.urlValid(endpoint)) throw IllegalArgumentException("送信先URLが不正です")
        val duration = event.durationMs?.toString() ?: ""
        val canonical = "1|${event.id}|${event.kind}|${event.millis}|${event.email}|${event.source}|$duration"
        val json = JSONObject().apply {
            put("version", 1)
            put("id", event.id)
            put("type", event.kind)
            put("timestamp", event.millis)
            put("recipient", event.email)
            put("source", event.source)
            if (event.durationMs != null) put("durationMs", event.durationMs)
            put("signature", hmacHex(secret, canonical))
        }
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        var responseCode: Int
        var body: String
        var location: String?
        try {
            conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            responseCode = conn.responseCode
            location = conn.getHeaderField("Location")
            body = if (responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally { conn.disconnect() }

        // Apps Script ContentService serves results via a one-time HTTPS redirect.
        if (responseCode in listOf(301, 302, 303, 307, 308)) {
            val next = URL(URL(endpoint), location ?: throw IllegalStateException("通知先の応答先がありません"))
            if (next.protocol != "https" || next.host != "script.googleusercontent.com") {
                throw IllegalStateException("予期しないリダイレクト先: ${next.host}")
            }
            val redirect = (next.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
            }
            try {
                responseCode = redirect.responseCode
                body = if (responseCode in 200..299) redirect.inputStream.bufferedReader().use { it.readText() }
                    else redirect.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally { redirect.disconnect() }
        }
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: ${body.take(120)}")
        val result = try { JSONObject(body) } catch (_: Exception) {
            throw IllegalStateException("JSON以外の応答: ${body.take(120)}")
        }
        if (!result.optBoolean("ok", false)) {
            throw IllegalStateException("サーバーで送信失敗: ${result.optString("error", "不明")}")
        }
    }
}

class SendJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        Thread({
            val needsRetry = try { EmailSender.drain(ctx) } catch (_: Exception) { true }
            jobFinished(params, needsRetry)
        }, "PowerLoggerEmailJob").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
