package app.orbit.island

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * Status bar (ve kilit ekranı status bar'ı) üzerindeki bildirim/sistem ikonlarını gizler, saati bırakır.
 * SystemUI görünürlüğü sık sık sıfırladığı için her çizimden önce yeniden uygulanır.
 * Orbit'e dokununca [reveal] ile bir süre geri gösterilir.
 *
 * Android sürümüne göre id'ler değişebilir: logcat'te "[...View] id ..." satırlarına bak.
 */
object StatusBarCleaner {
    private val HIDE_IDS = arrayOf(
        "notification_icon_area", "system_icons", "statusIcons", "battery",
        "status_icon_area", "system_icons_container"
    )

    private class Entry(val views: List<View>) {
        var hiddenByUs = false
    }

    private val entries = ArrayList<Entry>()
    private val seen = WeakHashMap<View, Boolean>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var revealUntil = 0L
    private val rehide = Runnable { applyAll() }

    fun attach(root: View) {
        synchronized(seen) {
            if (seen.containsKey(root)) return
            seen[root] = true
        }
        val res = root.resources
        val pkg = root.context.packageName
        val tag = root.javaClass.simpleName
        val targets = ArrayList<View>()
        for (name in HIDE_IDS) {
            val id = res.getIdentifier(name, "id", pkg)
            val v = if (id != 0) root.findViewById<View>(id) else null
            OLog.i("[$tag] id '$name': ${if (v != null) "found" else "missing"}")
            if (v != null && !targets.contains(v)) targets.add(v)
        }
        val entry = Entry(targets)
        synchronized(entries) { entries.add(entry) }
        root.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
            enforce(entry)
            true
        })
    }

    private fun shouldHide(): Boolean {
        val c = OrbitState.config
        return c.enabled && c.hideStatusIcons && SystemClock.uptimeMillis() >= revealUntil
    }

    private fun enforce(e: Entry) {
        if (shouldHide()) {
            for (v in e.views) {
                if (v.visibility != View.GONE) v.visibility = View.GONE
            }
            e.hiddenByUs = true
        } else if (e.hiddenByUs) {
            for (v in e.views) v.visibility = View.VISIBLE
            e.hiddenByUs = false
        }
    }

    fun applyAll() {
        main.post {
            synchronized(entries) {
                for (e in entries) enforce(e)
            }
        }
    }

    /** Gizlenen ikonları [ms] milisaniye geri göster. */
    fun reveal(ms: Long) {
        revealUntil = SystemClock.uptimeMillis() + ms
        main.removeCallbacks(rehide)
        main.postDelayed(rehide, ms + 60L)
        applyAll()
    }

    fun endReveal() {
        revealUntil = 0L
        main.removeCallbacks(rehide)
        applyAll()
    }
}
