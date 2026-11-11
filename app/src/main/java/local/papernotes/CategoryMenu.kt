package local.papernotes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import local.papernotes.backup.BackupsActivity
import local.papernotes.home.HomeModel
import local.papernotes.storage.BackupStore
import local.papernotes.storage.NoteRepository
import local.papernotes.trash.TrashActivity
import java.util.concurrent.Executor

/** Ziele des Kategorie-Menüs (Titel der Startseite, des Papierkorbs und der Sicherungen). */
enum class CategoryTarget { ALL, UNCATEGORIZED, FAVORITES, TRASH, BACKUPS }

/**
 * Gemeinsames Kategorie-Menü: Startseiten-Kategorien, Papierkorb und Sicherungen mit Zähler.
 * Papierkorb und Sicherungen zeigen denselben Titel mit Menü, damit man von dort weiterschalten kann.
 */
object CategoryMenu {
    const val EXTRA_CATEGORY = "category"

    private class Item(val icon: Int, val label: String, val count: Int, val target: CategoryTarget)

    /** Zählt im Hintergrund ([io]) und zeigt danach das Menü unter [anchor]. [onSelect] wird im UI-Thread aufgerufen. */
    fun show(activity: Activity, anchor: View, io: Executor, onSelect: (CategoryTarget) -> Unit) {
        if (!NotesLocation.hasAccess(activity)) return
        val root = NotesLocation.root(activity)
        io.execute {
            val counts = HomeModel.categoryCounts(NoteRepository(root))
            val backups = BackupStore(root).listGroups().size
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                val items = listOf(
                    Item(R.drawable.ic_all_notes, activity.getString(R.string.home_all_notes), counts.all, CategoryTarget.ALL),
                    Item(R.drawable.ic_uncategorized, activity.getString(R.string.home_uncategorized), counts.uncategorized, CategoryTarget.UNCATEGORIZED),
                    Item(R.drawable.ic_star, activity.getString(R.string.home_favorites), counts.favorites, CategoryTarget.FAVORITES),
                    Item(R.drawable.ic_trash, activity.getString(R.string.home_recently_deleted), counts.trash, CategoryTarget.TRASH),
                    Item(R.drawable.ic_backup, activity.getString(R.string.settings_backups), backups, CategoryTarget.BACKUPS),
                )
                popup(activity, anchor, items, onSelect)
            }
        }
    }

    /** Wechsel aus dem Papierkorb oder den Sicherungen ([current]) zu [target]. */
    fun switchFrom(activity: Activity, current: CategoryTarget, target: CategoryTarget) {
        if (target == current) return
        when (target) {
            CategoryTarget.TRASH -> {
                activity.startActivity(Intent(activity, TrashActivity::class.java))
                activity.finish()
            }
            CategoryTarget.BACKUPS -> {
                activity.startActivity(Intent(activity, BackupsActivity::class.java))
                activity.finish()
            }
            else -> activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .putExtra(EXTRA_CATEGORY, target.name)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun popup(activity: Activity, anchor: View, items: List<Item>, onSelect: (CategoryTarget) -> Unit) {
        fun dp(v: Int) = Math.round(v * activity.resources.displayMetrics.density)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bg_dialog)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        lateinit var popup: PopupWindow
        items.forEachIndexed { i, item ->
            if (i > 0) {
                content.addView(
                    View(activity).apply { setBackgroundColor(activity.getColor(R.color.line_gray)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                )
            }
            content.addView(row(activity, item) {
                popup.dismiss()
                onSelect(item.target)
            })
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(unspecified, unspecified)
        val minWidth = dp(280)
        val width = maxOf(minWidth, minOf(content.measuredWidth, activity.resources.displayMetrics.widthPixels - dp(16)))
        popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 0f
            animationStyle = 0
            isOutsideTouchable = true
        }
        val root = anchor.rootView
        val pos = IntArray(2)
        anchor.getLocationInWindow(pos)
        val margin = dp(8)
        val x = pos[0].coerceIn(margin, maxOf(margin, root.width - width - margin))
        val height = content.measuredHeight
        var y = pos[1] + anchor.height
        if (y + height > root.height - margin) y = pos[1] - height
        y = y.coerceIn(margin, maxOf(margin, root.height - height - margin))
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }

    private fun row(activity: Activity, item: Item, onClick: () -> Unit): View {
        fun dp(v: Int) = Math.round(v * activity.resources.displayMetrics.density)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), 0, dp(16), 0)
            background = activity.getDrawable(R.drawable.bg_tool)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            ImageView(activity).apply {
                setImageResource(item.icon)
                imageTintList = activity.getColorStateList(R.color.icon_tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        row.addView(
            TextView(activity).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(activity.getColorStateList(R.color.text_tint))
                setPadding(dp(16), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            TextView(activity).apply {
                text = item.count.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(activity.getColorStateList(R.color.text_tint))
                setPadding(dp(16), 0, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return row
    }
}
