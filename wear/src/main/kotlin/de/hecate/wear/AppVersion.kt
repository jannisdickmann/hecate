package de.hecate.wear

import android.content.Context

/** What is installed, read from the package rather than a generated constant:
 *  CI sets the version per build (A.34), and this module builds no
 *  BuildConfig. */
object AppVersion {
    fun name(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    fun code(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
