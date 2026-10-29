package com.matt.tvdash.remote

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.InputStream
import java.net.URL
import kotlin.concurrent.thread

/**
 * Updates TV Dash Remote from the build GitHub saves in the repo (apk/remote.apk, number in apk/version.txt),
 * so updating is one tap instead of opening the GitHub link.
 */
// Network read with timeouts
private fun openStream(url: String): InputStream = URL(url).openConnection().run {
    connectTimeout = 10_000; readTimeout = 30_000; getInputStream()
}

private fun httpGet(url: String): String = openStream(url).bufferedReader().use { it.readText() }

object Updater {
    private const val BASE = "https://raw.githubusercontent.com/matthope001-hub/HomeIPTV/main/apk/"

    // GitHub caches files for a few minutes; a changing query string always gets the newest one
    private fun fresh(file: String) = "$BASE$file?t=${System.currentTimeMillis()}"

    /** Build number of this copy (v1.0.N), or 0 for a build made on a computer */
    fun current(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.substringAfterLast('.')?.toIntOrNull() ?: 0
    } catch (_: Exception) { 0 }

    /** Newest build number on GitHub, or null if it can't be checked */
    fun latest(): Int? = try { httpGet(fresh("version.txt")).trim().toInt() } catch (_: Exception) { null }

    fun install(act: Activity) {
        // The TV must let TV Dash install apps; send the user to that setting the first time
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Turn on TV Dash Remote here, then tap Update again", Toast.LENGTH_LONG).show()
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            } catch (_: Exception) {
                Toast.makeText(act, "Allow TV Dash Remote to install apps in the phone settings", Toast.LENGTH_LONG).show()
            }
            return
        }
        Toast.makeText(act, "Downloading update…", Toast.LENGTH_SHORT).show()
        val app = act.applicationContext
        thread {
            try {
                val pi = app.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    openStream(fresh("remote.apk")).use { input ->
                        s.openWrite("tvdash", 0, -1).use { out -> input.copyTo(out); s.fsync(out) }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                    s.commit(done.intentSender)
                }
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}

/** Gets the installer's answer: shows the TV's "Install?" screen, or the error */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> Toast.makeText(c, "Update failed: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG).show()
        }
    }
}
