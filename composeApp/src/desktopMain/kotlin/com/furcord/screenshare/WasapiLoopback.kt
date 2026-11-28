package com.furcord.screenshare

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Windows WASAPI loopback yakalama (saf JNA, COM vtable çağrıları).
 *
 * Modlar (Discord'un yaptığı gibi):
 *  - [Mode.Exclude]: belirtilen işlem ağacı HARİÇ tüm sistem sesi. Furcord'un kendi sesi (sesli sohbet
 *    konuşmaları) yayına karışmaz. Windows 10 (2004) ve üstü, "process loopback".
 *  - [Mode.Include]: yalnızca belirtilen işlem ağacının sesi. Pencere paylaşılırken o uygulamanın sesi.
 *  - [Mode.Device]: varsayılan çıkış aygıtının tüm sesi (eski Windows için yedek; Furcord sesi dahil).
 *
 * Çıktı: 48 kHz, 16 bit, STEREO, 20 ms (960 kare = 1920 short) PCM çerçeveleri, [onFrame] ile verilir.
 * Hiçbir şey çalmıyorken WASAPI paket üretmez; bu durumda [onFrame] çağrılmaz.
 */
internal class WasapiLoopback(private val mode: Mode, private val onFrame: (ShortArray) -> Unit) {

    sealed class Mode {
        object Device : Mode()
        class Include(val pid: Int) : Mode()
        class Exclude(val pid: Int) : Mode()
    }

