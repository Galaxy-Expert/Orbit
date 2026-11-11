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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var cfg: OrbitConfig

    private val accentNames = listOf("Blue", "Green", "Purple", "Orange", "Pink", "White")
    private val accentColors = intArrayOf(
        0xFF3D8BFF.toInt(), 0xFF34C759.toInt(), 0xFFAF52DE.toInt(),
        0xFFFF9F0A.toInt(), 0xFFFF2D55.toInt(), 0xFFFFFFFF.toInt()
    )

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
                "Dynamic-island style notifications around your camera cutout " +
                    "(center / left / right punch-hole). Without a camera, a capsule appears at the right of the status bar.\n" +
                    "Enable this module for System UI in Vector/LSPosed, then restart System UI (or reboot).",
                14f, topDp = 8
            )
        )

        root.addView(section("General"))
        root.addView(switchRow("Enable Orbit", cfg.enabled) { save(cfg.copy(enabled = it)) })
        root.addView(switchRow("Hide status bar icons (keep clock)", cfg.hideStatusIcons) { save(cfg.copy(hideStatusIcons = it)) })
        root.addView(switchRow("Show battery % in the idle island", cfg.idleBattery) { save(cfg.copy(idleBattery = it)) })
        root.addView(switchRow("Show idle capsule when there is no camera", cfg.virtualIdleVisible) { save(cfg.copy(virtualIdleVisible = it)) })
        root.addView(switchRow("Haptic feedback", cfg.haptics) { save(cfg.copy(haptics = it)) })

        root.addView(section("Touch"))
        root.addView(
            spinnerRow(
                "Tap action (long press does the other one)",
                listOf("Reveal hidden status icons", "Open menu"),
                cfg.tapAction.coerceIn(0, 1)
            ) { save(cfg.copy(tapAction = it)) }
        )
        root.addView(seekRow("Reveal duration", 2, 10, cfg.revealSec, "s") { save(cfg.copy(revealSec = it)) })

        root.addView(section("Look and animation"))
        root.addView(
            spinnerRow("Orbit ring color", accentNames, accentColors.indexOf(cfg.accent).coerceAtLeast(0)) {
                save(cfg.copy(accent = accentColors[it]))
            }
        )
        root.addView(switchRow("Orbit ring animation", cfg.orbitAnim) { save(cfg.copy(orbitAnim = it)) })
        root.addView(seekRow("Orbit ring interval", 3, 60, cfg.orbitIntervalSec, "s") { save(cfg.copy(orbitIntervalSec = it)) })
        root.addView(seekRow("Animation speed", 50, 200, cfg.animSpeedPct, "%") { save(cfg.copy(animSpeedPct = it)) })
        root.addView(seekRow("Notification display time", 2, 10, cfg.popupSec, "s") { save(cfg.copy(popupSec = it)) })
        root.addView(seekRow("Notification width", 260, 460, cfg.notifWidthDp, "dp") { save(cfg.copy(notifWidthDp = it)) })

        root.addView(section("Position fine-tuning"))
        root.addView(seekRow("Horizontal offset", -12, 12, cfg.offsetXdp, "dp") { save(cfg.copy(offsetXdp = it)) })
        root.addView(seekRow("Vertical offset", -12, 12, cfg.offsetYdp, "dp") { save(cfg.copy(offsetYdp = it)) })

        root.addView(section("Test"))
        root.addView(Button(this).apply {
            text = "Send test notification (in 3 s)"
            setOnClickListener { sendTest() }
        })
        root.addView(
            label(
                "Tip: after pressing the button, go back to the home screen and wait. " +
                    "Logs: Vector -> Logs, or adb logcat -s Orbit",
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

    private fun section(s: String): TextView = label(s, 16f, bold = true, topDp = 24)

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

    private fun spinnerRow(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(TextView(this).apply { text = title })
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var first = true

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (first) {
                    first = false
                    return
                }
                onSelect(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        box.addView(sp, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
