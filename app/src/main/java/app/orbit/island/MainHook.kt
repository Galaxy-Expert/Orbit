package app.orbit.island

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        val proc = lpparam.processName
        if (proc != null && proc != "com.android.systemui") return
        OLog.i("System UI loaded, installing hooks")
        SystemUiHooks.install(lpparam.classLoader)
    }
}
