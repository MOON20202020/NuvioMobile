package com.nuvio.app.features.player

import kotlin.concurrent.Volatile

/**
 * Cast-to-TV support. The player's cast button reuses the external-player flow
 * (same title, headers, subtitles and resume position); the platform intent builder
 * then sends that intent to the built-in cast screen instead of another app.
 */
internal object CastToTv {
    /** Set by platforms that ship a cast screen (Android). */
    var isSupported: Boolean = false

    @Volatile
    private var pending: Boolean = false

    fun launch(openExternal: () -> Unit) {
        pending = true
        try {
            openExternal()
        } finally {
            pending = false
        }
    }

    fun consumePending(): Boolean {
        val value = pending
        pending = false
        return value
    }
}
