package ch.eshopit.tresor.applock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import ch.eshopit.tresor.TresorApp

/**
 * Bedienungshilfe-Dienst: erkennt, welche App in den Vordergrund kommt, und legt
 * bei gesperrten Apps den Sperrbildschirm darüber. Es werden keine Bildschirminhalte
 * gelesen, nur Paket- und Activity-Name des Fensters.
 */
class AppLockService : AccessibilityService() {

    /** App, die zuletzt im Vordergrund war. */
    private var foreground: String? = null
    private val activityCache = HashMap<String, Boolean>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Bildschirm aus → alle Apps wieder sperren
                Intent.ACTION_SCREEN_OFF -> AppLockStore.relockAll()
                // Nach dem Entsperren des Handys: App im Vordergrund erneut prüfen
                Intent.ACTION_USER_PRESENT -> foreground?.let(::checkLock)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return

        if (pkg == packageName) {
            // Unser eigener Sperrbildschirm ändert nichts am Vordergrund.
            if (cls == AppLockActivity::class.java.name) return
            switchTo(pkg)
            return
        }
        if (pkg in IGNORED_PACKAGES) return
        // Nur echte App-Bildschirme zählen – keine Dialoge, Tastaturen, Toasts.
        if (!isActivity(pkg, cls)) return

        switchTo(pkg)
        checkLock(pkg)
    }

    private fun switchTo(pkg: String) {
        if (pkg != foreground) {
            foreground?.let(AppLockStore::relock)
            foreground = pkg
        }
    }

    private fun checkLock(pkg: String) {
        if (pkg == packageName || AppLockStore.isUnlocked(pkg)) return
        if (pkg !in AppLockStore.lockedApps(this)) return
        if (!(application as TresorApp).keyManager.isSetUp) return
        startActivity(AppLockActivity.intent(this, pkg))
    }

    private fun isActivity(pkg: String, cls: String): Boolean =
        activityCache.getOrPut("$pkg/$cls") {
            try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    companion object {
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }
}