    @Volatile private var running = false
    /** Başlatma/yakalama hatası olduysa true (çağıran yedek moda geçebilir). */
    @Volatile var failed = false; private set
    @Volatile var error: Throwable? = null; private set
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            runCatching { capture() }.onFailure { failed = true; error = it }
            running = false
        }, "furcord-loopback").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(1_500) } }
        thread = null
    }

    // ── COM yardımcıları ──────────────────────────────────────────────────────

    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val vtbl = obj.getPointer(0)
        val fn = Function.getFunction(vtbl.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun release(obj: Pointer?) { if (obj != null) runCatching { call(obj, 2) } }
    private fun guid(s: String) = Guid.GUID.fromString(s)
    // COM bellek düzeni (little-endian alanlar) ile bayt dizisi
    private fun guidBytes(s: String): ByteArray { val g = guid(s); g.write(); return g.pointer.getByteArray(0, 16) }

    private class Opened(
        val client: Pointer, val isFloat: Boolean, val bits: Int, val channels: Int,
        val rate: Int, val blockAlign: Int, val event: WinNT.HANDLE?, val extra: List<Pointer?>,
    )

    // ── Aygıt (tüm ses) ───────────────────────────────────────────────────────
    private fun openDevice(): Opened {
        val enumRef = PointerByReference()
        check(Ole32.INSTANCE.CoCreateInstance(
            guid("{BCDE0395-E52F-467C-8E3D-C4579291692E}"), null, 23,
            guid("{A95664D2-9614-4F35-A746-DE8DB63617E6}"), enumRef).toInt() >= 0) { "MMDeviceEnumerator" }
        val enumerator = enumRef.value
        val devRef = PointerByReference()
        check(call(enumerator, 4, 0, 0, devRef) >= 0) { "GetDefaultAudioEndpoint" }   // eRender, eConsole
        val device = devRef.value
        val clientRef = PointerByReference()
        check(call(device, 3, guid("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}"), 23, null, clientRef) >= 0) { "Activate" }
        val client = clientRef.value
        val fmtRef = PointerByReference()
        check(call(client, 8, fmtRef) >= 0) { "GetMixFormat" }
        val fmt = fmtRef.value
        val tag      = fmt.getShort(0).toInt() and 0xFFFF
        val channels = fmt.getShort(2).toInt() and 0xFFFF
        val rate     = fmt.getInt(4)
        val blockAl  = fmt.getShort(12).toInt() and 0xFFFF
        val bits     = fmt.getShort(14).toInt() and 0xFFFF
        val isFloat  = if (tag == 0xFFFE) fmt.getInt(24) == 3 else tag == 3
        check(channels in 1..8 && rate > 0 && blockAl > 0) { "format" }
        check(call(client, 3, 0, 0x00020000, 2_000_000L, 0L, fmt, null) >= 0) { "Initialize" }
        return Opened(client, isFloat, bits, channels, rate, blockAl, null, listOf(device, enumerator))
    }

    // ── İşlem bazlı (process loopback) ────────────────────────────────────────

    private interface Mmdevapi : StdCallLibrary {
        fun ActivateAudioInterfaceAsync(path: WString, riid: Pointer, params: Pointer, handler: Pointer, op: PointerByReference): Int
    }
    private interface QiCb   : Callback { fun invoke(self: Pointer, riid: Pointer, ppv: Pointer): Int }
    private interface RefCb  : Callback { fun invoke(self: Pointer): Int }
    private interface DoneCb : Callback { fun invoke(self: Pointer, op: Pointer): Int }

    private val keep = ArrayList<Any>()   // COM geri çağırım nesneleri GC edilmesin

    private fun openProcess(pid: Int, include: Boolean): Opened {
        val iidAgile   = guidBytes("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}")
        val iidUnknown = guidBytes("{00000000-0000-0000-C000-000000000046}")
        val iidHandler = guidBytes("{41D949AB-9862-444A-80F6-C261334DA5EB}")
        val latch = CountDownLatch(1)
        var opRef: Pointer? = null

        val qi = object : QiCb {
            override fun invoke(self: Pointer, riid: Pointer, ppv: Pointer): Int {
                val id = riid.getByteArray(0, 16)
                return if (id.contentEquals(iidUnknown) || id.contentEquals(iidAgile) || id.contentEquals(iidHandler)) {
                    ppv.setPointer(0, self); 0
                } else { ppv.setPointer(0, null); -2147467262 /* E_NOINTERFACE */ }
            }
        }
        val addRef = object : RefCb { override fun invoke(self: Pointer) = 2 }
        val rel    = object : RefCb { override fun invoke(self: Pointer) = 1 }
        val done   = object : DoneCb {
            override fun invoke(self: Pointer, op: Pointer): Int { opRef = op; latch.countDown(); return 0 }
        }
        keep.addAll(listOf(qi, addRef, rel, done))
        val vtbl = Memory(4L * Native.POINTER_SIZE)
        vtbl.setPointer(0, CallbackReference.getFunctionPointer(qi))
        vtbl.setPointer(8, CallbackReference.getFunctionPointer(addRef))
        vtbl.setPointer(16, CallbackReference.getFunctionPointer(rel))
        vtbl.setPointer(24, CallbackReference.getFunctionPointer(done))
        val handler = Memory(Native.POINTER_SIZE.toLong()); handler.setPointer(0, vtbl)
        keep.add(vtbl); keep.add(handler)

        // AUDIOCLIENT_ACTIVATION_PARAMS { type = PROCESS_LOOPBACK(1), pid, mode (INCLUDE=0 / EXCLUDE=1) }
        val ap = Memory(12); ap.setInt(0, 1); ap.setInt(4, pid); ap.setInt(8, if (include) 0 else 1)
        // PROPVARIANT VT_BLOB (24 bayt, x64)
        val pv = Memory(24); pv.clear(); pv.setShort(0, 65); pv.setInt(8, 12); pv.setPointer(16, ap)
        keep.add(ap); keep.add(pv)

        val lib = Native.load("Mmdevapi", Mmdevapi::class.java)
        val iidClient = guid("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")
        iidClient.write()
        val opOut = PointerByReference()
        val hrA = lib.ActivateAudioInterfaceAsync(WString("VAD\\Process_Loopback"), iidClient.pointer, pv, handler, opOut)
        check(hrA >= 0) { "ActivateAsync 0x" + Integer.toHexString(hrA) }
        check(latch.await(5, TimeUnit.SECONDS)) { "Activate timeout" }
        val op = opRef ?: opOut.value
        val hrRef = IntByReference(); val unkRef = PointerByReference()
        check(call(op, 3, hrRef, unkRef) >= 0 && hrRef.value >= 0) { "GetActivateResult ${hrRef.value}" }
        val clientRef = PointerByReference()
        check(call(unkRef.value, 0, iidClient, clientRef) >= 0) { "QI IAudioClient" }
        val client = clientRef.value

        // Biçim: PCM 16 bit, stereo, 48 kHz
        val wf = Memory(18); wf.clear()
        wf.setShort(0, 1); wf.setShort(2, 2); wf.setInt(4, 48_000); wf.setInt(8, 192_000)
        wf.setShort(12, 4); wf.setShort(14, 16); wf.setShort(16, 0)
        // Bayrak denemeleri: bazı Windows sürümleri bazı bayrak birleşimlerini reddeder (0x88890021)
        val flagSets = listOf(
            0x00020000 or 0x00040000 or 0x00080000,   // LOOPBACK | EVENTCALLBACK | NOPERSIST
            0x00020000 or 0x00040000,                 // LOOPBACK | EVENTCALLBACK
            0x00020000,                               // LOOPBACK (olaysız, yoklama)
        )
        var used = -1; var lastHr = 0
        for (fl in flagSets) {
            lastHr = call(client, 3, 0, fl, 0L, 0L, wf, null)
            if (lastHr >= 0) { used = fl; break }
        }
        check(used >= 0) { "Initialize(process) 0x" + Integer.toHexString(lastHr) }
        var ev: WinNT.HANDLE? = null
        if (used and 0x00040000 != 0) {
            ev = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
            check(call(client, 13, ev.pointer) >= 0) { "SetEventHandle" }
        }
        return Opened(client, false, 16, 2, 48_000, 4, ev, listOf(unkRef.value))
    }

    // ── Yakalama ──────────────────────────────────────────────────────────────
    private fun capture() {
        Ole32.INSTANCE.CoInitializeEx(null, 0 /* COINIT_MULTITHREADED */)
        var o: Opened? = null
        var cap: Pointer? = null
        try {
            o = when (val m = mode) {
                is Mode.Device  -> openDevice()
                is Mode.Include -> openProcess(m.pid, true)
                is Mode.Exclude -> openProcess(m.pid, false)
            }
            val capRef = PointerByReference()
            check(call(o.client, 14, guid("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}"), capRef) >= 0) { "GetService" }
            cap = capRef.value
            check(call(o.client, 10) >= 0) { "Start" }

            val rate = o.rate; val blockAl = o.blockAlign; val channels = o.channels
            val out = ShortArray(960 * 2)
            var outPos = 0
            val step = rate / 48_000.0
            var srcPos = 0.0
            val srcL = FloatArray(16_384); val srcR = FloatArray(16_384)

            while (running) {
                var got = false
                while (running) {
                    val sizeRef = IntByReference()
                    if (call(cap, 5, sizeRef) < 0 || sizeRef.value == 0) break
                    val dataRef = PointerByReference(); val framesRef = IntByReference(); val flagsRef = IntByReference()
                    if (call(cap, 3, dataRef, framesRef, flagsRef, null, null) < 0) break
                    val frames = framesRef.value
                    val silent = (flagsRef.value and 0x2) != 0
                    val n = minOf(frames, srcL.size)
                    val data = dataRef.value
                    for (i in 0 until n) {
                        var l = 0f; var r = 0f
                        if (!silent && data != null) {
                            val base = i.toLong() * blockAl
                            if (o.isFloat && o.bits == 32) {
                                l = data.getFloat(base); r = if (channels > 1) data.getFloat(base + 4) else l
                            } else if (o.bits == 16) {
                                l = data.getShort(base) / 32768f; r = if (channels > 1) data.getShort(base + 2) / 32768f else l
                            }
                        }
                        srcL[i] = l; srcR[i] = r
                    }
                    call(cap, 4, frames)
                    got = true
                    while (srcPos < n) {
                        val i0 = Math.floor(srcPos).toInt(); val frac = (srcPos - i0).toFloat()
                        val l0 = srcL[i0]; val r0 = srcR[i0]
                        val l1 = if (i0 + 1 < n) srcL[i0 + 1] else l0; val r1 = if (i0 + 1 < n) srcR[i0 + 1] else r0
                        out[outPos++] = ((l0 + (l1 - l0) * frac).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        out[outPos++] = ((r0 + (r1 - r0) * frac).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        if (outPos >= out.size) { onFrame(out.copyOf()); outPos = 0 }
                        srcPos += step
                    }
                    srcPos -= n
                }
                if (!got) {
                    if (o.event != null) Kernel32.INSTANCE.WaitForSingleObject(o.event, 20) else Thread.sleep(8)
                }
            }
            call(o.client, 11)   // Stop
        } finally {
            release(cap)
            o?.let { release(it.client); if (mode is Mode.Device) it.extra.forEach { p -> release(p) } }
            o?.event?.let { runCatching { Kernel32.INSTANCE.CloseHandle(it) } }
            Ole32.INSTANCE.CoUninitialize()
        }
    }
}
