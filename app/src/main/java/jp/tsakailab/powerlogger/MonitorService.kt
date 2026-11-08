package jp.tsakailab.powerlogger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.net.Uri

object PowerDispatcher {
    fun observe(ctx: Context, plugged: Boolean, source: String): Boolean {
        val event = PowerStore.get(ctx).observe(ctx, plugged, source)
        if (event != null && event.state == "PENDING") {
            EmailScheduler.schedule(ctx)
            EmailSender.kick(ctx)
        }
        return event != null
    }
}

class PowerBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val plugged = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> true
            Intent.ACTION_POWER_DISCONNECTED -> false
            else -> return
        }
        // A short, local SQLite transaction: no network I/O on the broadcast thread.
        PowerDispatcher.observe(context, plugged, "BROADCAST")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.monitoring(context)) return
        try {
            MonitorService.start(context, "BOOT")
        } catch (_: RuntimeException) {
            // Device background restrictions may block auto-start. Opening the app will retry.
        }
        if (Prefs.emailEnabled(context) && PowerStore.get(context).pendingCount() > 0) {
            EmailScheduler.schedule(context)
        }
    }
}

class MonitorService : Service() {
    companion object {
        const val CHANNEL = "powerlogger_monitor"
        private const val NOTIFICATION_ID = 12001
        fun start(ctx: Context, source: String = "STARTUP") {
            val i = Intent(ctx, MonitorService::class.java).putExtra("source", source)
            ctx.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val receiver = PowerBroadcastReceiver()
    private var registered = false

    private val check = object : Runnable {
        override fun run() {
            if (!Prefs.monitoring(this@MonitorService)) { stopSelf(); return }
            val changed = PowerDispatcher.observe(this@MonitorService, PowerState.isPlugged(this@MonitorService), "POLL")
            if (changed) updateNotification()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Start as a foreground service before performing any prolonged work.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        registered = true
        handler.postDelayed(check, 20_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.monitoring(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra("source") ?: "STARTUP"
        PowerDispatcher.observe(this, PowerState.isPlugged(this), source)
        updateNotification()
        if (Prefs.emailEnabled(this) && PowerStore.get(this).pendingCount() > 0) {
            EmailScheduler.schedule(this)
            EmailSender.kick(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        if (registered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.channel_description) })
    }

    private fun notification(): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = getString(if (PowerState.isPlugged(this))
            R.string.notification_connected else R.string.notification_disconnected)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
}
