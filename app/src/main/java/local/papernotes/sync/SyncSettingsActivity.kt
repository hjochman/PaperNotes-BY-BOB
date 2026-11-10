package local.papernotes.sync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import local.papernotes.EinkDialog
import local.papernotes.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Einstellungen des Nextcloud-Sync: Konto (Anmeldung per Login Flow v2 im Browser, Abmelden), Server-Adresse,
 * Zielordner, Ein/Aus, "nur WLAN", Hinweis zur Akku-Ausnahme, Vollabgleich und Status. Netz- und Dateiarbeit läuft nie im
 * UI-Thread. Nach jeder Änderung werden die Jobs über [SyncCoordinator.onSettingsChanged] neu aufgebaut.
 */
class SyncSettingsActivity : Activity() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val settings by lazy { SyncSettings(this) }

    private lateinit var textStatus: TextView
    private lateinit var textStatusDetail: TextView
    private lateinit var textAccount: TextView
    private lateinit var textServer: TextView
    private lateinit var textTarget: TextView
    private lateinit var textEnabled: TextView
    private lateinit var textWifi: TextView

    @Volatile
    private var loginCancelled = false
    private var loginProgress: EinkDialog.Progress? = null

    private val statusListener = Runnable { if (!isDestroyed) refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)

        textStatus = findViewById(R.id.textStatus)
        textStatusDetail = findViewById(R.id.textStatusDetail)
        textAccount = findViewById(R.id.textAccount)
        textServer = findViewById(R.id.textServer)
        textTarget = findViewById(R.id.textTarget)
        textEnabled = findViewById(R.id.textEnabled)
        textWifi = findViewById(R.id.textWifi)
        findViewById<TextView>(R.id.textFullHint).setText(R.string.sync_full_hint)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowAccount).setOnClickListener { if (settings.configured) askSignOut() else startLogin() }
        findViewById<View>(R.id.rowServer).setOnClickListener { editServer() }
        findViewById<View>(R.id.rowTarget).setOnClickListener { editTarget() }
        findViewById<View>(R.id.rowEnabled).setOnClickListener {
            settings.enabled = !settings.enabled
            changed()
        }
        findViewById<View>(R.id.rowWifi).setOnClickListener {
            settings.wifiOnly = !settings.wifiOnly
            changed()
        }
        findViewById<View>(R.id.rowFull).setOnClickListener { fullSync() }
        findViewById<View>(R.id.rowBattery).setOnClickListener { openBatterySettings() }
    }

    override fun onResume() {
        super.onResume()
        SyncCoordinator.addListener(statusListener)
        SyncCoordinator.onNotesChanged(this)
        refresh()
    }

    override fun onPause() {
        SyncCoordinator.removeListener(statusListener)
        super.onPause()
    }

    override fun onDestroy() {
        loginCancelled = true
        loginProgress?.dismiss()
        io.shutdown()
        super.onDestroy()
    }

    private fun changed() {
        SyncCoordinator.onSettingsChanged(this)
        refresh()
    }

    private fun refresh() {
        val status = SyncCoordinator.status(this)
        textStatus.text = SyncTexts.status(this, status)
        textStatusDetail.text = getString(R.string.sync_pending_count, status.pending) + "\n" +
            SyncTexts.lastSuccess(this, settings.lastSuccess)
        textAccount.text = if (settings.configured) {
            getString(R.string.sync_account_as, settings.user) + "\n" + getString(R.string.sync_sign_out)
        } else {
            getString(R.string.sync_account_none) + "\n" + getString(R.string.sync_sign_in)
        }
        textServer.text = settings.baseUrl.ifEmpty { getString(R.string.sync_server_none) }
        textTarget.text = settings.target
        textEnabled.text = getString(if (settings.enabled) R.string.sync_on else R.string.sync_status_off)
        textWifi.text = getString(if (settings.wifiOnly) R.string.sync_on else R.string.sync_status_off)
        showLegend(status.ui)
    }

    /** Alle Symbolzustände untereinander, so wie sie auf Startseite und Widget aussehen; der aktuelle ist fett. */
    private fun showLegend(current: SyncUi) {
        val box = findViewById<ViewGroup>(R.id.legendContainer)
        box.removeAllViews()
        val rows = listOf(
            SyncUi.SYNCED to R.string.sync_legend_synced,
            SyncUi.PENDING to R.string.sync_legend_pending,
            SyncUi.OFFLINE to R.string.sync_legend_offline,
            SyncUi.RUNNING to R.string.sync_legend_running,
            SyncUi.CONFIRM to R.string.sync_legend_confirm,
            SyncUi.AUTH to R.string.sync_legend_auth,
            SyncUi.ERROR to R.string.sync_legend_error,
            SyncUi.OFF to R.string.sync_legend_off,
        )
        for ((ui, label) in rows) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_sync_legend, box, false)
            val sample = SyncStatus(ui, if (ui == SyncUi.PENDING || ui == SyncUi.OFFLINE) 3 else 0)
            row.findViewById<TextView>(R.id.legendBadge).text = SyncStatusLogic.badge(sample)
            row.findViewById<View>(R.id.legendSymbol).alpha = if (SyncStatusLogic.dimmed(sample)) DIM_ALPHA else 1f
            row.findViewById<TextView>(R.id.legendText).apply {
                setText(label)
                setTypeface(typeface, if (ui == current) Typeface.BOLD else Typeface.NORMAL)
            }
            box.addView(row)
        }
    }

    // ---------------------------------------------------------------- Server und Zielordner

    private fun editServer() {
        if (settings.configured && settings.baseUrl.isNotEmpty()) {
            Toast.makeText(this, R.string.sync_server_locked, Toast.LENGTH_LONG).show()
            return
        }
        EinkDialog.input(
            this,
            getString(R.string.sync_server),
            getString(R.string.sync_server_hint),
            settings.baseUrl,
            getString(R.string.dialog_ok),
            validate = { if (SyncPaths.normalizeBaseUrl(it) == null) getString(R.string.sync_server_invalid) else null },
            onOk = { text ->
                SyncPaths.normalizeBaseUrl(text)?.let { settings.baseUrl = it }
                changed()
            },
        )
    }

    private fun editTarget() {
        EinkDialog.input(
            this,
            getString(R.string.sync_target),
            getString(R.string.sync_target_hint),
            settings.target,
            getString(R.string.dialog_ok),
            validate = { if (SyncPaths.normalizeTarget(it) == null) getString(R.string.sync_target_invalid) else null },
            onOk = { text ->
                val normalized = SyncPaths.normalizeTarget(text) ?: return@input
                if (normalized != settings.target) {
                    settings.target = normalized
                    changed()
                    EinkDialog.message(this, getString(R.string.sync_target), getString(R.string.sync_target_changed), getString(R.string.dialog_ok))
                }
            },
        )
    }

    // ---------------------------------------------------------------- Anmeldung

    private fun startLogin() {
        val base = SyncPaths.normalizeBaseUrl(settings.baseUrl)
        if (base == null) {
            Toast.makeText(this, R.string.sync_server_first, Toast.LENGTH_LONG).show()
            editServer()
            return
        }
        EinkDialog.confirm(this, getString(R.string.sync_login_title), getString(R.string.sync_login_message), getString(R.string.sync_sign_in)) {
            runLogin(base)
        }
    }

    private fun runLogin(base: String) {
        loginCancelled = false
        val progress = EinkDialog.progress(this, getString(R.string.sync_login_title), getString(R.string.sync_login_waiting)) {
            loginCancelled = true
        }
        loginProgress = progress
        io.execute {
            val client = WebDavRemote.newClient()
            try {
                val flow = LoginFlow(client, base)
                val session = flow.start()
                val opened = openBrowser(session.loginUrl)
                if (!opened) {
                    finishLogin(progress, R.string.sync_login_no_browser)
                    return@execute
                }
                val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                while (!loginCancelled && System.currentTimeMillis() < deadline) {
                    val result = try {
                        flow.poll(session)
                    } catch (e: RemoteException) {
                        if (e.kind != RemoteException.Kind.NETWORK) throw e
                        null
                    }
                    if (result != null) {
                        CredentialStore(this).save(result.appPassword)
                        settings.user = result.loginName
                        settings.lastError = null
                        runOnUiThread {
                            progress.dismiss()
                            Toast.makeText(this, R.string.sync_login_ok, Toast.LENGTH_SHORT).show()
                            changed()
                            SyncCoordinator.syncNow(this) { }
                        }
                        return@execute
                    }
                    Thread.sleep(POLL_MS)
                }
                if (!loginCancelled) finishLogin(progress, R.string.sync_login_timeout)
            } catch (e: RemoteException) {
                finishLogin(progress, R.string.sync_login_failed)
            } catch (e: InterruptedException) {
                // Activity wird beendet.
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun openBrowser(url: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runOnUiThread {
            ok = try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
            latch.countDown()
        }
        latch.await()
        return ok
    }

    private fun finishLogin(progress: EinkDialog.Progress, message: Int) {
        runOnUiThread {
            progress.dismiss()
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun askSignOut() {
        EinkDialog.confirm(this, getString(R.string.sync_sign_out_title), getString(R.string.sync_sign_out_message), getString(R.string.sync_sign_out)) {
            signOut()
        }
    }

    /** Meldet zuerst lokal ab (sofort wirksam) und widerruft das App-Passwort danach im Hintergrund, soweit das Netz da ist. */
    private fun signOut() {
        val creds = CredentialStore(this)
        val secret = creds.load()
        val user = settings.user
        val base = SyncPaths.normalizeBaseUrl(settings.baseUrl)
        creds.clear()
        settings.clearAccount()
        SyncEngine.stateFile(this).delete()
        SyncCoordinator.onSettingsChanged(this)
        refresh()
        if (secret != null && base != null && user.isNotEmpty()) {
            val auth = WebDavRemote.basicAuth(user, secret)
            Thread {
                val client = WebDavRemote.newClient()
                try {
                    LoginFlow.revoke(client, base, auth)
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    // ---------------------------------------------------------------- Sonstiges

    private fun fullSync() {
        if (!settings.configured) {
            Toast.makeText(this, R.string.sync_account_none, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.sync_status_running, Toast.LENGTH_SHORT).show()
        SyncCoordinator.syncNow(this, force = true) { result ->
            SyncTexts.resultMessage(result)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            if (!isDestroyed) refresh()
        }
    }

    private fun openBatterySettings() {
        EinkDialog.message(this, getString(R.string.sync_battery_hint), getString(R.string.sync_battery_hint_text), getString(R.string.sync_battery_open)) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private companion object {
        const val DIM_ALPHA = 0.45f
        const val POLL_MS = 3_000L
        const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
