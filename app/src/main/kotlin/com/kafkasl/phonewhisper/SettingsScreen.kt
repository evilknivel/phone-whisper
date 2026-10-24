package com.kafkasl.phonewhisper

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Base for the settings screens, built in code like the rest of the UI. A screen calls
 * [setScreen] in onCreate; rows that show a current value register an updater with [onRefresh],
 * and all of them run in [refresh] (on resume and after every change).
 */
abstract class SettingsScreen : AppCompatActivity() {

    private val refreshers = mutableListOf<() -> Unit>()

    /** Build the screen. Calling it again (the setup assistant does, once per step) replaces it. */
    protected fun setScreen(title: String, content: LinearLayout.() -> Unit) {
        refreshers.clear()
        val root = vertical(0, 0)
        root.addView(TextView(this).apply {
            text = title
            textSize = if (this@SettingsScreen is MainActivity) 32f else 28f
            setPadding(dp(24), dp(32), dp(24), dp(16))
        })
        root.content()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            clipToPadding = false
            addView(root)
        }
        // targetSdk 35+ draws edge-to-edge: keep content clear of the status and navigation bars.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Re-read prefs into every row. Subclasses add their own updates and call super. */
    open fun refresh() = refreshers.forEach { it() }

    protected fun onRefresh(update: () -> Unit) { refreshers += update }

    // --- Rows ---

    protected fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }

        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })

        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    protected fun LinearLayout.subtitle(): TextView = findViewWithTag("subtitle")

    /** A boolean pref as a switch row; [onChange] runs after the pref is written. */
    protected fun switchRow(
        title: String, subtitle: String, key: String, default: Boolean, onChange: (Boolean) -> Unit = {}
    ): LinearLayout = toggleRow(title, subtitle, get = { prefs().getBoolean(key, default) }) {
        prefs().edit().putBoolean(key, it).apply()
        onChange(it)
    }

    /** A switch row for a value that isn't stored as a plain boolean pref. */
    protected fun toggleRow(title: String, subtitle: String, get: () -> Boolean, set: (Boolean) -> Unit): LinearLayout {
        val switch = MaterialSwitch(this).apply { isClickable = false }
        val row = settingsRow(title, subtitle, switch) {
            set(!get())
            refresh()
        }
        onRefresh { switch.isChecked = get() }
        return row
    }

    /** A row whose subtitle shows the current choice; tapping it offers [options] (pref value to label). */
    protected fun <T> choiceRow(
        title: String, options: List<Pair<T, String>>, current: () -> T, onPick: (T) -> Unit
    ): LinearLayout {
        val row = settingsRow(title, "") {
            val index = options.indexOfFirst { it.first == current() }
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), index) { dialog, which ->
                    onPick(options[which].first)
                    dialog.dismiss()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val sub = row.subtitle()
        onRefresh {
            sub.text = options.firstOrNull { it.first == current() }?.second.orEmpty()
            sub.visibility = View.VISIBLE
        }
        return row
    }

    /** A row that opens another settings screen. */
    protected fun navRow(title: String, subtitle: String, screen: Class<out SettingsScreen>): LinearLayout =
        settingsRow(title, subtitle, TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }) { startActivity(Intent(this, screen)) }

    /** A short explanation under a group of rows. */
    protected fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(attrColor(android.R.attr.textColorSecondary))
        setPadding(dp(24), dp(4), dp(24), dp(12))
    }

    protected fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    protected fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    protected fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    protected fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    protected fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    protected fun prefs(): SharedPreferences = Prefs.of(this)
    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
