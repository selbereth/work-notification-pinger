package local.pinger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var ringtoneLabel: TextView

    /** Saved theme choice: "dark", "light", or null to follow the system (the default). */
    private fun themeChoice(): String? = getSharedPreferences(WatchService.PREFS, MODE_PRIVATE).getString(KEY_THEME, null)

    private fun isDark(): Boolean = when (themeChoice()) {
        "dark" -> true
        "light" -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            when (themeChoice()) {
                "dark" -> android.R.style.Theme_DeviceDefault
                "light" -> android.R.style.Theme_DeviceDefault_Light
                else -> if (Build.VERSION.SDK_INT >= 29) android.R.style.Theme_DeviceDefault_DayNight
                else android.R.style.Theme_DeviceDefault
            }
        )
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 18f }
        ringtoneLabel = TextView(this).apply { textSize = 14f }

        val enable = Button(this).apply {
            text = "1. Grant notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val chooseRingtone = Button(this).apply {
            text = "Choose ringtone"
            setOnClickListener { openRingtonePicker() }
        }
        val cooldownLabel = TextView(this).apply {
            textSize = 14f
            text = "Quiet minutes needed before a notification rings (e.g. 0.5, 2, 4.5). Each new notification restarts the wait:"
        }
        val cooldownInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(formatMinutes(WatchService.cooldownMinutes(this@MainActivity)))
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) saveCooldown(view as EditText)
                false // let the keyboard close as usual
            }
            setOnFocusChangeListener { view, hasFocus -> if (!hasFocus) saveCooldown(view as EditText) }
        }
        val test = Button(this).apply {
            text = "Test ping"
            setOnClickListener {
                if (!WatchService.test()) {
                    Toast.makeText(this@MainActivity, "Grant notification access first", Toast.LENGTH_SHORT).show()
                }
                refresh()
            }
        }
        val ack = Button(this).apply {
            text = "Acknowledge (stop pinging)"
            setOnClickListener {
                WatchService.acknowledge()
                refresh()
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(enable)
                addView(chooseRingtone)
                addView(ringtoneLabel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(cooldownLabel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(cooldownInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(test)
                addView(ack)
            }
        )

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_THEME, Menu.NONE, if (isDark()) "Light" else "Dark")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_THEME) return super.onOptionsItemSelected(item)
        getSharedPreferences(WatchService.PREFS, MODE_PRIVATE).edit()
            .putString(KEY_THEME, if (isDark()) "light" else "dark")
            .apply()
        recreate()
        return true
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pinger ringtone (default: random high/low pings)")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, WatchService.savedRingtoneUri(this@MainActivity))
        }
        startActivityForResult(intent, REQUEST_RINGTONE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RINGTONE) return
        val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        getSharedPreferences(WatchService.PREFS, MODE_PRIVATE).edit()
            .putString(WatchService.KEY_RINGTONE_URI, uri?.toString())
            .apply()
        refresh()
    }

    private fun refresh() {
        status.text = if (WatchService.pending) {
            "PINGING: ${WatchService.source} notification waiting. Check it, then acknowledge."
        } else {
            "Idle. Watching ${WatchService.WATCHED.values.joinToString(" and ")}."
        }
        val savedUri = WatchService.savedRingtoneUri(this)
        ringtoneLabel.text = "Ringtone: " + if (savedUri != null) {
            runCatching { RingtoneManager.getRingtone(this, savedUri)?.getTitle(this) }.getOrNull() ?: "custom"
        } else {
            "default (random high/low pings)"
        }
    }

    /** Saves the typed cooldown and confirms with a toast; ignores invalid or unchanged values. */
    private fun saveCooldown(input: EditText) {
        val minutes = input.text.toString().toFloatOrNull()?.takeIf { it >= 0f }
        if (minutes == null) {
            input.error = "Enter minutes, e.g. 0.5 or 2"
            return
        }
        if (minutes == WatchService.cooldownMinutes(this)) return
        getSharedPreferences(WatchService.PREFS, MODE_PRIVATE).edit()
            .putFloat(WatchService.KEY_COOLDOWN_MIN, minutes)
            .apply()
        Toast.makeText(this, "Updated: ${formatMinutes(minutes)} min", Toast.LENGTH_SHORT).show()
    }

    private fun formatMinutes(minutes: Float): String = minutes.toString().removeSuffix(".0")

    companion object {
        private const val REQUEST_RINGTONE = 1
        private const val MENU_THEME = 1
        private const val KEY_THEME = "theme"
    }
}
