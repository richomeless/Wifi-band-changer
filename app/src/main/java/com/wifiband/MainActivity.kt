package com.wifiband

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
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

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            status.text = if (result == PackageManager.PERMISSION_GRANTED) "Shizuku allowed ✓" else "Shizuku permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
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
        btn("Test 5 GHz") { test("5") }
        btn("Test 2.4 GHz") { test("24") }
        btn("Test Auto") { test("auto") }

        status = TextView(this)
        status.setPadding(0, pad, 0, 0)
        col.addView(status)

        val hint = TextView(this)
        hint.text = "Picks the strongest router of the chosen band automatically. Then add the widget from the home screen widgets list: Wi-Fi Band."
        hint.setPadding(0, pad, 0, 0)
        col.addView(hint)

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

    private fun test(band: String) {
        save()
        status.text = "Working…"
        val app = applicationContext
        Thread {
            val msg = WifiBand.apply(app, band)
            runOnUiThread { status.text = msg }
            Thread.sleep(2500)
            Updater.updateAll(app)
        }.start()
    }
}
