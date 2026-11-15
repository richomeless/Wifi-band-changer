package com.wifiband

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var fSsid: EditText
    private lateinit var fPass: EditText
    private lateinit var fSec: EditText
    private lateinit var list: LinearLayout
    private var dp = 1f
    private var lastAps: List<WifiBand.Ap> = emptyList()
    private var lastCur = ""

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            status.text = if (result == PackageManager.PERMISSION_GRANTED) "Shizuku allowed ✓" else "Shizuku permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        fun field(hint: String, key: String, pwd: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(Cfg.get(this, key, if (key == "sec") "wpa2" else ""))
            if (pwd) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            col.addView(e)
            return e
        }

        fun btn(label: String, action: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { action() }
            col.addView(b)
        }

        fSsid = field("Network name (SSID)", "ssid")
        fPass = field("Password", "pass", true)
        fSec = field("Security: wpa2 or wpa3", "sec")

        btn("Allow Shizuku") {
            when {
                !Shizuku.pingBinder() -> status.text = "Start Shizuku first"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> status.text = "Shizuku already allowed ✓"
                else -> Shizuku.requestPermission(1)
            }
        }
        btn("Use current network") {
            Thread {
                val out = Shell.run("cmd wifi status")
                val s = Regex("SSID: \"([^\"]+)\"").find(out)?.groupValues?.get(1)
                runOnUiThread {
                    if (s != null) fSsid.setText(s)
                    status.text = if (s != null) "Filled from $s. Now type the password and Save." else "Not connected, or Shizuku not allowed"
                }
            }.start()
        }
        btn("Save") { save(); status.text = "Saved" }

        val head = TextView(this)
        head.text = "Choose a router"
        head.textSize = 18f
        head.setTypeface(null, Typeface.BOLD)
        head.setPadding(0, pad, 0, 0)
        col.addView(head)

        val legend = TextView(this)
        legend.text = "Blue = 5 GHz    Orange = 2.4 GHz\nTap to connect · long-press to rename"
        legend.setPadding(0, (4 * dp).toInt(), 0, 0)
        col.addView(legend)

        btn("Scan networks") { scan() }

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list)

        val quick = TextView(this)
        quick.text = "Quick (same as the widget)"
        quick.setPadding(0, pad, 0, 0)
        col.addView(quick)
        btn("Strongest 5 GHz") { quick("5") }
        btn("Strongest 2.4 GHz") { quick("24") }
        btn("Reset to Auto") { quick("auto") }

        status = TextView(this)
        status.setPadding(0, pad, 0, 0)
        col.addView(status)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)

        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun save() {
        Cfg.set(this, "ssid", fSsid.text.toString().trim())
        Cfg.set(this, "pass", fPass.text.toString())
        Cfg.set(this, "sec", fSec.text.toString().trim())
    }

    private fun quick(band: String) {
        save()
        status.text = "Working…"
        val app = applicationContext
        Thread {
            val msg = WifiBand.apply(app, band)
            runOnUiThread { status.text = msg }
            Thread.sleep(2500)
            Updater.updateAll(app)
            val cur = WifiBand.status().bssid
            runOnUiThread { showAps(lastAps, cur) }
        }.start()
    }

    private fun scan() {
        save()
        status.text = "Scanning…"
        val app = applicationContext
        Thread {
            if (!Shell.ready()) {
                runOnUiThread { status.text = "Shizuku is not running or not allowed" }
                return@Thread
            }
            val aps = WifiBand.scanAps(app)
            val cur = WifiBand.status().bssid
            runOnUiThread { showAps(aps, cur) }
        }.start()
    }

    private fun quality(rssi: Int): String = when {
        rssi >= -50 -> "excellent"
        rssi >= -60 -> "good"
        rssi >= -70 -> "fair"
        else -> "weak"
    }

    private fun showAps(aps: List<WifiBand.Ap>, cur: String) {
        lastAps = aps
        lastCur = cur
        list.removeAllViews()
        if (aps.isEmpty()) {
            status.text = "No \"" + Cfg.get(this, "ssid") + "\" networks found"
            return
        }
        status.text = "${aps.size} found"
        val keys = aps.map { it.bssid.lowercase().take(14) }.distinct().sorted()
        val sorted = aps.sortedWith(compareBy<WifiBand.Ap>({ it.bssid.lowercase().take(14) }, { it.freq }))
        for (ap in sorted) {
            val key = ap.bssid.lowercase().take(14)
            val name = Cfg.get(this, "name_$key").ifBlank { "Router ${keys.indexOf(key) + 1}" }
            val is5 = WifiBand.inBand(ap.freq, "5")
            val solid = Color.parseColor(if (is5) "#1E88E5" else "#FB8C00")
            val tint = Color.parseColor(if (is5) "#331E88E5" else "#33FB8C00")
            val isCur = ap.bssid.equals(cur, true)
            val ch = if (is5) (ap.freq - 5000) / 5 else (ap.freq - 2407) / 5

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val p = (12 * dp).toInt()
            row.setPadding(p, p, p, p)
            val bg = GradientDrawable()
            bg.setColor(tint)
            bg.cornerRadius = 16 * dp
            if (isCur) bg.setStroke((2 * dp).toInt(), solid)
            row.background = bg
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (8 * dp).toInt()
            row.layoutParams = lp

            val chip = TextView(this)
            chip.text = if (is5) "5 GHz" else "2.4 GHz"
            chip.setTextColor(Color.WHITE)
            chip.setTypeface(null, Typeface.BOLD)
            chip.gravity = Gravity.CENTER
            val cp = (10 * dp).toInt()
            chip.setPadding(cp, cp / 2, cp, cp / 2)
            val cbg = GradientDrawable()
            cbg.setColor(solid)
            cbg.cornerRadius = 12 * dp
            chip.background = cbg
            row.addView(chip)

            val info = TextView(this)
            info.text = name + (if (isCur) "  ✓ connected" else "") + "\n" +
                ap.bssid + " · " + ap.rssi + " dBm (" + quality(ap.rssi) + ") · ch " + ch
            val ilp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            ilp.marginStart = (12 * dp).toInt()
            info.layoutParams = ilp
            row.addView(info)

            row.setOnClickListener { connectTo(ap) }
            row.setOnLongClickListener {
                rename(key, name)
                true
            }
            list.addView(row)
        }
    }

    private fun connectTo(ap: WifiBand.Ap) {
        save()
        status.text = "Connecting to " + ap.bssid + "…"
        val app = applicationContext
        Thread {
            val err = if (Shell.ready()) WifiBand.connect(app, ap.bssid) else "Shizuku is not running or not allowed"
            runOnUiThread { status.text = if (err == null) "Connected ✓ " + ap.bssid else "Failed: $err" }
            Thread.sleep(2500)
            Updater.updateAll(app)
            val cur = WifiBand.status().bssid
            runOnUiThread { showAps(lastAps, cur) }
        }.start()
    }

    private fun rename(key: String, current: String) {
        val e = EditText(this)
        e.setText(current)
        AlertDialog.Builder(this)
            .setTitle("Router name")
            .setView(e)
            .setPositiveButton("Save") { _, _ ->
                Cfg.set(this, "name_$key", e.text.toString().trim())
                showAps(lastAps, lastCur)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
