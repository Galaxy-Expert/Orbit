package app.orbit.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * SystemUI hook noktaları. Sınıf adları Android sürümüne göre değişebilir;
 * bulunamayanlar logcat'e yazılır (adb logcat -s Orbit).
 */
object SystemUiHooks {
    @Volatile
    private var controller: OrbitController? = null
    private val lock = Any()

    fun install(cl: ClassLoader) {
        try { hookAppCreate(cl) } catch (t: Throwable) { OLog.e("hookAppCreate", t) }
        try { hookStatusBarView(cl) } catch (t: Throwable) { OLog.e("hookStatusBarView", t) }
        try { hookHeadsUp(cl) } catch (t: Throwable) { OLog.e("hookHeadsUp", t) }
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

    // 2) Status bar: ikonları gizle, saati bırak
    private fun hookStatusBarView(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.PhoneStatusBarView", cl)
        if (cls == null) {
            OLog.e("PhoneStatusBarView not found (status bar cleanup disabled)")
            return
        }
        val hook = object : XC_MethodHook() {
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
        var n = 0
        for (name in arrayOf("onFinishInflate", "onAttachedToWindow")) {
            n += XposedBridge.hookAllMethods(cls, name, hook).size
        }
        OLog.i("PhoneStatusBarView hooks: $n")
    }

    // 3) Heads-up'ı sistemden al, adaya yönlendir
    private val headsUpHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val ctl = controller ?: return
                val m = param.method as? java.lang.reflect.Method ?: return
                if (m.returnType != Void.TYPE) return // sonucu güvenle iptal edemeyiz
                val entry = param.args?.firstOrNull {
                    it != null && it.javaClass.name.endsWith("NotificationEntry")
                } ?: return
                val sbn = XposedHelpers.callMethod(entry, "getSbn") as? StatusBarNotification ?: return
                if (ctl.offer(sbn)) {
                    param.result = null // sistemin heads-up'ı gösterilmesin
                }
            } catch (t: Throwable) {
                OLog.e("heads-up hook error", t)
            }
        }
    }

    private fun hookHeadsUp(cl: ClassLoader) {
        val candidates = listOf(
            "com.android.systemui.statusbar.policy.BaseHeadsUpManager",
            "com.android.systemui.statusbar.phone.HeadsUpManagerPhone",
            "com.android.systemui.statusbar.policy.HeadsUpManagerImpl",
            "com.android.systemui.statusbar.policy.HeadsUpManager"
        )
        var hooked = 0
        for (name in candidates) {
            val c = XposedHelpers.findClassIfExists(name, cl) ?: continue
            val n = XposedBridge.hookAllMethods(c, "showNotification", headsUpHook).size
            OLog.i("$name.showNotification hooks: $n")
            hooked += n
        }
        if (hooked == 0) {
            OLog.e("HeadsUpManager.showNotification not found, using fallback listener hook")
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
                    val sbn = param.args?.firstOrNull { it is StatusBarNotification } as? StatusBarNotification ?: return
                    controller?.offer(sbn)
                } catch (t: Throwable) {
                    OLog.e("listener fallback error", t)
                }
            }
        }).size
        OLog.i("NotificationListener.onNotificationPosted hooks: $n")
    }
}
