package com.technlov.homecontroller.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Creates the one DataStore that holds [HomeData] (only one instance per file may exist in the process). */
object HomeDataStore {
    private const val TAG = "HomeDataStore"

    fun create(context: Context, scope: CoroutineScope): DataStore<HomeData> {
        val file = File(context.filesDir, "home/home.json")
        return DataStoreFactory.create(
            serializer = HomeDataSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { error ->
                // Never lose the user's setup silently: keep the unreadable file next to the fresh one.
                Log.w(TAG, "Saved data could not be read; keeping a copy and starting fresh", error)
                runCatching { file.copyTo(File(file.parentFile, "home.unreadable.json"), overwrite = true) }
                HomeData()
            },
            scope = scope,
            produceFile = { file },
        )
    }
}

internal object HomeDataSerializer : Serializer<HomeData> {
    override val defaultValue: HomeData = HomeData()

    override suspend fun readFrom(input: InputStream): HomeData {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            HomeJson.decode(text)
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable home data", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable home data", e)
        }
    }

    override suspend fun writeTo(t: HomeData, output: OutputStream) {
        output.write(HomeJson.encode(t).encodeToByteArray())
    }
}
