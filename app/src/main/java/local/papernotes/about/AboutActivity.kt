package local.papernotes.about

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import local.papernotes.R

/**
 * Über-Seite: Logo, Version, "Neu in Version", Beschreibung, Lizenzhinweis und Link zum Quellcode.
 * Beim Schließen (Zurück-Pfeil, Zurück-Taste, "Schließen") wird die gesehene Version gemerkt, danach erscheint die Seite
 * nur noch über das Menü (siehe [AboutPolicy]).
 */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        val version = currentVersion(this)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.textVersion).text = getString(R.string.about_version_format, version)
        findViewById<TextView>(R.id.textNewsTitle).text = getString(R.string.about_news_title, version)
        val news = findViewById<LinearLayout>(R.id.newsItems)
        resources.getStringArray(R.array.about_news_items).forEach { item ->
            news.addView(
                TextView(this).apply {
                    text = getString(R.string.about_bullet_format, item)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(getColor(R.color.black))
                    setPadding(0, dp(4), 0, dp(4))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        findViewById<TextView>(R.id.linkSource).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { openSource() }
        }
        findViewById<TextView>(R.id.buttonClose).setOnClickListener { finish() }
    }

    /** Alle Wege zum Schließen laufen über finish(): hier wird die gesehene Version geschrieben. */
    override fun finish() {
        markSeen(this)
        super.finish()
    }

    private fun openSource() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    companion object {
        const val SOURCE_URL = "https://github.com/hjochman/PaperNotes-BY-BOB"
        private const val PREFS = "about"
        private const val KEY_LAST_SEEN = "about_last_seen_version"

        /** versionName aus dem PackageManager (wie in den Einstellungen); leer, wenn unbekannt. */
        fun currentVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }

        /** Wahr, wenn die Über-Seite beim Start automatisch erscheinen soll. */
        fun shouldShowOnStart(context: Context): Boolean {
            val lastSeen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_SEEN, null)
            return AboutPolicy.shouldShowAbout(lastSeen, currentVersion(context))
        }

        private fun markSeen(context: Context) {
            val version = currentVersion(context)
            if (version.isEmpty()) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_SEEN, version).apply()
        }
    }
}
