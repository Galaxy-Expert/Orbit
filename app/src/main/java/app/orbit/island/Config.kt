package app.orbit.island

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

data class OrbitConfig(
    val enabled: Boolean = true,
    val hideStatusIcons: Boolean = true,
    val orbitAnim: Boolean = true,
    val orbitIntervalSec: Int = 12,
    val popupSec: Int = 4,
    val offsetXdp: Int = 0,
    val offsetYdp: Int = 0,
    val virtualIdleVisible: Boolean = true,
    val idleBattery: Boolean = true,
    val tapAction: Int = 0, // 0 = gizlenen ikonları göster, 1 = menü aç (uzun basış diğeri)
    val revealSec: Int = 5,
    val animSpeedPct: Int = 100,
    val haptics: Boolean = true,
    val notifWidthDp: Int = 380,
    val accent: Int = DEFAULT_ACCENT
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("enabled", enabled)
        putBoolean("hideStatusIcons", hideStatusIcons)
        putBoolean("orbitAnim", orbitAnim)
        putInt("orbitIntervalSec", orbitIntervalSec)
        putInt("popupSec", popupSec)
        putInt("offsetXdp", offsetXdp)
        putInt("offsetYdp", offsetYdp)
        putBoolean("virtualIdleVisible", virtualIdleVisible)
        putBoolean("idleBattery", idleBattery)
        putInt("tapAction", tapAction)
        putInt("revealSec", revealSec)
        putInt("animSpeedPct", animSpeedPct)
        putBoolean("haptics", haptics)
        putInt("notifWidthDp", notifWidthDp)
        putInt("accent", accent)
    }

    companion object {
        const val DEFAULT_ACCENT = 0xFF3D8BFF.toInt()

        fun fromBundle(b: Bundle): OrbitConfig {
            val d = OrbitConfig()
            return OrbitConfig(
                enabled = b.getBoolean("enabled", d.enabled),
                hideStatusIcons = b.getBoolean("hideStatusIcons", d.hideStatusIcons),
                orbitAnim = b.getBoolean("orbitAnim", d.orbitAnim),
                orbitIntervalSec = b.getInt("orbitIntervalSec", d.orbitIntervalSec),
                popupSec = b.getInt("popupSec", d.popupSec),
                offsetXdp = b.getInt("offsetXdp", d.offsetXdp),
                offsetYdp = b.getInt("offsetYdp", d.offsetYdp),
                virtualIdleVisible = b.getBoolean("virtualIdleVisible", d.virtualIdleVisible),
                idleBattery = b.getBoolean("idleBattery", d.idleBattery),
                tapAction = b.getInt("tapAction", d.tapAction),
                revealSec = b.getInt("revealSec", d.revealSec),
                animSpeedPct = b.getInt("animSpeedPct", d.animSpeedPct),
                haptics = b.getBoolean("haptics", d.haptics),
                notifWidthDp = b.getInt("notifWidthDp", d.notifWidthDp),
                accent = b.getInt("accent", d.accent)
            )
        }
    }
}

/** Ayarlar modül uygulamasında SharedPreferences'ta tutulur, SystemUI'ye ContentProvider ile aktarılır. */
object Prefs {
    const val AUTHORITY = "app.orbit.island.settings"
    const val ACTION_REFRESH = "app.orbit.island.ACTION_REFRESH"
    private const val FILE = "orbit_prefs"

    fun read(ctx: Context): OrbitConfig {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = OrbitConfig()
        return OrbitConfig(
            enabled = p.getBoolean("enabled", d.enabled),
            hideStatusIcons = p.getBoolean("hideStatusIcons", d.hideStatusIcons),
            orbitAnim = p.getBoolean("orbitAnim", d.orbitAnim),
            orbitIntervalSec = p.getInt("orbitIntervalSec", d.orbitIntervalSec),
            popupSec = p.getInt("popupSec", d.popupSec),
            offsetXdp = p.getInt("offsetXdp", d.offsetXdp),
            offsetYdp = p.getInt("offsetYdp", d.offsetYdp),
            virtualIdleVisible = p.getBoolean("virtualIdleVisible", d.virtualIdleVisible),
            idleBattery = p.getBoolean("idleBattery", d.idleBattery),
            tapAction = p.getInt("tapAction", d.tapAction),
            revealSec = p.getInt("revealSec", d.revealSec),
            animSpeedPct = p.getInt("animSpeedPct", d.animSpeedPct),
            haptics = p.getBoolean("haptics", d.haptics),
            notifWidthDp = p.getInt("notifWidthDp", d.notifWidthDp),
            accent = p.getInt("accent", d.accent)
        )
    }

    fun write(ctx: Context, c: OrbitConfig) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putBoolean("hideStatusIcons", c.hideStatusIcons)
            .putBoolean("orbitAnim", c.orbitAnim)
            .putInt("orbitIntervalSec", c.orbitIntervalSec)
            .putInt("popupSec", c.popupSec)
            .putInt("offsetXdp", c.offsetXdp)
            .putInt("offsetYdp", c.offsetYdp)
            .putBoolean("virtualIdleVisible", c.virtualIdleVisible)
            .putBoolean("idleBattery", c.idleBattery)
            .putInt("tapAction", c.tapAction)
            .putInt("revealSec", c.revealSec)
            .putInt("animSpeedPct", c.animSpeedPct)
            .putBoolean("haptics", c.haptics)
            .putInt("notifWidthDp", c.notifWidthDp)
            .putInt("accent", c.accent)
            .apply()
        // SystemUI'ye "ayarlar değişti" haberi
        ctx.sendBroadcast(Intent(ACTION_REFRESH).setPackage("com.android.systemui"))
    }
}

/** SystemUI sürecindeki güncel ayar. */
object OrbitState {
    @Volatile
    var config: OrbitConfig = OrbitConfig()

    fun refresh(ctx: Context): Boolean {
        return try {
            val b = ctx.contentResolver.call(Uri.parse("content://${Prefs.AUTHORITY}"), "get", null, null)
            if (b != null) {
                config = OrbitConfig.fromBundle(b)
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            OLog.e("settings provider could not be read (defaults in use): ${t.message}")
            false
        }
    }
}
