package io.github.deveshb1.paradiseflac

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object Station {
    const val NAME = "Radio Paradise"
    const val STREAM_URL = "https://stream.radioparadise.com/flac"
    const val USER_AGENT = "ParadiseFLAC/1.0 (Android)"
    private const val NOW_PLAYING_URL = "https://api.radioparadise.com/api/now_playing?chan=0"

    data class Track(
        val artist: String,
        val title: String,
        val album: String,
        val year: Int?,
        val coverUrl: String?,
        /** Seconds until the server moves on to the next song. */
        val secondsLeft: Int,
    )

    /** Blocking; call off the main thread. Returns null on any network/parse failure. */
    fun nowPlaying(): Track? = runCatching {
        val conn = URL(NOW_PLAYING_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        Track(
            artist = json.optString("artist"),
            title = json.optString("title"),
            album = json.optString("album"),
            year = json.optString("year").toIntOrNull(),
            coverUrl = json.optString("cover").ifBlank { null },
            secondsLeft = json.optString("time").toIntOrNull() ?: 15,
        )
    }.getOrNull()
}
