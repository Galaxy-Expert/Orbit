package app.orbit.island

import android.util.Log
import de.robv.android.xposed.XposedBridge

/** Logcat: `adb logcat -s Orbit`. Ayrıca Vector/LSPosed günlüğüne de yazılır. */
object OLog {
    private const val TAG = "Orbit"

    fun i(msg: String) {
        Log.i(TAG, msg)
        try {
            XposedBridge.log("[$TAG] $msg")
        } catch (e: Throwable) {
            // Xposed ortamı yoksa (ayar ekranı süreci) sessizce geç
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        try {
            XposedBridge.log("[$TAG] $msg" + (if (t != null) ": $t" else ""))
        } catch (e: Throwable) {
        }
    }
}
