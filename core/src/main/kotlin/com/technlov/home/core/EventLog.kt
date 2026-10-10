package com.technlov.home.core

/**
 * Diagnostics: what the hub and the connectors did, for the in-app log and its "Share log" button. Connectors are
 * plain Kotlin (no Android), so they write here instead of android.util.Log.
 *
 * Never log a secret: no device keys, tokens or Wi-Fi passwords -- write "key received", not the key. [verbose]
 * entries (whole frames to and from devices) are recorded only when the user turned verbose logging on.
 */
interface EventLog {
    fun log(level: Level, source: String, message: String, error: Throwable? = null)
    val verbose: Boolean

    enum class Level { DEBUG, INFO, WARN, ERROR }

    /** Writes nothing: the default for connectors created without a log (and for tests). */
    object None : EventLog {
        override fun log(level: Level, source: String, message: String, error: Throwable?) {}
        override val verbose = false
    }
}

fun EventLog.info(source: String, message: String) = log(EventLog.Level.INFO, source, message)
fun EventLog.warn(source: String, message: String, error: Throwable? = null) = log(EventLog.Level.WARN, source, message, error)
fun EventLog.error(source: String, message: String, error: Throwable? = null) = log(EventLog.Level.ERROR, source, message, error)

/** Verbose frame logging: the message is only built when verbose logging is on. */
inline fun EventLog.trace(source: String, message: () -> String) {
    if (verbose) log(EventLog.Level.DEBUG, source, message())
}
