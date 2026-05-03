package com.furcord.platform

/**
 * Opens a native file picker, resizes the selected image to 128×128 and
 * returns it as a Base64-encoded JPEG string, or null if cancelled.
 */
expect fun pickImageAsBase64(): String?
