package local.papernotes.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Einstellungen und letzter Stand des Sync (privater App-Speicher). Das App-Passwort liegt getrennt im [CredentialStore]. */
internal class SyncSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(K_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_URL, v).apply()

    var user: String
        get() = prefs.getString(K_USER, "") ?: ""
        set(v) = prefs.edit().putString(K_USER, v).apply()

    var target: String
        get() = prefs.getString(K_TARGET, SyncPaths.DEFAULT_TARGET) ?: SyncPaths.DEFAULT_TARGET
        set(v) = prefs.edit().putString(K_TARGET, v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, true)
        set(v) = prefs.edit().putBoolean(K_ENABLED, v).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(K_WIFI, true)
        set(v) = prefs.edit().putBoolean(K_WIFI, v).apply()

    /** Zeitpunkt des letzten vollständig erfolgreichen Laufs (ms), 0 = nie. */
    var lastSuccess: Long
        get() = prefs.getLong(K_LAST_OK, 0L)
        set(v) = prefs.edit().putLong(K_LAST_OK, v).apply()

    /** Art des letzten Fehlers ([RemoteException.Kind]-Name oder [SyncStatusLogic.ERROR_STORAGE]) oder null. */
    var lastError: String?
        get() = prefs.getString(K_ERROR, null)
        set(v) = prefs.edit().apply { if (v == null) remove(K_ERROR) else putString(K_ERROR, v) }.apply()

    var pending: Int
        get() = prefs.getInt(K_PENDING, 0)
        set(v) = prefs.edit().putInt(K_PENDING, v).apply()

    var needsConfirmation: Boolean
        get() = prefs.getBoolean(K_CONFIRM, false)
        set(v) = prefs.edit().putBoolean(K_CONFIRM, v).apply()

    /** Ein Konto ist eingerichtet, sobald ein Benutzername gespeichert ist (das Passwort prüft der Lauf selbst). */
    val configured: Boolean get() = user.isNotEmpty()

    val scope: String get() = SyncPaths.scope(baseUrl, user, target)

    /** Meldet ab: Konto und Fehlerstand weg; Adresse, Zielordner und Optionen bleiben. */
    fun clearAccount() {
        prefs.edit().remove(K_USER).remove(K_ERROR).remove(K_LAST_OK).remove(K_PENDING).remove(K_CONFIRM).apply()
    }

    fun facts(running: Boolean, online: Boolean) = SyncFacts(
        configured = configured, enabled = enabled, running = running, pending = pending,
        error = lastError, needsConfirmation = needsConfirmation, online = online,
    )

    companion object {
        private const val FILE = "sync_settings"
        private const val K_URL = "base_url"
        private const val K_USER = "user"
        private const val K_TARGET = "target"
        private const val K_ENABLED = "enabled"
        private const val K_WIFI = "wifi_only"
        private const val K_LAST_OK = "last_success"
        private const val K_ERROR = "last_error"
        private const val K_PENDING = "pending"
        private const val K_CONFIRM = "confirm"
    }
}

/** Ist ein brauchbares Netz da? Mit [wifiOnly] zählt nur ein ungemessenes Netz. */
internal object NetworkCheck {
    fun online(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return false
        }
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
