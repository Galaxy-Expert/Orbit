package app.orbit.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import dalvik.system.DexFile
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier

/**
 * SystemUI hook noktaları.
 *
 * Sınıf adları Android sürümüne/ROM'a göre değişir (ör. Android 16'da heads-up sınıfları başka pakete taşındı).
 * Bu yüzden bilinen adayların yanında SystemUI'nin dex'i taranır ve "HeadsUpManager" / "*StatusBarView"
 * adlı sınıflar otomatik bulunup hook'lanır. Her şey logcat'e / Vector Logs'a yazılır.
 */
object SystemUiHooks {
    @Volatile
    private var controller: OrbitController? = null
    private val lock = Any()
    private val hookedKeys = HashSet<String>()

    @Volatile
    private var headsUpHooks = 0

    private val KNOWN_STATUS_BAR_VIEWS = listOf(
        "com.android.systemui.statusbar.phone.PhoneStatusBarView",
        "com.android.systemui.statusbar.phone.KeyguardStatusBarView",
        "com.android.systemui.keyguard.ui.view.KeyguardStatusBarView"
    )

    private val KNOWN_HEADS_UP = listOf(
        "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
        "com.android.systemui.statusbar.notification.headsup.HeadsUpManager",
        "com.android.systemui.statusbar.policy.HeadsUpManagerImpl",
        "com.android.systemui.statusbar.policy.BaseHeadsUpManager",
        "com.android.systemui.statusbar.phone.HeadsUpManagerPhone",
        "com.android.systemui.statusbar.policy.HeadsUpManager"
    )

    fun install(cl: ClassLoader) {
        try { hookAppCreate(cl) } catch (t: Throwable) { OLog.e("hookAppCreate", t) }
        for (n in KNOWN_STATUS_BAR_VIEWS) {
            try {
                val c = XposedHelpers.findClassIfExists(n, cl)
                if (c != null) hookStatusBarClass(c)
            } catch (t: Throwable) { OLog.e("known status bar view $n", t) }
        }
        for (n in KNOWN_HEADS_UP) {
            try {
                val c = XposedHelpers.findClassIfExists(n, cl)
                if (c != null) hookHeadsUpClass(c)
            } catch (t: Throwable) { OLog.e("known heads-up $n", t) }
        }
        // Arka planda tarama: bilinmeyen sürüm/ROM adlarını yakalar
        Thread(Runnable {
            try {
                discover(cl)
            } catch (t: Throwable) {
                OLog.e("discover failed", t)
            }
        }, "Orbit-discover").start()
    }

    private fun ensureController(ctx: Context) {
        if (controller != null) return
        synchronized(lock) {
            if (controller != null) return
            try {
                val c = OrbitController(ctx)
                controller = c
                c.start()
            } catch (t: Throwable) {
                OLog.e("controller could not be created", t)
            }
        }
    }

