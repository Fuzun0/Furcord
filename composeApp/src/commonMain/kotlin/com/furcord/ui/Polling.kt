package com.furcord.ui

/**
 * Uyarlanabilir yoklama aralığı: veri gelmiyorsa aralık kademeli uzar (×2, [maxMs]'e kadar),
 * veri gelince [baseMs]'e döner. Pencere odakta/aktif değilken aralık en az [unfocusedMs] olur.
 */
class PollBackoff(
    private val baseMs: Long,
    private val maxMs: Long,
    private val unfocusedMs: Long = maxMs,
) {
    private var cur = baseMs

    /** @param changed son yoklamada yeni veri geldi mi. @return bir sonraki bekleme süresi (ms). */
    fun next(changed: Boolean, ignoreFocus: Boolean = false): Long {
        cur = if (changed) baseMs else (cur * 2).coerceAtMost(maxMs)
        return if (ignoreFocus || Notifier.windowFocused) cur else maxOf(cur, unfocusedMs)
    }

    fun reset() { cur = baseMs }
}
