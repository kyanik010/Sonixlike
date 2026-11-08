package com.example

import android.content.Context
import com.example.model.RadioStation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages local persistence, multi-subscription isolation, and querying
 * of discovered ALWAN and beIN SPORTS external audio stations.
 */
class RadioStationRepository(private val baseDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "sonix_radio_v1").apply { mkdirs() })

    private val memoryCache = ConcurrentHashMap<String, List<RadioStation>>()

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    fun accountKey(server: String, username: String): String {
        val identity = "$server|$username".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(identity)
            .joinToString("") { "%02x".format(it) }
    }

    private fun getFile(server: String, username: String): File {
        val key = accountKey(server, username)
        return File(baseDir, "radio_$key.json")
    }

    private fun escapeString(value: String): String {
        return "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    fun serializeStations(server: String, username: String, stations: List<RadioStation>): String {
        val sb = StringBuilder()
        sb.append("{\"version\":1,\"server\":")
        sb.append(escapeString(server))
        sb.append(",\"username\":")
        sb.append(escapeString(username))
        sb.append(",\"savedAt\":").append(System.currentTimeMillis())
        sb.append(",\"count\":").append(stations.size)
        sb.append(",\"stations\":[")
        stations.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":").append(escapeString(s.id))
            sb.append(",\"name\":").append(escapeString(s.name))
            sb.append(",\"categoryName\":").append(escapeString(s.categoryName))
            sb.append(",\"group\":").append(escapeString(s.group))
            sb.append(",\"containerExtension\":").append(escapeString(s.containerExtension))
            sb.append(",\"number\":").append(s.number)
            sb.append(",\"logoUrl\":").append(escapeString(s.logoUrl))
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun unescape(s: String): String {
        return s.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    fun parseStationsJson(text: String): List<RadioStation> {
        val result = mutableListOf<RadioStation>()
        val str = """(?:\\.|[^"\\])*"""
        val regex = Regex("""\{"id":"($str)","name":"($str)","categoryName":"($str)","group":"($str)","containerExtension":"($str)","number":(-?\d+),"logoUrl":"($str)"\}""")
        regex.findAll(text).forEach { match ->
            result.add(
                RadioStation(
                    id = unescape(match.groupValues[1]),
                    name = unescape(match.groupValues[2]),
                    categoryName = unescape(match.groupValues[3]),
                    group = unescape(match.groupValues[4]),
                    containerExtension = unescape(match.groupValues[5]),
                    number = match.groupValues[6].toIntOrNull() ?: 0,
                    logoUrl = unescape(match.groupValues[7])
                )
            )
        }
        return result
    }

    private fun logWarning(msg: String, error: Throwable) {
        try {
            android.util.Log.w("SONIXRadioRepo", msg, error)
        } catch (_: Throwable) {
            System.err.println("SONIXRadioRepo: $msg: ${error.message}")
        }
    }

    /**
     * Atomically saves discovered stations for the specified account without storing passwords.
     */
    fun saveStations(server: String, username: String, stations: List<RadioStation>) {
        val file = getFile(server, username)
        val parent = file.parentFile ?: baseDir
        if (!parent.exists()) parent.mkdirs()

        val temp = File(parent, "${file.name}.tmp")
        val payload = serializeStations(server, username, stations)

        try {
            FileOutputStream(temp).use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            memoryCache[accountKey(server, username)] = stations
        } catch (error: Throwable) {
            temp.delete()
            logWarning("Failed to save radio stations", error)
        }
    }

    /**
     * Loads saved stations for the active account. If in memory, returns immediately.
     */
    fun loadStations(server: String, username: String): List<RadioStation> {
        val key = accountKey(server, username)
        memoryCache[key]?.let { return it }

        val file = getFile(server, username)
        if (!file.isFile || file.length() <= 0L) return emptyList()

        return try {
            val text = file.readText(Charsets.UTF_8)
            val result = parseStationsJson(text)
            memoryCache[key] = result
            result
        } catch (error: Throwable) {
            logWarning("Failed to load radio stations", error)
            emptyList()
        }
    }

    /**
     * Discovers stations from raw categories and streams JSON and persists them for the account.
     */
    fun discoverAndSave(
        server: String,
        username: String,
        categoriesJson: JSONArray,
        streamsJson: JSONArray
    ): List<RadioStation> {
        val stations = RadioStationMatcher.discoverStations(categoriesJson, streamsJson)
        saveStations(server, username, stations)
        return stations
    }

    fun discoverAndSave(
        server: String,
        username: String,
        categories: List<Pair<String, String>>,
        streams: List<Map<String, String>>
    ): List<RadioStation> {
        val stations = RadioStationMatcher.discoverStations(categories, streams)
        saveStations(server, username, stations)
        return stations
    }

    /**
     * Returns structured JSON string containing categorized groups for the UI.
     */
    fun getStationsJsonPayload(server: String, username: String): JSONObject {
        val stations = loadStations(server, username)
        val alwanArray = JSONArray()
        val beinArray = JSONArray()
        val allArray = JSONArray()

        stations.forEach { station ->
            val json = station.toJson()
            allArray.put(json)
            if (station.group == "ALWAN") {
                alwanArray.put(json)
            } else {
                beinArray.put(json)
            }
        }

        return JSONObject().apply {
            put("success", true)
            put("count", stations.size)
            put("alwan", alwanArray)
            put("bein", beinArray)
            put("stations", allArray)
            put("server", server)
            put("username", username)
        }
    }

    /**
     * Removes cached stations for a single account when deleted.
     */
    fun clearAccount(server: String, username: String) {
        val key = accountKey(server, username)
        memoryCache.remove(key)
        val file = getFile(server, username)
        if (file.exists()) file.delete()
    }

    /**
     * Wipes all cached radio data upon user logout or factory reset.
     */
    fun clearAll() {
        memoryCache.clear()
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
            baseDir.mkdirs()
        }
    }
}
