package com.furcord.battery

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ShortByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Marka bağımsız okuma: HID standardındaki pil kullanımlarını (Battery Strength 0x06/0x20,
 * Absolute State of Charge 0x85/0x65) bildiren herhangi bir cihaz (fare, klavye, kulaklık...).
 * Üretici özel protokolü gerekmez.
 */
object GenericHid {
    private const val HIDP_STATUS_SUCCESS = 0x110000
    private const val VALUE_CAPS_SIZE = 72

    fun read(): List<BatteryDevice> = try {
        val found = LinkedHashMap<String, BatteryDevice>()
        for (path in RazerHid.allHidPaths()) {
            val dev = try { probe(path) } catch (_: Throwable) { null } ?: continue
            found.putIfAbsent(dev.name, dev)
        }
        found.values.toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun probe(path: String): BatteryDevice? {
        val hid = HidLib.I
        val handle = Kernel32.INSTANCE.CreateFile(
            path, 0, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE, null, WinNT.OPEN_EXISTING, 0, null,
        )
        if (handle == WinBase.INVALID_HANDLE_VALUE) return null
        val pre = PointerByReference()
        var havePre = false
        try {
            val h = handle.pointer
            if (!hid.HidD_GetPreparsedData(h, pre)) return null
            havePre = true
            val p = pre.value

            val caps = Memory(64).also { it.clear() }
            if (hid.HidP_GetCaps(p, caps) != HIDP_STATUS_SUCCESS) return null
            val topUsage = caps.getShort(0).toInt() and 0xFFFF
            val topPage = caps.getShort(2).toInt() and 0xFFFF
            // UPS / güç kaynağı / dokunmatik panel gibi cihazları atla
            if (topPage == 0x84 || topPage == 0x85 || topPage == 0x0D) return null

            val inLen = caps.getShort(4).toInt() and 0xFFFF
            val featLen = caps.getShort(8).toInt() and 0xFFFF
            // (rapor tipi, caps içindeki değer sayısı ofseti, rapor uzunluğu)
            val kinds = listOf(Triple(0, 48, inLen), Triple(2, 60, featLen))

            for ((type, countOffset, reportLen) in kinds) {
                val n = caps.getShort(countOffset.toLong()).toInt() and 0xFFFF
                if (n == 0 || reportLen == 0) continue
                val vc = Memory((VALUE_CAPS_SIZE * n).toLong()).also { it.clear() }
                val len = ShortByReference(n.toShort())
                if (hid.HidP_GetValueCaps(type, vc, len, p) != HIDP_STATUS_SUCCESS) continue

                for (i in 0 until (len.value.toInt() and 0xFFFF)) {
                    val b = (VALUE_CAPS_SIZE * i).toLong()
                    val page = vc.getShort(b).toInt() and 0xFFFF
                    val reportId = vc.getByte(b + 2).toInt() and 0xFF
                    val link = vc.getShort(b + 6).toInt() and 0xFFFF
                    val isRange = vc.getByte(b + 12).toInt() != 0
                    val usage = vc.getShort(b + 56).toInt() and 0xFFFF
                    val usageMax = if (isRange) vc.getShort(b + 58).toInt() and 0xFFFF else usage
                    val isBattery =
                        (page == 0x06 && usage <= 0x20 && 0x20 <= usageMax) ||
                        (page == 0x85 && usage <= 0x65 && 0x65 <= usageMax)
                    if (!isBattery) continue
                    val usageId = if (page == 0x06) 0x20 else 0x65
                    val lmin = vc.getInt(b + 40)
                    val lmax = vc.getInt(b + 44)
                    if (lmax <= lmin) continue

                    val buf = Memory(reportLen.toLong()).also { it.clear(); it.setByte(0, reportId.toByte()) }
                    val ok = if (type == 0) hid.HidD_GetInputReport(h, buf, reportLen)
                             else hid.HidD_GetFeature(h, buf, reportLen)
                    if (!ok) continue
                    val value = IntByReference()
                    if (hid.HidP_GetUsageValue(type, page, link, usageId, value, p, buf, reportLen) != HIDP_STATUS_SUCCESS) continue
                    val v = value.value
                    if (v < lmin || v > lmax) continue
                    val percent = ((v - lmin).toLong() * 100 / (lmax - lmin)).toInt()

                    val name = productName(h) ?: continue
                    val hint = when {
                        topPage == 0x01 && topUsage == 0x02 -> DeviceKind.MOUSE
                        topPage == 0x01 && topUsage == 0x06 -> DeviceKind.KEYBOARD
                        else -> DeviceKind.OTHER
                    }
                    return BatteryDevice("HID:$path", name, percent.coerceIn(0, 100), hint)
                }
            }
            return null
        } finally {
            if (havePre) hid.HidD_FreePreparsedData(pre.value)
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private fun productName(h: Pointer): String? {
        val buf = Memory(256).also { it.clear() }
        return if (HidLib.I.HidD_GetProductString(h, buf, 256)) buf.getWideString(0).trim().ifEmpty { null } else null
    }
}

private interface XInputLib : StdCallLibrary {
    fun XInputGetBatteryInformation(user: Int, devType: Int, info: Pointer): Int
    companion object {
        val I: XInputLib? = try { Native.load("xinput1_4", XInputLib::class.java) } catch (_: Throwable) { null }
    }
}

/** Xbox / XInput uyumlu kablosuz kollar ve kulaklıklar (seviye 4 kademeli: boş/düşük/orta/dolu). */
object XInputBattery {
    fun read(): List<BatteryDevice> {
        val lib = XInputLib.I ?: return emptyList()
        val out = ArrayList<BatteryDevice>()
        val info = Memory(2)
        for (user in 0..3) {
            for ((devType, label) in listOf(0 to "Gamepad", 1 to "Headset")) {
                info.clear()
                if (lib.XInputGetBatteryInformation(user, devType, info) != 0) continue
                val type = info.getByte(0).toInt() and 0xFF
                val level = info.getByte(1).toInt() and 0xFF
                if (type != 2 && type != 3) continue          // yalnızca pilli (alkalin / NiMH) cihazlar
                val percent = when (level) { 0 -> 5; 1 -> 33; 2 -> 66; else -> 100 }
                out += BatteryDevice("XINPUT:$user:$devType", "$label ${user + 1}", percent, DeviceKind.OTHER)
            }
        }
        return out
    }
}
