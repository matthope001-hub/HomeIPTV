package com.matt.tvdash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the goal watcher running while you're in other apps, so the edge glow can show over them */
class WatchService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("watch", "Goal alerts", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "watch")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Watching for goals")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        Config.load(Prefs(this))
        GoalWatch.start(this)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_STICKY

    override fun onDestroy() { GoalWatch.stop(); super.onDestroy() }
}
