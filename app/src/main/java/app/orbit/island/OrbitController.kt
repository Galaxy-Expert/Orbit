package app.orbit.island

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers
import kotlin.math.min

/**
 * Ada penceresini yönetir: kamera konumunu bulur, pencereyi ekler/günceller,
 * bildirimleri süzer ve OrbitView'a iletir.
 *
 * Kamera durumları:
 *  - Ortada / solda / sağda punch-hole: delik konumu Display.getCutout() ile okunur
 *  - Hiç çentik yok (tablet vb.): status bar'ın sağında elips şeklinde sanal ada
 *  - Çentik var ama üst kenarda değil (ör. telefon yatayda): ada gizlenir
 */
class OrbitController(private val ctx: Context) : OrbitView.Host {

    private class Found(val rect: Rect, val virtual: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val dm: DisplayManager = ctx.getSystemService(DisplayManager::class.java)
    private val km: KeyguardManager = ctx.getSystemService(KeyguardManager::class.java)
    private val view = OrbitView(ctx, this)

    @Volatile
    private var attached = false
    private var started = false
    private var gotConfig = false
    private var retries = 0
    private var params: WindowManager.LayoutParams? = null
    private var lastGeoKey: String? = null
    private var lastKey: String? = null
    private var lastTime = 0L

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    // ---------------------------------------------------------
    //  Yaşam döngüsü
    // ---------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        main.post {
            try {
                gotConfig = OrbitState.refresh(ctx)
                val filter = IntentFilter().apply {
                    addAction(Prefs.ACTION_REFRESH)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ctx.registerReceiver(refreshReceiver, filter, Context.RECEIVER_EXPORTED)
                val sticky = ctx.registerReceiver(
                    batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED
                )
                if (sticky != null) handleBattery(sticky)
                dm.registerDisplayListener(displayListener, main)
                applyGeometry()
                if (!gotConfig) main.postDelayed(retryConfig, 20_000L)
                OLog.i("Orbit started (attached=$attached)")
            } catch (t: Throwable) {
                OLog.e("start error", t)
            }
        }
    }

    private val retryConfig = object : Runnable {
        override fun run() {
            if (gotConfig || retries++ > 15) return
            gotConfig = OrbitState.refresh(ctx)
            if (gotConfig) {
                applyGeometry()
                view.rescheduleSpin()
            } else {
                main.postDelayed(this, 20_000L)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (OrbitState.refresh(ctx)) gotConfig = true
            applyGeometry()
            view.rescheduleSpin()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handleBattery(i)
        }
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val chg = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
        val had = view.hasBattery
        view.setBattery(pct, chg)
        // Pil bilgisi ilk kez geldiğinde kanatlar oluşur, pencere boyutu değişir
        if (had != view.hasBattery) applyGeometry()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyGeometry()
        }
    }

    // ---------------------------------------------------------
    //  Geometri ve pencere
    // ---------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun applyGeometry() {
        try {
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val cfg = OrbitState.config
            if (!cfg.enabled) {
                lastGeoKey = null
                detach()
                return
            }
            val size = Point()
            display.getRealSize(size)
            val found = findCutout(display, size)
            if (found == null) {
                OLog.i("no usable top-edge cutout in this orientation, island hidden")
                lastGeoKey = null
                detach()
                return
            }
            val cut = found.rect
            val key = "${cut.left},${cut.top},${cut.right},${cut.bottom},${size.x},${size.y}," +
                "${found.virtual},${cfg.virtualIdleVisible},${cfg.idleBattery},${view.hasBattery}"
            if (attached && key == lastGeoKey) return
            lastGeoKey = key

            view.applyGeo(OrbitView.Geo(cut, size.x, size.y, found.virtual))
            val r = view.windowRect(false)
            val lp = params ?: createParams(r).also { params = it }
            lp.x = r.left
            lp.y = r.top
            lp.width = r.width()
            lp.height = r.height()
            if (!attached) addWindow(lp) else wm.updateViewLayout(view, lp)
            OLog.i("geometry applied: hole=$cut screen=${size.x}x${size.y} virtual=${found.virtual}")
        } catch (t: Throwable) {
            OLog.e("applyGeometry error", t)
        }
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun findCutout(display: Display, size: Point): Found? {
        val cfg = OrbitState.config
        val cx = size.x / 2
        val rects = display.cutout?.boundingRects ?: emptyList()
        val r: Rect
        var virtual = false
        if (rects.isEmpty()) {
            // Kamera yok: status bar'ın sağında elips şeklinde sanal ada (3:1)
            virtual = true
            val sbH = statusBarHeight()
            val h = min(dp(24), sbH - dp(6)).coerceAtLeast(dp(16))
            val w = h * 3
            val right = size.x - dp(16)
            val top = ((sbH - h) / 2).coerceAtLeast(0)
            r = Rect(right - w, top, right, top + h)
        } else {
            // Android çentikleri bir kenara bağlı bölge olarak bildirir: üst kenara değen en küçük/merkeze yakın olanı al
            val best = rects
                .filter { it.top <= 2 && it.width() < size.x * 2 / 5 }
                .minByOrNull { Math.abs(it.centerX() - cx) }
                ?: return null
            // Kenara bağlı kısım delikten yüksekse deliği sadece alt kısımdaki kare olarak al
            r = if (best.height() > best.width() * 1.15f) {
                Rect(best.left, best.bottom - best.width(), best.right, best.bottom)
            } else {
                Rect(best)
            }
        }
        r.offset(dp(cfg.offsetXdp), dp(cfg.offsetYdp))
        return Found(r, virtual)
    }

