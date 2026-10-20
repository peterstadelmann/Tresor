package ch.eshopit.tresor.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * "Zugriff auf alle Dateien": nötig, um einen Ordner exakt an seinem ursprünglichen Ort
 * wiederherzustellen (inkl. Änderungsdatum der Dateien) und Originalordner zuverlässig zu löschen.
 */
object StorageAccess {
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
}
