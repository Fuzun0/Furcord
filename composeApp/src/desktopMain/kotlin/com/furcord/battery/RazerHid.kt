package com.furcord.battery

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

private interface HidLib : StdCallLibrary {
    fun HidD_GetHidGuid(out: Pointer)
    fun HidD_GetAttributes(h: Pointer, attr: Pointer): Boolean
    fun HidD_GetPreparsedData(h: Pointer, out: PointerByReference): Boolean
    fun HidD_FreePreparsedData(p: Pointer): Boolean
    fun HidP_GetCaps(p: Pointer, caps: Pointer): Int
    fun HidD_SetFeature(h: Pointer, buf: Pointer, len: Int): Boolean
    fun HidD_GetFeature(h: Pointer, buf: Pointer, len: Int): Boolean
    fun HidD_GetProductString(h: Pointer, buf: Pointer, len: Int): Boolean

    companion object { val I: HidLib = Native.load("hid", HidLib::class.java) }
}

private interface SetupLib : StdCallLibrary {
    fun SetupDiGetClassDevsW(guid: Pointer, enumerator: Pointer?, hwnd: Pointer?, flags: Int): Pointer
    fun SetupDiEnumDeviceInterfaces(set: Pointer, info: Pointer?, guid: Pointer, index: Int, data: Pointer): Boolean
    fun SetupDiGetDeviceInterfaceDetailW(
        set: Pointer, data: Pointer, detail: Pointer?, size: Int, required: IntByReference, info: Pointer?,
    ): Boolean
    fun SetupDiDestroyDeviceInfoList(set: Pointer): Boolean

    companion object { val I: SetupLib = Native.load("setupapi", SetupLib::class.java) }
}

/**
 * Razer fare/klavyelerin pilini (2.4 GHz alıcı veya kablo üzerinden) Razer'in
 * 91 baytlık HID feature-report protokolüyle okur. Windows bu cihazlar için pil
 * bilgisi vermez. Cihazlara yalnızca salt-okunur sorgu (class 0x07, cmd 0x80/0x84) gönderilir.
 */
object RazerHid {
    private const val RAZER_VID = 0x1532
    private const val REPORT_LEN = 91
    private val TIDS = intArrayOf(0x1F, 0x3F, 0xFF, 0x08, 0x9F)

