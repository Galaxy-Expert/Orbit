package app.orbit.island

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var cfg: OrbitConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Prefs.read(this)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label("Orbit", 28f, bold = true))
        root.addView(
            label(
                "Dynamic-island style notifications around your camera cutout.\n" +
                    "Enable this module for System UI in Vector/LSPosed, then restart System UI (or reboot).",
                14f, topDp = 8
            )
        )

        root.addView(switchRow("Enable Orbit", cfg.enabled) { save(cfg.copy(enabled = it)) })
        root.addView(switchRow("Hide status bar icons (keep clock)", cfg.hideStatusIcons) { save(cfg.copy(hideStatusIcons = it)) })
        root.addView(switchRow("Orbit ring animation", cfg.orbitAnim) { save(cfg.copy(orbitAnim = it)) })

        root.addView(seekRow("Orbit ring interval", 3, 60, cfg.orbitIntervalSec, "s") { save(cfg.copy(orbitIntervalSec = it)) })
        root.addView(seekRow("Notification display time", 2, 10, cfg.popupSec, "s") { save(cfg.copy(popupSec = it)) })
        root.addView(seekRow("Vertical offset", -12, 12, cfg.offsetYdp, "dp") { save(cfg.copy(offsetYdp = it)) })

        root.addView(Button(this).apply {
            text = "Send test notification (in 3 s)"
            setOnClickListener { sendTest() }
        })
        root.addView(
            label(
                "Tip: after pressing the button, go back to the home screen and wait. " +
                    "Logs: adb logcat -s Orbit",
                12f, topDp = 8
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save(c: OrbitConfig) {
        cfg = c
        Prefs.write(this, c)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(s: String, sp: Float, bold: Boolean = false, topDp: Int = 0): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                this.topMargin = dp(topDp)
            }
        }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun seekRow(title: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val tv = TextView(this).apply { text = "$title: $value $unit" }
        val sb = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    tv.text = "$title: $progress $unit"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onChange(seekBar?.progress ?: value)
                }
            })
        }
        box.addView(tv)
        box.addView(sb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    private fun sendTest() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("orbit_test", "Orbit test", NotificationManager.IMPORTANCE_HIGH)
        )
        Toast.makeText(this, "Test notification in 3 seconds…", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({
            val n = Notification.Builder(this, "orbit_test")
                .setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle("Orbit test")
                .setContentText("If you can see this in the island, it works!")
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        }, 3000L)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendTest()
        }
    }
}
