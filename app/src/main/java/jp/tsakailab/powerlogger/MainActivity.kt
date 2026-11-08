package jp.tsakailab.powerlogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bilingual UI: default resources are English, values-ja provides Japanese. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusLabel: TextView
    private lateinit var summaryLabel: TextView
    private lateinit var historyLabel: TextView
    private lateinit var sendStatusLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var emailSwitch: Switch
    private lateinit var emailInput: EditText
    private lateinit var endpointInput: EditText
    private lateinit var keyInput: EditText
    private var suppressSwitch = false

    private val update = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        }
        if (Prefs.monitoring(this)) {
            try { MonitorService.start(this) } catch (_: RuntimeException) {
                // Some OEM firmware blocks background service start; show the stored state.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(update)
        handler.post(update)
    }

    override fun onPause() {
        handler.removeCallbacks(update)
        super.onPause()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(text: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(31, 41, 55))
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun section(title: String) = label(title, 19f, true).apply {
        setPadding(0, dp(18), 0, dp(5))
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(10), dp(13), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.rgb(240, 246, 252))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun input(hintText: String, value: String, secret: Boolean = false, email: Boolean = false): EditText =
        EditText(this).apply {
            setText(value)
            hint = hintText
            textSize = 14f
            setSingleLine(true)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun addButton(line: LinearLayout, title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        params.marginEnd = dp(6)
        line.addView(this, params)
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        scroll.addView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        root.addView(label(getString(R.string.app_name), 27f, true))
        root.addView(label(getString(R.string.description), 12f))
        val stateBox = box()
        statusLabel = label(getString(R.string.status_loading), 19f, true)
        summaryLabel = label("", 14f)
        stateBox.addView(statusLabel)
        stateBox.addView(summaryLabel)
        root.addView(stateBox)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = addButton(controls, getString(R.string.start)) { startMonitoring() }
        stopButton = addButton(controls, getString(R.string.stop)) { stopMonitoring() }
        root.addView(controls)
        root.addView(Button(this).apply {
            text = getString(R.string.refresh)
            setOnClickListener { updateUi() }
        })

        root.addView(section(getString(R.string.history_heading)))
        historyLabel = label("", 14f)
        root.addView(historyLabel)
        root.addView(Button(this).apply {
            text = getString(R.string.csv_export)
            setOnClickListener { exportCsv() }
        })

        root.addView(section(getString(R.string.email_heading)))
        root.addView(label(getString(R.string.email_explanation), 13f))
        emailSwitch = Switch(this).apply { text = getString(R.string.email_toggle) }
        root.addView(emailSwitch)
        root.addView(label(getString(R.string.email_recipient), 14f))
        emailInput = input(getString(R.string.email_hint), Prefs.recipient(this), email = true)
        root.addView(emailInput)
        root.addView(label(getString(R.string.email_endpoint), 14f))
        endpointInput = input(getString(R.string.email_endpoint_hint), Prefs.endpoint(this))
        root.addView(endpointInput)
        root.addView(label(getString(R.string.email_secret), 14f))
        keyInput = input(getString(R.string.email_secret_hint), Prefs.secret(this), secret = true)
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = getString(R.string.email_save)
            setOnClickListener { saveConfig(showMessage = true) }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.email_test)
            setOnClickListener { sendTest() }
        })
        sendStatusLabel = label("", 13f)
        root.addView(sendStatusLabel)

        root.addView(section(getString(R.string.device_heading)))
        root.addView(label(getString(R.string.device_instructions), 13f))
        root.addView(Button(this).apply {
            text = getString(R.string.device_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        })
        if (Build.VERSION.SDK_INT >= 33) {
            root.addView(Button(this).apply {
                text = getString(R.string.app_language_settings)
                setOnClickListener {
                    // Android 13+ supports per-app language settings with manifest localeConfig.
                    try {
                        startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                            Uri.parse("package:$packageName")))
                    } catch (_: RuntimeException) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")))
                    }
                }
            })
        }
        root.addView(label(getString(R.string.device_warning), 12f))

        suppressSwitch = true
        emailSwitch.isChecked = Prefs.emailEnabled(this)
        suppressSwitch = false
        emailSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (!saveConfig(showMessage = false) || !EmailConfig.ready(this)) {
                    message(getString(R.string.notice_missing_configuration))
                    suppressSwitch = true
                    emailSwitch.isChecked = false
                    suppressSwitch = false
                    return@setOnCheckedChangeListener
                }
                Prefs.setEmailEnabled(this, true)
                message(getString(R.string.notice_enabled))
            } else {
                Prefs.setEmailEnabled(this, false)
                PowerStore.get(this).cancelPending()
                EmailScheduler.cancel(this)
                message(getString(R.string.notice_disabled))
            }
            updateUi()
        }
        updateUi()
    }

    private fun saveConfig(showMessage: Boolean): Boolean {
        val email = emailInput.text.toString().trim().lowercase(Locale.ROOT)
        val url = endpointInput.text.toString().trim()
        val key = keyInput.text.toString().trim()
        val validEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
        val valid = validEmail && EmailConfig.urlValid(url) && key.length >= 40
        if (Prefs.emailEnabled(this) && !valid) {
            message(getString(R.string.notice_required))
            return false
        }
        Prefs.saveEmailConfig(this, email, url, key)
        if (showMessage) message(getString(if (valid) R.string.notice_saved else R.string.notice_saved_incomplete))
        return valid
    }

    private fun startMonitoring() {
        if (Prefs.monitoring(this)) return
        PowerStore.get(this).beginSession(this, PowerState.isPlugged(this))
        Prefs.setMonitoring(this, true)
        try {
            MonitorService.start(this)
            message(getString(R.string.notice_monitor_started))
        } catch (ex: RuntimeException) {
            Prefs.setMonitoring(this, false)
            PowerStore.get(this).endSession(this)
            message(getString(R.string.notice_monitor_error, ex.message ?: ex.javaClass.simpleName))
        }
        updateUi()
    }

    private fun stopMonitoring() {
        if (!Prefs.monitoring(this)) return
        Prefs.setMonitoring(this, false)
        PowerStore.get(this).endSession(this)
        stopService(Intent(this, MonitorService::class.java))
        message(getString(R.string.notice_monitor_stopped))
        updateUi()
    }

    private fun sendTest() {
        if (!Prefs.emailEnabled(this)) {
            message(getString(R.string.notice_email_off))
            return
        }
        if (!saveConfig(false)) return
        val test = PowerStore.get(this).queueTest(this)
        if (test != null) {
            EmailScheduler.schedule(this)
            EmailSender.kick(this)
            message(getString(R.string.notice_test_queued))
        }
        updateUi()
    }

    private fun updateUi() {
        if (!::statusLabel.isInitialized) return
        val store = PowerStore.get(this)
        val monitoring = Prefs.monitoring(this)
        val plugged = PowerState.isPlugged(this)
        statusLabel.text = when {
            !monitoring -> getString(R.string.status_stopped,
                getString(if (plugged) R.string.power_connected else R.string.power_disconnected))
            plugged -> getString(R.string.status_monitoring_on)
            else -> getString(R.string.status_monitoring_off)
        }
        statusLabel.setTextColor(when {
            !monitoring -> Color.DKGRAY
            plugged -> Color.rgb(18, 112, 68)
            else -> Color.rgb(188, 45, 35)
        })
        startButton.isEnabled = !monitoring
        stopButton.isEnabled = monitoring
        val recent = store.outages(20)
        val all = store.outages()
        val confirmed = all.filter { it.endMs != null && it.endSource != "MONITOR_GAP" && it.endMs >= it.startMs }
        val totalMillis = confirmed.sumOf { (it.endMs ?: it.startMs) - it.startMs }
        summaryLabel.text = getString(R.string.summary,
            store.totalOutages(), DateFormats.elapsed(this, totalMillis), store.pendingCount())
        historyLabel.text = if (recent.isEmpty()) getString(R.string.history_empty)
        else recent.joinToString("\n\n") { outage ->
            val title = getString(R.string.history_start, DateFormats.moment(outage.startMs))
            val end = when {
                outage.endMs == null -> getString(R.string.history_ongoing,
                    DateFormats.elapsed(this, System.currentTimeMillis() - outage.startMs))
                outage.endSource == "MONITOR_GAP" -> getString(R.string.history_monitor_gap)
                else -> getString(R.string.history_end, DateFormats.moment(outage.endMs),
                    DateFormats.elapsed(this, outage.endMs - outage.startMs))
            }
            val accuracy = if (outage.startSource == "BROADCAST" && outage.endSource == "BROADCAST")
                getString(R.string.history_quality_broadcast) else getString(R.string.history_quality_estimated)
            "$title\n$end\n$accuracy"
        }
        val fail = store.latestFailure()
        val onOff = getString(if (Prefs.emailEnabled(this)) R.string.enabled else R.string.disabled)
        sendStatusLabel.text = getString(R.string.send_status, onOff, store.pendingCount()) +
            if (fail.isNotBlank()) getString(R.string.send_error, fail) else ""
    }

    private fun exportCsv() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "powerlogger_$stamp.csv")
        }, 78)
    }

    @Deprecated("Use Activity Result API when moving to AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 78 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { PowerStore.get(this).exportCsv(it, this) }
                ?: throw IllegalStateException("Cannot open the selected file")
            message(getString(R.string.notice_csv_saved))
        } catch (ex: Exception) {
            message(getString(R.string.notice_csv_error, ex.message ?: ex.javaClass.simpleName))
        }
    }

    private fun message(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
