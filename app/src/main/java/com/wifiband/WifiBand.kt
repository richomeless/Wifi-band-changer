package com.wifiband

import android.content.Context

object WifiBand {
    class Ap(val bssid: String, val freq: Int, val rssi: Int)
    class Status(val enabled: Boolean, val ssid: String, val freq: Int, val rssi: Int, val bssid: String)

    // bssid  freq  rssi  tsf  ssid  [flags]
    private val scanRe = Regex("""^\s*([0-9a-fA-F:]{17})\s+(\d+)\s+(-?\d+)\s+\S+\s+(.*?)\s+\[""")
    // id  ssid  security
    private val netRe = Regex("""^\s*(\d+)\s+(.*?)\s{2,}\S+""")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun inBand(freq: Int, band: String): Boolean = when (band) {
        "5" -> freq in 4900..5900
        "24" -> freq in 2400..2500
        else -> false
    }

    fun status(): Status {
        val out = Shell.run("cmd wifi status")
        val enabled = !out.startsWith("error") && !Regex("Wifi is disabled", RegexOption.IGNORE_CASE).containsMatchIn(out)
        val f = Regex("Frequency: (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val r = Regex("RSSI: (-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val b = Regex("BSSID: ([0-9a-fA-F:]{17})").find(out)?.groupValues?.get(1) ?: ""
        val s = Regex("SSID: \"([^\"]*)\"").find(out)?.groupValues?.get(1) ?: ""
        return Status(enabled, s, f, r, b)
    }

    /** Fresh scan, returns every access point broadcasting the saved SSID. */
    fun scanAps(ctx: Context): List<Ap> {
        val ssid = Cfg.get(ctx, "ssid")
        Shell.run("cmd wifi start-scan")
        Thread.sleep(1500)
        val list = ArrayList<Ap>()
        for (line in Shell.run("cmd wifi list-scan-results").lines()) {
            val m = scanRe.find(line) ?: continue
            val (bssid, f, r, name) = m.destructured
            if (name.trim() != ssid) continue
            list.add(Ap(bssid, f.toInt(), r.toInt()))
        }
        return list
    }

    fun best(aps: List<Ap>, band: String): Ap? =
        aps.filter { inBand(it.freq, band) }.maxByOrNull { it.rssi }

    private fun savedIds(ssid: String): List<String> =
        Shell.run("cmd wifi list-networks").lines().mapNotNull { l ->
            val m = netRe.find(l)
            if (m != null && m.groupValues[2].trim() == ssid) m.groupValues[1] else null
        }.distinct()

    private fun connectCmd(ssid: String, sec: String, pass: String, bssid: String?): String {
        val s = if (pass.isEmpty()) "open" else sec
        val p = if (pass.isEmpty()) "" else q(pass)
        val b = if (bssid != null) " -b $bssid" else ""
        return Shell.run("cmd wifi connect-network ${q(ssid)} $s $p$b")
    }

    /** Reconnects to the saved network, pinned to [bssid] (or unpinned when null). Returns null on success, else an error text. */
    fun connect(ctx: Context, bssid: String?): String? {
        val ssid = Cfg.get(ctx, "ssid")
        val pass = Cfg.get(ctx, "pass")
        val sec = Cfg.get(ctx, "sec", "wpa2").ifBlank { "wpa2" }
        savedIds(ssid).forEach { Shell.run("cmd wifi forget-network $it") }
        val out = connectCmd(ssid, sec, pass, bssid)
        val bad = out.contains("fail", true) || out.contains("error", true) || out.contains("exception", true)
        if (bad) {
            if (bssid != null) connectCmd(ssid, sec, pass, null)
            return out.take(80)
        }
        return null
    }

    fun apply(ctx: Context, band: String): String {
        if (!Shell.ready()) return "Shizuku is not running or not allowed"
        val ssid = Cfg.get(ctx, "ssid")
        if (ssid.isBlank()) return "Open the app and set the network name"
        val label = when (band) { "5" -> "5 GHz"; "24" -> "2.4 GHz"; else -> "Auto" }

        if (band == "auto") {
            val err = connect(ctx, null)
            Cfg.set(ctx, "mode", "auto")
            RoamService.sync(ctx)
            return if (err == null) "Auto ✓" else "Failed: $err"
        }

        val best = best(scanAps(ctx), band) ?: return "No $label signal found for $ssid"
        val err = connect(ctx, best.bssid)
        Cfg.set(ctx, "mode", if (err == null) band else "auto")
        RoamService.sync(ctx)
        return if (err == null) "$label ✓ (${best.rssi} dBm) · auto-roam on" else "Failed: $err"
    }
}
