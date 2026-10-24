package dev.otmgram.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** The app's page in the system settings, where a permission refused for good can be allowed. */
fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/** The app's notification settings; turning them on there also grants the permission. */
fun Context.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}
