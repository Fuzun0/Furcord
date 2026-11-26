package com.furcord.battery

import kotlinx.serialization.json.*
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class DeviceKind { HEADSET, MOUSE, KEYBOARD, OTHER }

data class BatteryDevice(val id: String, val name: String, val percent: Int, val hint: DeviceKind = DeviceKind.OTHER) {
    val kind: DeviceKind get() {
        val n = name.lowercase()
        return when {
            listOf("headphone", "headset", "buds", "airpods", "kulak", "arctis", "wh-", "wf-", "kraken", "barracuda", "blackshark").any(n::contains) -> DeviceKind.HEADSET
            listOf("mouse", "fare", "mx master", "g502", "g305", "pebble", "basilisk", "deathadder", "viper", "naga", "orochi", "cobra").any(n::contains) -> DeviceKind.MOUSE
            listOf("keyboard", "klavye", "keys", "mx keys", "k380", "blackwidow", "huntsman", "ornata", "deathstalker").any(n::contains) -> DeviceKind.KEYBOARD
            else -> hint
        }
    }
}

/** Windows'un bildirdiği kablosuz (Bluetooth) cihaz pil yüzdelerini okur. */
object BatteryReader {

    // § işareti PowerShell'deki $ yerine kullanılıyor (Kotlin string şablonuyla çakışmasın diye).
    private val SCRIPT = """
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        §key = '{104EA319-6EE2-4701-BD47-8DDBF425BBE5} 2'
        §out = @()
        foreach (§d in (Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object InstanceId -like 'BTH*')) {
            §p = Get-PnpDeviceProperty -InstanceId §d.InstanceId -KeyName §key -ErrorAction SilentlyContinue
            if (§p -and §null -ne §p.Data) {
                §v = [int]§p.Data
                if (§v -ge 0 -and §v -le 100) {
                    §out += [pscustomobject]@{ id = §d.InstanceId; name = §d.FriendlyName; percent = §v }
                }
            }
        }
        ConvertTo-Json -InputObject @(§out) -Compress
    """.trimIndent().replace('§', '$')

    private val lastSeen = HashMap<String, Pair<BatteryDevice, Long>>()
    private const val STALE_MS = 15 * 60_000L

    /**
     * [read] anlık/aralıklı sonuç verebilir (PowerShell zaman aşımı, uyuyan cihaz vb.).
     * Bu yüzden cihazlar son görüldükleri değerle [STALE_MS] boyunca tutulur → gösterge titremez/kaybolmaz.
     */
    fun readStable(): List<BatteryDevice> {
        val now = System.currentTimeMillis()
        val fresh = runCatching { read() }.getOrDefault(emptyList())
        synchronized(lastSeen) {
            fresh.forEach { lastSeen[it.name] = it to now }
            lastSeen.entries.removeIf { now - it.value.second > STALE_MS }
            return lastSeen.values.map { it.first }
        }
    }

    /** Bluetooth (Windows'un bildirdiği) + Razer 2.4 GHz/kablolu cihazlar. */
    fun read(): List<BatteryDevice> =
        (readBluetooth() + RazerHid.read() + GenericHid.read() + XInputBattery.read()).distinctBy { it.name }

    private fun readBluetooth(): List<BatteryDevice> = try {
        val encoded = Base64.getEncoder()
            .encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
        val proc = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
        ).redirectErrorStream(false).start()

        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor(20, TimeUnit.SECONDS)

        val root = Json.parseToJsonElement(text.trim())
        val items = if (root is JsonArray) root else JsonArray(listOf(root))
        items.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            BatteryDevice(
                id      = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name    = o["name"]?.jsonPrimitive?.contentOrNull ?: "Bilinmeyen cihaz",
                percent = o["percent"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
            )
        }
        // Aynı cihazın klasik + LE düğümleri iki kez gelebilir: isme göre tekilleştir.
        .distinctBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }
}
