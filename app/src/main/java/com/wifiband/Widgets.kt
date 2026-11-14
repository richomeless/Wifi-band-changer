package com.wifiband

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class BaseProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Updater.updateAll(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class LargeGlassProvider : BaseProvider()
class LargeSolidProvider : BaseProvider()
class SmallGlassProvider : BaseProvider()
class SmallSolidProvider : BaseProvider()

enum class Variant(val cls: Class<*>, val layout: Int, val glass: Boolean, val text: Boolean) {
    LARGE_GLASS(LargeGlassProvider::class.java, R.layout.widget_large, true, true),
    LARGE_SOLID(LargeSolidProvider::class.java, R.layout.widget_large, false, true),
    SMALL_GLASS(SmallGlassProvider::class.java, R.layout.widget_small, true, false),
    SMALL_SOLID(SmallSolidProvider::class.java, R.layout.widget_small, false, false)
}

class BandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val band = intent.getStringExtra("band") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (band == "refresh") {
                    Updater.updateAll(app)
                    Updater.toast(app, "Refreshed")
                } else {
                    val msg = WifiBand.apply(app, band)
                    Updater.toast(app, msg)
                    Thread.sleep(2000)
                    Updater.updateAll(app)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object Updater {
    fun toast(c: Context, m: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_SHORT).show() }
    }

    fun updateAll(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        for (v in Variant.values()) {
            val ids = mgr.getAppWidgetIds(ComponentName(c, v.cls))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(c, v))
        }
    }

    class Info(val freq: Int, val rssi: Int, val bssid: String)

    @Suppress("DEPRECATION")
    private fun info(c: Context): Info {
        if (Shell.ready()) {
            val out = Shell.run("cmd wifi status")
            val f = Regex("Frequency: (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
            if (f != null) {
                val r = Regex("RSSI: (-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val b = Regex("BSSID: ([0-9a-fA-F:]{17})").find(out)?.groupValues?.get(1) ?: ""
                return Info(f, r, b)
            }
        }
        return try {
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val i = wm.connectionInfo
            Info(i.frequency, i.rssi, "")
        } catch (e: Throwable) {
            Info(-1, 0, "")
        }
    }

    private fun build(c: Context, v: Variant): RemoteViews {
        val rv = RemoteViews(c.packageName, v.layout)
        val inf = info(c)
        val f = inf.freq
        val is5 = f in 4900..5900
        val is24 = f in 2400..2500
        val tail = if (inf.bssid.length >= 5) " · " + inf.bssid.takeLast(5) else ""
        val time = " · " + SimpleDateFormat("HH:mm", Locale.US).format(Date())

        rv.setInt(R.id.root, "setBackgroundResource", if (v.glass) R.drawable.bg_glass else R.drawable.bg_solid)

        if (v.text) {
            val ssid = Cfg.get(c, "ssid").ifBlank { "Wi-Fi band" }
            val roamTag = if (Cfg.get(c, "mode", "auto") != "auto") " · roaming" else ""
            rv.setTextViewText(R.id.title, ssid + roamTag)
            rv.setTextColor(R.id.title, c.getColor(if (v.glass) R.color.glass_text else R.color.solid_text))
            val sub = when {
                is5 -> "5 GHz · ${inf.rssi} dBm$tail$time"
                is24 -> "2.4 GHz · ${inf.rssi} dBm$tail$time"
                else -> "Not connected$time"
            }
            rv.setTextViewText(R.id.subtitle, sub)
            rv.setTextColor(R.id.subtitle, c.getColor(if (v.glass) R.color.glass_dim else R.color.solid_dim))
        }

        button(c, rv, R.id.btn5, "5", v.glass, is5)
        button(c, rv, R.id.btn24, "24", v.glass, is24)
        button(c, rv, R.id.btnauto, "auto", v.glass, false)
        button(c, rv, R.id.btnrefresh, "refresh", v.glass, false)
        return rv
    }

    private fun button(c: Context, rv: RemoteViews, id: Int, band: String, glass: Boolean, active: Boolean) {
        val bg = when {
            glass && active -> R.drawable.btn_glass_active
            glass -> R.drawable.btn_glass
            active -> R.drawable.btn_solid_active
            else -> R.drawable.btn_solid
        }
        val tc = when {
            glass && active -> R.color.glass_active_text
            glass -> R.color.glass_text
            active -> R.color.solid_active_text
            else -> R.color.solid_text
        }
        rv.setInt(id, "setBackgroundResource", bg)
        rv.setTextColor(id, c.getColor(tc))
        val i = Intent(c, BandReceiver::class.java).setAction("com.wifiband.SET").putExtra("band", band)
        val pi = PendingIntent.getBroadcast(
            c, band.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(id, pi)
    }
}