    fun read(): List<BatteryDevice> = try {
        val found = LinkedHashMap<String, BatteryDevice>()
        for (path in razerHidPaths()) {
            val dev = probe(path) ?: continue
            found.putIfAbsent(dev.name, dev)
        }
        found.values.toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun razerHidPaths(): List<String> {
        val hid = HidLib.I
        val setup = SetupLib.I
        val guid = Memory(16).also { hid.HidD_GetHidGuid(it) }
        val set = setup.SetupDiGetClassDevsW(guid, null, null, 0x12 /* PRESENT | DEVICEINTERFACE */)
        val paths = ArrayList<String>()
        try {
            var i = 0
            while (true) {
                val data = Memory(32).also { it.clear(); it.setInt(0, 32) }
                if (!setup.SetupDiEnumDeviceInterfaces(set, null, guid, i++, data)) break
                val req = IntByReference()
                setup.SetupDiGetDeviceInterfaceDetailW(set, data, null, 0, req, null)
                if (req.value <= 4) continue
                val detail = Memory(req.value.toLong()).also { it.clear(); it.setInt(0, if (Native.POINTER_SIZE == 8) 8 else 6) }
                if (!setup.SetupDiGetDeviceInterfaceDetailW(set, data, detail, req.value, req, null)) continue
                val path = detail.getWideString(4)
                if (path.contains("vid_1532", ignoreCase = true)) paths += path
            }
        } finally {
            setup.SetupDiDestroyDeviceInfoList(set)
        }
        return paths
    }

    private fun probe(path: String): BatteryDevice? {
        val hid = HidLib.I
        // Erişim 0: klavye/fare koleksiyonları okuma-yazma ile açılamaz, feature report için gerekmez.
        val handle = Kernel32.INSTANCE.CreateFile(
            path, 0, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE, null,
            WinNT.OPEN_EXISTING, 0, null,
        )
        if (handle == WinBase.INVALID_HANDLE_VALUE) return null
        try {
            val h = handle.pointer

            val attr = Memory(12).also { it.clear(); it.setInt(0, 12) }
            if (!hid.HidD_GetAttributes(h, attr)) return null
            if ((attr.getShort(4).toInt() and 0xFFFF) != RAZER_VID) return null
            val pid = attr.getShort(6).toInt() and 0xFFFF

            val pre = PointerByReference()
            if (!hid.HidD_GetPreparsedData(h, pre)) return null
            val caps = Memory(64).also { it.clear() }
            hid.HidP_GetCaps(pre.value, caps)
            hid.HidD_FreePreparsedData(pre.value)
            val featLen = caps.getShort(8).toInt() and 0xFFFF
            if (featLen != REPORT_LEN) return null

            val raw = query(h, 0x80) ?: return null
            val percent = (raw.value * 100 + 127) / 255
            if (percent <= 0) return null

            val name = productName(h)?.let(::cleanName) ?: "Razer %04X".format(pid)
            return BatteryDevice(id = "RAZER:%04X".format(pid), name = name, percent = percent.coerceIn(0, 100))
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private class Reply(val value: Int)

    /** Pil sorgusu: her işlem kimliğini (tid) dener, geçerli (CRC+tid eşleşen) yanıtı döndürür. */
    private fun query(h: Pointer, cmd: Int): Reply? {
        for (tid in TIDS) {
            for (attempt in 0 until 4) {
                val resp = transact(h, tid, cmd) ?: break
                // resp[0] = report id; a = resp + 1
                when (resp[1].toInt() and 0xFF) {
                    0x02 -> return Reply(resp[10].toInt() and 0xFF)
                    0x01 -> Thread.sleep(25)          // meşgul: tekrar dene
                    else -> break                     // 04 zaman aşımı (cihaz bağlı değil), 05 desteklenmiyor...
                }
            }
        }
        return null
    }

    private fun transact(h: Pointer, tid: Int, cmd: Int): ByteArray? {
        val hid = HidLib.I
        val req = ByteArray(REPORT_LEN)
        req[2] = tid.toByte()
        req[6] = 0x02          // veri boyutu
        req[7] = 0x07          // komut sınıfı
        req[8] = cmd.toByte()  // 0x80 pil seviyesi
        var crc = 0
        for (k in 3..88) crc = crc xor (req[k].toInt() and 0xFF)
        req[89] = crc.toByte()

        val buf = Memory(REPORT_LEN.toLong())
        buf.write(0, req, 0, REPORT_LEN)
        if (!hid.HidD_SetFeature(h, buf, REPORT_LEN)) return null
        Thread.sleep(30)

        val rbuf = Memory(REPORT_LEN.toLong()).also { it.clear() }
        if (!hid.HidD_GetFeature(h, rbuf, REPORT_LEN)) return null
        val resp = rbuf.getByteArray(0, REPORT_LEN)

        // Yanıt, bizim isteğimizle eşleşmeli (kablolu cihazlar bazen eski/alakasız veri döndürür)
        if ((resp[2].toInt() and 0xFF) != tid) return null
        if ((resp[7].toInt() and 0xFF) != 0x07 || (resp[8].toInt() and 0xFF) != cmd) return null
        var rc = 0
        for (k in 3..88) rc = rc xor (resp[k].toInt() and 0xFF)
        if ((resp[89].toInt() and 0xFF) != rc) return null
        return resp
    }

    private fun productName(h: Pointer): String? {
        val buf = Memory(256).also { it.clear() }
        return if (HidLib.I.HidD_GetProductString(h, buf, 256)) buf.getWideString(0).trim().ifEmpty { null } else null
    }

    private fun cleanName(n: String) = n
        .replace(" HyperSpeed Dongle", "").replace(" Wireless Receiver", "")
        .replace(" Receiver", "").replace(" Dongle", "")
        .replace(" (Wired)", "").replace(" (Wireless)", "").trim()
}