    private fun createParams(r: Rect): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            r.width(), r.height(), 2024, // TYPE_NAVIGATION_BAR_PANEL: durum çubuğunun üstünde
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left
            y = r.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "OrbitIsland"
        }
    }

    private fun addWindow(lp: WindowManager.LayoutParams) {
        for (type in intArrayOf(2024, 2038)) { // olmazsa TYPE_APPLICATION_OVERLAY
            try {
                lp.type = type
                wm.addView(view, lp)
                attached = true
                OLog.i("window added (type=$type)")
                return
            } catch (t: Throwable) {
                OLog.e("addView type=$type failed: ${t.message}")
            }
        }
    }

    private fun detach() {
        if (!attached) return
        try {
            view.resetToIdle()
            wm.removeViewImmediate(view)
        } catch (t: Throwable) {
            OLog.e("removeView error", t)
        }
        attached = false
    }

    // ---------------------------------------------------------
    //  Bildirimler
    // ---------------------------------------------------------

    /** true dönerse ada bildirimi gösterecek (çağıran sistemin heads-up'ını iptal edebilir). */
    fun offer(sbn: StatusBarNotification): Boolean {
        if (!attached || !OrbitState.config.enabled || !eligible(sbn)) return false
        val now = SystemClock.uptimeMillis()
        if (sbn.key == lastKey && now - lastTime < 1000L) return true
        lastKey = sbn.key
        lastTime = now
        main.post { view.showNotification(sbn) }
        return true
    }

    private fun eligible(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (km.isKeyguardLocked) return false
        if (n.fullScreenIntent != null) return false
        val f = n.flags
        if ((f and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((f and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((f and Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) return false
        return true
    }

    // ---------------------------------------------------------
    //  OrbitView.Host
    // ---------------------------------------------------------

    override fun setExpandedWindow(expanded: Boolean) {
        view.windowExpanded = expanded
        val lp = params ?: return
        if (!attached) return
        val r = view.windowRect(expanded)
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width()
        lp.height = r.height()
        try {
            wm.updateViewLayout(view, lp)
        } catch (t: Throwable) {
            OLog.e("updateViewLayout error", t)
        }
    }

    override fun openNotificationPanel() {
        try {
            val sbm = ctx.getSystemService("statusbar")
            XposedHelpers.callMethod(sbm, "expandNotificationsPanel")
        } catch (t: Throwable) {
            OLog.e("expandNotificationsPanel error", t)
        }
    }

    override fun revealStatus() {
        StatusBarCleaner.reveal(OrbitState.config.revealSec.coerceIn(2, 15) * 1000L)
    }

    override fun openSettings() {
        try {
            val i = Intent()
                .setClassName("app.orbit.island", "app.orbit.island.SettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            OLog.e("openSettings error", t)
        }
    }

    override fun launch(sbn: StatusBarNotification) {
        try {
            val pi = sbn.notification.contentIntent ?: return
            val opts = ActivityOptions.makeBasic()
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        } catch (t: Throwable) {
            OLog.e("contentIntent error", t)
        }
    }

    override fun isLocked(): Boolean = km.isKeyguardLocked
}