    // 1) Süreç başlangıcı: status bar view'u hook'lanamazsa yedek başlatıcı
    private fun hookAppCreate(cl: ClassLoader) {
        val app = XposedHelpers.findClassIfExists("com.android.systemui.SystemUIApplication", cl)
        if (app == null) {
            OLog.e("SystemUIApplication not found")
            return
        }
        XposedHelpers.findAndHookMethod(app, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ctx = param.thisObject as? Context ?: return
                Handler(Looper.getMainLooper()).postDelayed({ ensureController(ctx) }, 3000)
            }
        })
        OLog.i("hooked SystemUIApplication.onCreate")
    }

    // 2) Status bar ve kilit ekranı status bar'ı: ikonları gizle, saati bırak
    private val statusBarHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val v = param.thisObject as? View ?: return
                StatusBarCleaner.attach(v)
                ensureController(v.context)
            } catch (t: Throwable) {
                OLog.e("status bar hook error", t)
            }
        }
    }

    private fun hookStatusBarClass(c: Class<*>) {
        synchronized(hookedKeys) {
            if (!hookedKeys.add("sb:" + c.name)) return
        }
        var n = 0
        for (m in c.declaredMethods) {
            if ((m.name == "onFinishInflate" || m.name == "onAttachedToWindow") && !Modifier.isAbstract(m.modifiers)) {
                try {
                    XposedBridge.hookMethod(m, statusBarHook)
                    n++
                } catch (t: Throwable) {
                    OLog.e("hook ${c.name}.${m.name} failed", t)
                }
            }
        }
        OLog.i("status bar view ${c.name}: $n hooks")
    }

    // 3) Heads-up'ı sistemden al, adaya yönlendir
    private fun extractSbn(args: Array<Any?>?): StatusBarNotification? {
        if (args == null) return null
        for (a in args) {
            if (a == null) continue
            if (a is StatusBarNotification) return a
            try {
                val s = XposedHelpers.callMethod(a, "getSbn")
                if (s is StatusBarNotification) return s
            } catch (t: Throwable) {
                // bu argüman NotificationEntry değil
            }
        }
        return null
    }

    private val headsUpHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val ctl = controller ?: return
                val m = param.method as? java.lang.reflect.Method ?: return
                if (m.returnType != Void.TYPE) return // sonucu güvenle iptal edemeyiz
                val sbn = extractSbn(param.args) ?: return
                if (ctl.offer(sbn)) {
                    param.result = null // sistemin heads-up'ı gösterilmesin
                }
            } catch (t: Throwable) {
                OLog.e("heads-up hook error", t)
            }
        }
    }

    private fun hookHeadsUpClass(c: Class<*>) {
        synchronized(hookedKeys) {
            if (!hookedKeys.add("hu:" + c.name)) return
        }
        for (m in c.declaredMethods) {
            if (m.name == "showNotification" && !Modifier.isAbstract(m.modifiers)) {
                try {
                    XposedBridge.hookMethod(m, headsUpHook)
                    headsUpHooks++
                    OLog.i("hooked ${c.name}.showNotification(${m.parameterTypes.joinToString { it.simpleName }})")
                } catch (t: Throwable) {
                    OLog.e("hook ${c.name}.showNotification failed", t)
                }
            }
        }
    }

    // ---- dex taraması ----

    private fun scanClassNames(cl: ClassLoader, filter: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        try {
            val pathList = XposedHelpers.getObjectField(cl, "pathList")
            val elements = XposedHelpers.getObjectField(pathList, "dexElements") as? Array<*> ?: return out
            for (el in elements) {
                if (el == null) continue
                try {
                    val dex = XposedHelpers.getObjectField(el, "dexFile") as? DexFile ?: continue
                    val e = dex.entries()
                    while (e.hasMoreElements()) {
                        val name = e.nextElement()
                        if (filter(name)) out.add(name)
                    }
                } catch (t: Throwable) {
                    OLog.e("dex element scan failed: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            OLog.e("dex scan failed", t)
        }
        return out
    }

    private fun discover(cl: ClassLoader) {
        val names = scanClassNames(cl) { n ->
            n.startsWith("com.android.systemui.") && !n.contains('$') &&
                (n.contains("HeadsUpManager") || n.endsWith("StatusBarView"))
        }
        OLog.i("scan found ${names.size} candidate classes: " + names.take(30).joinToString(", "))
        for (n in names) {
            val c = try {
                Class.forName(n, false, cl)
            } catch (t: Throwable) {
                continue
            }
            try {
                if (n.endsWith("StatusBarView")) hookStatusBarClass(c)
                if (n.contains("HeadsUpManager")) hookHeadsUpClass(c)
            } catch (t: Throwable) {
                OLog.e("hooking $n failed", t)
            }
        }
        OLog.i("heads-up hooks installed: $headsUpHooks")
        if (headsUpHooks == 0) {
            OLog.e("no HeadsUpManager.showNotification found, using fallback listener hook")
            hookListenerFallback(cl)
        }
    }

    // Yedek: sistem heads-up'ı da görünür (çift gösterim olabilir) ama ada en azından çalışır
    private fun hookListenerFallback(cl: ClassLoader) {
        val c = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.NotificationListener", cl)
        if (c == null) {
            OLog.e("NotificationListener not found either, notifications will not reach the island")
            return
        }
        val n = XposedBridge.hookAllMethods(c, "onNotificationPosted", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val sbn = extractSbn(param.args) ?: return
                    controller?.offer(sbn)
                } catch (t: Throwable) {
                    OLog.e("listener fallback error", t)
                }
            }
        }).size
        OLog.i("NotificationListener.onNotificationPosted hooks: $n")
    }
}
