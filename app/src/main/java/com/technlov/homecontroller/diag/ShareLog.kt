package com.technlov.homecontroller.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** "Share log": a text file (header + the saved log) handed to any app through a FileProvider. */
object ShareLog {
    private const val DIR = "shared"

    fun header(container: AppContainer): String = LogHeader.build(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        device = LogHeader.deviceName(Build.MANUFACTURER, Build.MODEL),
        connectors = container.registry.all.map { it.info },
        verbose = container.eventLog.verbose,
        createdAt = LogFormat.time(System.currentTimeMillis(), ZoneId.systemDefault()),
    )

    /** Writes the file (replacing older exports) and returns the share sheet for it. */
    suspend fun prepare(context: Context, container: AppContainer, chooserTitle: String): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LogFormat.time(System.currentTimeMillis(), ZoneId.systemDefault())
            .replace(" ", "-").replace(":", "").substringBefore('.')
        val file = File(dir, "home-controller-log-$stamp.txt")
        file.writeText(container.eventLog.export(header(container)), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Home Controller log ($stamp)")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
