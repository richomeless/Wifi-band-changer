package com.wifiband

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * Keeps the phone on the strongest access point of the chosen band while you move around the house.
 * Only acts while connected to the saved network (or disconnected from it); never touches other networks.
 */
class RoamService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    companion object {
        fun sync(c: Context) {
            val app = c.applicationContext
            try {
                val i = Intent(app, RoamService::class.java)
                if (Cfg.get(app, "mode", "auto") == "auto") app.stopService(i) else app.startForegroundService(i)
            } catch (e: Throwable) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            Cfg.set(this, "mode", "auto")
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notif("Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (worker?.isAlive != true) {
            running = true
            val t = Thread { loop() }
            worker = t
            t.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun notif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("roam", "Wi-Fi roaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RoamService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "roam")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Wi-Fi Band · auto-roam")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Stop", stop).build())
            .build()
    }

    private fun loop() {
        var lastRoam = 0L
        while (running) {
            var text = ""
            try {
                val mode = Cfg.get(this, "mode", "auto")
                if (mode == "auto") {
                    stopSelf()
                    return
                }
                val ssid = Cfg.get(this, "ssid")
                val weak = Cfg.get(this, "weak", "-65").toIntOrNull() ?: -65
                val label = if (mode == "5") "5 GHz" else "2.4 GHz"

                if (!Shell.ready()) {
                    text = "Shizuku is not running"
                } else {
                    val st = WifiBand.status()
                    if (!st.enabled) {
                        text = "Wi-Fi is off"
                    } else if (st.freq > 0 && st.ssid.isNotEmpty() && st.ssid != ssid) {
                        text = "Connected to another network"
                    } else {
                        val inTarget = WifiBand.inBand(st.freq, mode)
                        val now = SystemClock.elapsedRealtime()
                        val need = st.freq <= 0 || !inTarget || st.rssi < weak
                        if (need && now - lastRoam > 20000L) {
                            val best = WifiBand.best(WifiBand.scanAps(this), mode)
                            if (best != null) {
                                val better = st.freq <= 0 || !inTarget ||
                                    (!best.bssid.equals(st.bssid, true) && best.rssi >= st.rssi + 8)
                                if (better) {
                                    WifiBand.connect(this, best.bssid)
                                    lastRoam = now
                                    Updater.updateAll(this)
                                    text = "$label · moved to ${best.bssid.takeLast(5)} (${best.rssi} dBm)"
                                }
                            }
                        }
                        if (text.isEmpty()) text = "$label · ${st.rssi} dBm · ${st.bssid.takeLast(5)}"
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                text = "Error: " + e.message
            }
            getSystemService(NotificationManager::class.java).notify(1, notif(text))
            val pm = getSystemService(PowerManager::class.java)
            try {
                Thread.sleep(if (pm.isInteractive) 8000L else 40000L)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
}
