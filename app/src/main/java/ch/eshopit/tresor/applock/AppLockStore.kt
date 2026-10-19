package ch.eshopit.tresor.applock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Welche Apps gesperrt sind (dauerhaft) und welche gerade entsperrt sind (nur im Speicher).
 * Eine entsperrte App bleibt offen, bis man sie verlässt oder der Bildschirm ausgeht.
 */
object AppLockStore {
    private const val PREFS = "tresor_applock"
    private const val K_LOCKED = "locked_packages"

    private val unlocked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun lockedApps(context: Context): Set<String> =
        prefs(context).getStringSet(K_LOCKED, null)?.toSet() ?: emptySet()

    fun setLocked(context: Context, pkg: String, locked: Boolean) {
        val set = lockedApps(context).toMutableSet()
        if (locked) {
            set.add(pkg)
        } else {
            set.remove(pkg)
            unlocked.remove(pkg)
        }
        prefs(context).edit().putStringSet(K_LOCKED, set).apply()
    }

    fun isUnlocked(pkg: String) = pkg in unlocked
    fun markUnlocked(pkg: String) { unlocked.add(pkg) }
    fun relock(pkg: String) { unlocked.remove(pkg) }
    fun relockAll() = unlocked.clear()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Ist der Bedienungshilfe-Dienst "Tresor App-Sperre" in den Android-Einstellungen aktiv? */
fun isAppLockServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AppLockService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

data class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap)

/** Alle Apps mit Symbol im Launcher (ausser Tresor selbst), alphabetisch. */
fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ri ->
            InstalledApp(
                pkg = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
                icon = ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}
