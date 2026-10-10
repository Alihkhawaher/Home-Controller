package com.technlov.homecontroller.diag

import com.technlov.home.core.ConnectorInfo

/** What the shared log starts with, so a report says which app, phone and plugins it came from. Pure. */
object LogHeader {
    fun build(
        appVersion: String,
        versionCode: Int,
        buildType: String,
        androidRelease: String,
        sdkInt: Int,
        device: String,
        connectors: List<ConnectorInfo>,
        verbose: Boolean,
        createdAt: String,
    ): String = buildString {
        appendLine("Home Controller diagnostics")
        appendLine("App: $appVersion ($versionCode, $buildType)")
        appendLine("Android: $androidRelease (API $sdkInt)")
        appendLine("Device: $device")
        appendLine("Connectors:")
        if (connectors.isEmpty()) appendLine("  (none)")
        connectors.forEach { appendLine("  ${it.name} (${it.id}) ${it.version}, ${it.protocol}") }
        appendLine("Verbose logging: ${if (verbose) "on" else "off"}")
        appendLine("Created: $createdAt")
        append("----")
    }

    /** "Google Pixel 7", without repeating the maker when the model already starts with it. */
    fun deviceName(manufacturer: String, model: String): String {
        val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
        val name = model.trim()
        return if (name.startsWith(maker, ignoreCase = true)) name else "$maker $name".trim()
    }
}
