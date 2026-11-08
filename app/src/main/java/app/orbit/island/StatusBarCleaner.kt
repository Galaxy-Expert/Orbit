package app.orbit.island

import android.view.View
import android.view.ViewTreeObserver
import java.util.WeakHashMap

/**
 * Status bar'daki bildirim ikonlarını ve sistem ikonlarını gizler, saati bırakır.
 * SystemUI görünürlüğü sık sık sıfırladığı için her çizimden önce yeniden uygulanır.
 *
 * Android 17'de id'ler değişmiş olabilir: logcat'te "status bar id ..." satırlarına bak.
 */
object StatusBarCleaner {
    private val HIDE_IDS = arrayOf("notification_icon_area", "system_icons", "statusIcons", "battery")
    private val seen = WeakHashMap<View, Boolean>()

    fun attach(root: View) {
        synchronized(seen) {
            if (seen.containsKey(root)) return
            seen[root] = true
        }
        val res = root.resources
        val pkg = root.context.packageName
        val targets = ArrayList<View>()
        for (name in HIDE_IDS) {
            val id = res.getIdentifier(name, "id", pkg)
            val v = if (id != 0) root.findViewById<View>(id) else null
            OLog.i("status bar id '$name': ${if (v != null) "found" else "missing"}")
            if (v != null) targets.add(v)
        }
        var hidden = false
        root.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
            val cfg = OrbitState.config
            if (cfg.enabled && cfg.hideStatusIcons) {
                for (v in targets) {
                    if (v.visibility != View.GONE) v.visibility = View.GONE
                }
                hidden = true
            } else if (hidden) {
                for (v in targets) v.visibility = View.VISIBLE
                hidden = false
            }
            true
        })
    }
}
