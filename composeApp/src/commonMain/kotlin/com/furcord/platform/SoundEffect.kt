package com.furcord.platform

/** Platform-agnostic ses efekti çalma. */
expect object SoundEffect {
    /** Kullanıcı ses kanalına katıldığında çalınan yumuşak ding. */
    fun playJoin()
    /** Kullanıcı ses kanalından ayrıldığında çalınan yumuşak ding. */
    fun playLeave()
}
