package com.wifiband

import android.content.Context

object WifiBand {
    // bssid  freq  rssi  tsf  ssid  [flags]
    private val scanRe = Regex("""^\s*([0-9a-fA-F:]{17})\s+(\d+)\s+(-?\d+)\s+\S+\s+(.*?)\s+\[""")
    // id  ssid  security
    private val netRe = Regex("""^\s*(\d+)\s+(.*?)\s{2,}\S+""")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Strongest access point of the given band among ALL routers broadcasting this SSID. */
    fun findBssid(ctx: Context, band: String): Pair<String, Int>? {
        val ssid = Cfg.get(ctx, "ssid")
        val range = if (band == "5") 4900..5900 else 2400..2500
        Shell.run("cmd wifi start-scan")
        Thread.sleep(1500)
        var best: String? = null
        var bestRssi = -999
        for (line in Shell.run("cmd wifi list-scan-results").lines()) {
            val m = scanRe.find(line) ?: continue
            val (bssid, f, r, name) = m.destructured
            if (name.trim() != ssid) continue
            if (f.toInt() !in range) continue
            val rssi = r.toInt()
            if (rssi > bestRssi) {
                bestRssi = rssi
                best = bssid
            }
        }
        return if (best != null) Pair(best, bestRssi) else null
    }

    private fun savedIds(ssid: String): List<String> =
        Shell.run("cmd wifi list-networks").lines().mapNotNull { l ->
            val m = netRe.find(l)
            if (m != null && m.groupValues[2].trim() == ssid) m.groupValues[1] else null
        }.distinct()

    private fun connect(ssid: String, sec: String, pass: String, bssid: String?): String {
        val s = if (pass.isEmpty()) "open" else sec
        val p = if (pass.isEmpty()) "" else q(pass)
        val b = if (bssid != null) " -b $bssid" else ""
        return Shell.run("cmd wifi connect-network ${q(ssid)} $s $p$b")
    }

    fun apply(ctx: Context, band: String): String {
        if (!Shell.ready()) return "Shizuku is not running or not allowed"
        val ssid = Cfg.get(ctx, "ssid")
        if (ssid.isBlank()) return "Open the app and set the network name"
        val pass = Cfg.get(ctx, "pass")
        val sec = Cfg.get(ctx, "sec", "wpa2").ifBlank { "wpa2" }
        val label = when (band) { "5" -> "5 GHz"; "24" -> "2.4 GHz"; else -> "Auto" }

        var bssid: String? = null
        var rssi = 0
        if (band != "auto") {
            val found = findBssid(ctx, band) ?: return "No $label signal found for $ssid"
            bssid = found.first
            rssi = found.second
        }
        savedIds(ssid).forEach { Shell.run("cmd wifi forget-network $it") }
        val out = connect(ssid, sec, pass, bssid)
        val bad = out.contains("fail", true) || out.contains("error", true) || out.contains("exception", true)
        if (bad) {
            if (bssid != null) connect(ssid, sec, pass, null)
            return "Failed: " + out.take(80)
        }
        return if (bssid != null) "$label ✓ ($rssi dBm)" else "$label ✓"
    }
}
