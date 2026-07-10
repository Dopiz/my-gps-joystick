package com.dopiz.gpsjoystick

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Single source of truth for the three permission/state gates the app needs:
 *  1. Fine location runtime permission.
 *  2. Whether THIS app is selected as the system "mock location app"
 *     (Developer options -> Select mock location app).
 *  3. Overlay / SYSTEM_ALERT_WINDOW permission (for the floating joystick).
 *
 * Also exposes the jump [Intent]s to the relevant system settings pages so callers
 * never hand-roll these. Every other slice reuses this rather than re-checking.
 */
object PermissionChecker {

    data class Status(
        val locationGranted: Boolean,
        val isMockAppSelected: Boolean,
        val overlayGranted: Boolean,
        val notificationsGranted: Boolean,
    ) {
        /** True only when everything required to inject mock locations is in place. */
        val mockReady: Boolean get() = locationGranted && isMockAppSelected
    }

    fun status(context: Context): Status = Status(
        locationGranted = isLocationGranted(context),
        isMockAppSelected = isMockAppSelected(context),
        overlayGranted = isOverlayGranted(context),
        notificationsGranted = isNotificationsGranted(context),
    )

    fun isLocationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * There is no public API to directly ask "am I the selected mock app". The reliable
     * signal is the app-op [AppOpsManager.OPSTR_MOCK_LOCATION], which the system flips to
     * [AppOpsManager.MODE_ALLOWED] for exactly the app chosen in Developer options.
     */
    fun isMockAppSelected(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isOverlayGranted(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isNotificationsGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    // --- Jump intents to the relevant system settings pages -----------------

    /**
     * There is no deep link to the "Select mock location app" picker, so we open the
     * Developer options page (closest reachable target). Callers should tell the user to
     * pick this app under "Select mock location app".
     */
    fun developerOptionsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** App details page — used to grant location when the runtime prompt is unavailable. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
