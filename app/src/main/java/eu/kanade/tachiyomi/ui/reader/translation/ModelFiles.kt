package eu.kanade.tachiyomi.ui.reader.translation

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException

/**
 * The file [name] in [directory], downloaded from [url] the first time, so a model is fetched once rather than
 * shipped with the app. Blocking.
 */
internal fun downloadOnce(client: OkHttpClient, directory: File, name: String, url: String): File {
    val file = File(directory, name)
    if (file.exists()) return file

    directory.mkdirs()
    val partial = File(directory, "$name.part")
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Download of $name failed: HTTP ${response.code}")
        partial.sink().buffer().use { it.writeAll(response.body.source()) }
    }
    if (!partial.renameTo(file)) throw IOException("Could not save $name")
    return file
}
