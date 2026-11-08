package com.example

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native Xtream Codes bridge used by the existing WebView application.
 *
 * Designed for high performance, zero memory bloating, and rock-solid stability
 * on low-spec and high-spec Android TV devices alike.
 */
class XtreamBridge(context: Context) {
    private val appContext = context.applicationContext
    private val vault = CredentialVault(appContext)
    val radioStationRepository = RadioStationRepository(appContext)
    private val catalogExecutor = Executors.newFixedThreadPool(3)
    private val catalogJobs = ConcurrentHashMap<String, Future<*>>()
    private val catalogTokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val catalogConnections = ConcurrentHashMap<String, HttpURLConnection>()
    @Volatile private var asyncResultHandler: ((String, String) -> Unit)? = null
    @Volatile private var detailsResultHandler: ((String, String) -> Unit)? = null

    fun setAsyncResultHandler(handler: ((String, String) -> Unit)?) { asyncResultHandler = handler }
    fun setDetailsResultHandler(handler: ((String, String) -> Unit)?) { detailsResultHandler = handler }

    fun shutdown() {
        catalogTokens.values.forEach { it.set(true) }
        catalogConnections.values.forEach { it.disconnect() }
        catalogJobs.values.forEach { it.cancel(true) }
        catalogExecutor.shutdownNow()
    }

    @JavascriptInterface
    fun login(server: String, username: String, password: String): String {
        return try {
            val normalizedServer = normalizeServer(server)
            require(username.isNotBlank()) { "أدخل اسم المستخدم" }
            require(password.isNotBlank()) { "أدخل كلمة المرور" }

            val response = request(normalizedServer, username, password, "player_api.php")
            val root = JSONObject(response)
            val userInfo = XtreamAccountValidator.validate(root, System.currentTimeMillis())

            vault.save(normalizedServer, username, password)
            try { CatalogRefreshScheduler.ensureScheduled(appContext) }
            catch (scheduleError: Exception) { android.util.Log.w("SONIX", "Could not schedule background catalog refresh", scheduleError) }
            JSONObject()
                .put("success", true)
                .put("user_info", userInfo)
                .put("server_info", root.optJSONObject("server_info") ?: JSONObject())
                .toString()
        } catch (error: Throwable) {
            android.util.Log.e("SONIX", "Login error", error)
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun restoreSession(): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("لا يوجد اشتراك محفوظ")
            val response = request(credentials.server, credentials.username, credentials.password, "player_api.php")
            val root = JSONObject(response)
            val userInfo = XtreamAccountValidator.validate(root, System.currentTimeMillis())
            JSONObject()
                .put("success", true)
                .put("user_info", userInfo)
                .put("server_info", root.optJSONObject("server_info") ?: JSONObject())
                .toString()
        } catch (error: Throwable) {
            android.util.Log.w("SONIX", "Restore session failed", error)
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun load(action: String): String {
        return try {
            val credentials = vault.read()
                ?: throw IllegalStateException("سجّل الدخول أولاً")
            val allowed = setOf(
                "get_live_categories",
                "get_live_streams",
                "get_vod_categories",
                "get_vod_streams",
                "get_series_categories",
                "get_series"
            )
            require(action in allowed) { "طلب API غير مسموح" }
            val response = request(credentials.server, credentials.username, credentials.password, "player_api.php", action)

            // Cache response on disk without in-memory JSON object expansion
            try {
                persistCatalogAction(credentials.server, credentials.username, action, response)
            } catch (cacheError: Throwable) {
                android.util.Log.w("SONIX", "Non-critical cache save error for $action", cacheError)
            }

            response
        } catch (error: Throwable) {
            android.util.Log.e("SONIX", "Error in load($action)", error)
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun loadSectionAsync(section: String, requestId: String) {
        val id = requestId.trim()
        if (id.isBlank() || id.length > 120) return
        val actions = when (section) {
            "live" -> "get_live_categories" to "get_live_streams"
            "movies" -> "get_vod_categories" to "get_vod_streams"
            "series" -> "get_series_categories" to "get_series"
            else -> {
                asyncResultHandler?.invoke(id, errorResult(IllegalArgumentException("قسم المزامنة غير صالح")))
                return
            }
        }
        val token = AtomicBoolean(false)
        if (catalogTokens.putIfAbsent(id, token) != null) return
        val task = FutureTask {
            try {
                val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
                ensureCatalogRequestActive(token)
                val categoriesBody = request(credentials.server, credentials.username, credentials.password,
                    "player_api.php", actions.first, requestId = id, cancellation = token)
                ensureCatalogRequestActive(token)
                val itemsBody = request(credentials.server, credentials.username, credentials.password,
                    "player_api.php", actions.second, requestId = id, cancellation = token)
                ensureCatalogRequestActive(token)
                validateCatalogSection(section, categoriesBody, itemsBody)
                val savedAt = System.currentTimeMillis()
                // Keep provider arrays as validated raw JSON to avoid a second large object graph.
                val payload = "{\"success\":true,\"version\":2,\"section\":${JSONObject.quote(section)}," +
                    "\"server\":${JSONObject.quote(credentials.server)},\"username\":${JSONObject.quote(credentials.username)}," +
                    "\"savedAt\":$savedAt,\"categories\":$categoriesBody,\"items\":$itemsBody}"
                ensureCatalogRequestActive(token)
                persistSectionCache(credentials.server, credentials.username, section, payload)
                ensureCatalogRequestActive(token)
                val response = "{\"success\":true,\"section\":${JSONObject.quote(section)},\"data\":$payload}"
                if (!token.get()) asyncResultHandler?.invoke(id, response)
            } catch (error: Throwable) {
                if (!token.get()) asyncResultHandler?.invoke(id, errorResult(error))
            } finally {
                catalogJobs.remove(id)
                catalogConnections.remove(id)
                catalogTokens.remove(id, token)
            }
        }
        catalogJobs[id] = task
        catalogExecutor.execute(task)
    }

    @JavascriptInterface
    fun cancelSectionLoad(requestId: String) {
        val id = requestId.trim()
        catalogTokens[id]?.set(true)
        catalogConnections.remove(id)?.disconnect()
        catalogJobs.remove(id)?.cancel(true)
        catalogTokens.remove(id)
    }

    @JavascriptInterface
    fun loadCachedSection(section: String): String {
        return try {
            require(section in setOf("live", "movies", "series")) { "قسم التخزين غير صالح" }
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            val file = sectionCacheFile(credentials.server, credentials.username, section)
            require(file.isFile && file.length() > 0L) { "لا توجد بيانات محفوظة لهذا القسم" }
            val cached = file.readText(Charsets.UTF_8)
            val expectedPrefix = "{\"success\":true,\"version\":2,\"section\":${JSONObject.quote(section)}," +
                "\"server\":${JSONObject.quote(credentials.server)},\"username\":${JSONObject.quote(credentials.username)},\"savedAt\":"
            require(cached.startsWith(expectedPrefix) && cached.contains("\"categories\":") && cached.contains("\"items\":")) {
                "بيانات القسم المحفوظة غير مكتملة"
            }
            cached
        } catch (error: Throwable) { errorResult(error) }
    }

    private fun validateCatalogSection(section: String, categoriesBody: String, itemsBody: String) {
        val categories = JSONArray(categoriesBody)
        val items = JSONArray(itemsBody)
        require(items.length() > 0) { "لم يُرجع الخادم أي عناصر لهذا القسم؛ احتفظنا بالبيانات السابقة" }
        val idField = if (section == "series") "series_id" else "stream_id"
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: throw IllegalArgumentException("عنصر غير صالح في قائمة المحتوى")
            val itemId = item.optString(idField).trim()
            require(itemId.isNotEmpty() && itemId != "null") { "معرّف عنصر غير صالح في القسم" }
        }
        for (index in 0 until categories.length()) {
            val category = categories.optJSONObject(index) ?: throw IllegalArgumentException("تصنيف غير صالح في قائمة المحتوى")
            val categoryId = category.optString("category_id").trim()
            require(categoryId.isNotEmpty() && categoryId != "null") { "معرّف تصنيف غير صالح في القسم" }
        }
    }

    private fun ensureCatalogRequestActive(token: AtomicBoolean) {
        if (token.get() || Thread.currentThread().isInterrupted) throw InterruptedException("تم إلغاء تحميل القسم")
    }

    private fun sectionCacheFile(server: String, username: String, section: String): File {
        val identity = "$server|$username".toByteArray(Charsets.UTF_8)
        val key = MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
        return File(File(File(appContext.filesDir, "sonix_catalog_v2"), key), "$section.json")
    }

    private fun persistSectionCache(server: String, username: String, section: String, payload: String) {
        val target = sectionCacheFile(server, username, section)
        val parent = target.parentFile ?: throw IllegalStateException("تعذر تحديد مجلد التخزين")
        if (!parent.exists() && !parent.mkdirs()) throw IllegalStateException("تعذر إنشاء مجلد التخزين")
        val temp = File(parent, "$section.json.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            android.system.Os.rename(temp.absolutePath, target.absolutePath)
            if (section == "live") {
                try {
                    val root = JSONObject(payload)
                    val categories = root.optJSONArray("categories") ?: JSONArray()
                    val items = root.optJSONArray("items") ?: JSONArray()
                    if (items.length() > 0) {
                        radioStationRepository.discoverAndSave(server, username, categories, items)
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("SONIX", "Failed to discover radio stations from live section", e)
                }
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    fun hasSavedCredentials(): Boolean = vault.read() != null

    /**
     * Refresh only catalogs already stored on this device. The worker reads only
     * the small JSON header to decide whether each section has reached 24 hours,
     * then atomically replaces its cache after the provider response is validated.
     */
    fun refreshCachedCatalogsInBackground() {
        val credentials = vault.read() ?: return
        val sections = listOf(
            Triple("live", "get_live_categories", "get_live_streams"),
            Triple("movies", "get_vod_categories", "get_vod_streams"),
            Triple("series", "get_series_categories", "get_series")
        )
        val now = System.currentTimeMillis()
        var hadFailure = false

        for ((section, categoriesAction, itemsAction) in sections) {
            val cachedFile = sectionCacheFile(credentials.server, credentials.username, section)
            if (!cachedFile.isFile || cachedFile.length() <= 0L) continue
            val savedAt = readCachedSectionSavedAt(cachedFile)
            if (savedAt > 0L && now - savedAt < 86_400_000L) continue

            try {
                val categoriesBody = request(
                    credentials.server, credentials.username, credentials.password,
                    "player_api.php", categoriesAction
                )
                val itemsBody = request(
                    credentials.server, credentials.username, credentials.password,
                    "player_api.php", itemsAction
                )
                validateCatalogSection(section, categoriesBody, itemsBody)
                val refreshedAt = System.currentTimeMillis()
                val payload = "{\"success\":true,\"version\":2,\"section\":${JSONObject.quote(section)}," +
                    "\"server\":${JSONObject.quote(credentials.server)},\"username\":${JSONObject.quote(credentials.username)}," +
                    "\"savedAt\":$refreshedAt,\"categories\":$categoriesBody,\"items\":$itemsBody}"
                persistSectionCache(credentials.server, credentials.username, section, payload)
            } catch (error: Exception) {
                hadFailure = true
                android.util.Log.w("SONIX", "Background catalog refresh failed for $section; old cache retained", error)
            }
        }

        if (hadFailure) throw IllegalStateException("تعذر تحديث قسم أو أكثر؛ ستتم إعادة المحاولة لاحقاً")
    }

    private fun readCachedSectionSavedAt(file: File): Long {
        return try {
            val prefix = file.inputStream().use { input ->
                val bytes = ByteArray(4096)
                val count = input.read(bytes)
                if (count > 0) String(bytes, 0, count, Charsets.UTF_8) else ""
            }
            Regex("\"savedAt\"\\s*:\\s*(\\d+)").find(prefix)
                ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    @JavascriptInterface
    fun seriesInfo(seriesId: String): String {
        return requestSaved("get_series_info", "series_id" to seriesId)
    }

    @JavascriptInterface
    fun shortEpg(streamId: String): String {
        return requestSaved("get_short_epg", "stream_id" to streamId, "limit" to "10")
    }

    @JavascriptInterface
    fun vodInfo(vodId: String): String {
        return requestSaved("get_vod_info", "vod_id" to vodId)
    }

    @JavascriptInterface
    fun requestDetailsAsync(action: String, paramKey: String, paramVal: String, requestId: String) {
        val id = requestId.trim()
        if (id.isBlank() || id.length > 120) return
        val token = AtomicBoolean(false)
        if (catalogTokens.putIfAbsent(id, token) != null) return
        val task = FutureTask {
            try {
                val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
                ensureCatalogRequestActive(token)
                val body = request(
                    credentials.server, credentials.username, credentials.password,
                    "player_api.php", action = action, extra = mapOf(paramKey to paramVal),
                    requestId = id, cancellation = token
                )
                ensureCatalogRequestActive(token)
                val response = "{\"success\":true,\"action\":${JSONObject.quote(action)},\"id\":${JSONObject.quote(paramVal)},\"data\":$body}"
                if (!token.get()) detailsResultHandler?.invoke(id, response)
            } catch (error: Throwable) {
                if (!token.get()) detailsResultHandler?.invoke(id, errorResult(error))
            } finally {
                catalogJobs.remove(id)
                catalogTokens.remove(id)
                catalogConnections.remove(id)
            }
        }
        catalogJobs[id] = task
        catalogExecutor.execute(task)
    }

    @JavascriptInterface
    fun cancelDetailsLoad(requestId: String) {
        val id = requestId.trim()
        catalogTokens.remove(id)?.set(true)
        catalogConnections.remove(id)?.disconnect()
        catalogJobs.remove(id)?.cancel(true)
    }

    @JavascriptInterface
    fun streamUrl(type: String, streamId: String, extension: String): String {
        return try {
            val uri = buildPlaybackUri(type, streamId, extension)
            JSONObject().put("success", true).put("url", uri.toString()).toString()
        } catch (error: Throwable) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun loadCached(action: String): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            val allowed = catalogActions
            require(action in allowed) { "طلب محتوى مخزن غير مسموح" }

            // 1. Try file-based action cache (instant, 0-allocation)
            val actionFile = File(appContext.filesDir, "sonix_cache_${action}.json")
            if (actionFile.isFile && actionFile.length() > 0) {
                return actionFile.readText(Charsets.UTF_8)
            }

            // 2. Fallback to legacy combined cache file if present
            val legacyFile = File(appContext.filesDir, "sonix_provider_cache_v1.json")
            if (legacyFile.isFile && legacyFile.length() > 0) {
                val envelope = JSONObject(legacyFile.readText(Charsets.UTF_8))
                if (envelope.optInt("version") == 1 &&
                    envelope.optString("server") == credentials.server &&
                    envelope.optString("username") == credentials.username) {
                    val actions = envelope.optJSONObject("actions")
                    val cachedData = actions?.optJSONArray(action)?.toString()
                    if (!cachedData.isNullOrBlank()) {
                        return cachedData
                    }
                }
            }

            throw IllegalStateException("بيانات هذا القسم غير محفوظة")
        } catch (error: Throwable) {
            android.util.Log.w("SONIX", "loadCached failed for $action", error)
            errorResult(error)
        }
    }

    private val catalogActions = setOf(
        "get_live_categories", "get_live_streams",
        "get_vod_categories", "get_vod_streams",
        "get_series_categories", "get_series"
    )

    /**
     * Persists each catalog action cleanly into its own file on disk.
     * Avoids deserializing 50,000+ items into memory, preventing OutOfMemoryError
     * on Android TV hardware with 1GB or 2GB RAM.
     */
    private fun persistCatalogAction(server: String, username: String, action: String, response: String) {
        val targetFile = File(appContext.filesDir, "sonix_cache_${action}.json")
        val tempFile = File(appContext.filesDir, "sonix_cache_${action}.tmp")
        try {
            FileOutputStream(tempFile).use { it.write(response.toByteArray(Charsets.UTF_8)) }
            if (!tempFile.renameTo(targetFile)) {
                targetFile.delete()
                tempFile.renameTo(targetFile)
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }

        // Update small metadata file
        try {
            val metaFile = File(appContext.filesDir, "sonix_cache_meta.json")
            val meta = JSONObject()
                .put("version", 2)
                .put("server", server)
                .put("username", username)
                .put("savedAt", System.currentTimeMillis())
            metaFile.writeText(meta.toString(), Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun clearCatalogCache() {
        catalogActions.forEach { action ->
            File(appContext.filesDir, "sonix_cache_${action}.json").delete()
            File(appContext.filesDir, "sonix_cache_${action}.tmp").delete()
        }
        File(appContext.filesDir, "sonix_cache_meta.json").delete()
        File(appContext.filesDir, "sonix_provider_cache_v1.json").delete()
        File(appContext.filesDir, "sonix_provider_cache_v1.json.bak").delete()
        File(appContext.filesDir, "sonix_provider_cache_v1.json.new").delete()
        File(appContext.filesDir, "sonix_catalog_v2").deleteRecursively()
    }

    @JavascriptInterface
    fun savedCredentials(): String {
        return try {
            val credentials = vault.read() ?: return JSONObject().put("success", false).toString()
            JSONObject()
                .put("success", true)
                .put("server", credentials.server)
                .put("username", credentials.username)
                .toString()
        } catch (error: Throwable) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun logout(): String {
        vault.clear()
        clearCatalogCache()
        radioStationRepository.clearAll()
        try { CatalogRefreshScheduler.cancel(appContext) }
        catch (scheduleError: Exception) { android.util.Log.w("SONIX", "Could not cancel background catalog refresh", scheduleError) }
        return JSONObject().put("success", true).toString()
    }

    @JavascriptInterface
    fun getDiscoveredRadioStations(): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            var stations = radioStationRepository.loadStations(credentials.server, credentials.username)
            if (stations.isEmpty()) {
                val liveFile = sectionCacheFile(credentials.server, credentials.username, "live")
                if (liveFile.isFile && liveFile.length() > 0L) {
                    val liveJson = JSONObject(liveFile.readText(Charsets.UTF_8))
                    val categories = liveJson.optJSONArray("categories") ?: JSONArray()
                    val items = liveJson.optJSONArray("items") ?: JSONArray()
                    if (items.length() > 0) {
                        stations = radioStationRepository.discoverAndSave(credentials.server, credentials.username, categories, items)
                    }
                }
            }
            radioStationRepository.getStationsJsonPayload(credentials.server, credentials.username).toString()
        } catch (error: Throwable) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun refreshRadioStations(): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            val liveFile = sectionCacheFile(credentials.server, credentials.username, "live")
            if (liveFile.isFile && liveFile.length() > 0L) {
                val liveJson = JSONObject(liveFile.readText(Charsets.UTF_8))
                val categories = liveJson.optJSONArray("categories") ?: JSONArray()
                val items = liveJson.optJSONArray("items") ?: JSONArray()
                radioStationRepository.discoverAndSave(credentials.server, credentials.username, categories, items)
            }
            radioStationRepository.getStationsJsonPayload(credentials.server, credentials.username).toString()
        } catch (error: Throwable) {
            errorResult(error)
        }
    }

    fun buildPlaybackUri(type: String, streamId: String, extension: String): Uri {
        val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً للتشغيل")
        require(type in setOf("live", "movie", "series")) { "نوع المحتوى غير مسموح" }
        require(streamId.toLongOrNull() != null) { "معرف البث غير صالح" }
        val safeExtension = extension.trim().lowercase().removePrefix(".")
        require(safeExtension.matches(Regex("[a-z0-9]{1,8}"))) { "امتداد البث غير صالح" }
        val url = "${credentials.server}/$type/${Uri.encode(credentials.username)}/${Uri.encode(credentials.password)}/${Uri.encode(streamId)}.$safeExtension"
        return Uri.parse(url)
    }

    private fun requestSaved(action: String, vararg parameters: Pair<String, String>): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            request(
                credentials.server,
                credentials.username,
                credentials.password,
                "player_api.php",
                action,
                parameters.toMap()
            )
        } catch (error: Throwable) {
            errorResult(error)
        }
    }

    private fun request(
        server: String,
        username: String,
        password: String,
        endpoint: String,
        action: String? = null,
        extra: Map<String, String> = emptyMap(),
        requestId: String? = null,
        cancellation: AtomicBoolean? = null
    ): String {
        val builder = Uri.parse("$server/$endpoint").buildUpon()
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
        if (!action.isNullOrBlank()) builder.appendQueryParameter("action", action)
        extra.forEach { (key, value) -> builder.appendQueryParameter(key, value) }

        var currentUrl = builder.build().toString()
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val urlObj = URL(currentUrl)
            if (cancellation?.get() == true || Thread.currentThread().isInterrupted) throw InterruptedException("تم إلغاء تحميل القسم")
            val connection = urlObj.openConnection() as HttpURLConnection
            if (requestId != null) catalogConnections[requestId] = connection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20_000
            connection.readTimeout = 40_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("User-Agent", "SONIX-TV/1.0 (Android TV)")

            try {
                val statusCode = connection.responseCode

                // Handle redirects gracefully (HTTP 301, 302, 303, 307, 308)
                if (statusCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            URL(urlObj, location).toString()
                        }
                        redirects++
                        continue
                    }
                }

                val isSuccess = statusCode in 200..299
                val rawStream = if (isSuccess) connection.inputStream else connection.errorStream
                if (rawStream == null) {
                    if (!isSuccess) throw IllegalStateException("استجابة الخادم غير ناجحة (HTTP $statusCode)")
                    throw IllegalStateException("أعاد الخادم استجابة فارغة")
                }

                val isGzip = connection.contentEncoding?.equals("gzip", ignoreCase = true) == true
                val inStream: InputStream = if (isGzip) GZIPInputStream(rawStream) else rawStream

                val body = inStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                if (!isSuccess) {
                    throw IllegalStateException("استجابة الخادم غير ناجحة (HTTP $statusCode)")
                }
                if (body.isBlank()) throw IllegalStateException("أعاد الخادم استجابة فارغة")
                return body
            } finally {
                if (requestId != null) catalogConnections.remove(requestId, connection)
                connection.disconnect()
            }
        }
        throw IllegalStateException("تجاوز عدد عمليات إعادة التوجيه المسموح بها ($maxRedirects)")
    }

    private fun errorResult(error: Throwable): String {
        val message = when (error) {
            is UnknownHostException -> "تعذر العثور على خادم الاشتراك. تحقق من عنوان السيرفر والإنترنت."
            is SocketTimeoutException -> "انتهت مهلة الاتصال بخادم الاشتراك. حاول مرة أخرى."
            is ConnectException -> "تعذر الاتصال بخادم الاشتراك. تحقق من عنوان السيرفر والمنفذ."
            is JSONException -> "أعاد الخادم بيانات غير مفهومة؛ تأكد من توافقه مع Xtream Codes API."
            is OutOfMemoryError -> "ذاكرة الجهاز محدودة. تم تحسين الذاكرة، حاول مجدداً."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "تعذر الاتصال بالخادم."
        }
        return JSONObject()
            .put("success", false)
            .put("error", message)
            .toString()
    }

    companion object {
        fun normalizeServer(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = try {
                java.net.URI(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("أدخل عنوان خادم صحيحاً يبدأ بـ http:// أو https://")
            }
            val scheme = uri.scheme?.lowercase()
            require((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
                "أدخل عنوان خادم صحيحاً يبدأ بـ http:// أو https://"
            }
            require(uri.userInfo.isNullOrBlank()) { "لا تضع اسم المستخدم أو كلمة المرور داخل عنوان الخادم" }
            require(uri.query.isNullOrBlank() && uri.fragment.isNullOrBlank()) {
                "أدخل عنوان الخادم فقط دون معاملات إضافية"
            }
            require(uri.port == -1 || uri.port in 1..65535) { "منفذ الخادم غير صالح" }
            return trimmed
        }
    }
}

/**
 * Encrypted credential vault backed by Android Keystore AES-GCM,
 * with resilient fallback encryption for cheap Android TV boxes where
 * hardware AndroidKeyStore is unstable or unsupported.
 */
private class CredentialVault(private val context: Context) {
    private val preferences = context.getSharedPreferences("sonix_secure_account", Context.MODE_PRIVATE)

    fun save(server: String, username: String, password: String) {
        val plain = JSONObject()
            .put("server", server)
            .put("username", username)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        try {
            // Primary path: AndroidKeyStore AES/GCM
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain)
            preferences.edit()
                .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .remove("fallback_ciphertext")
                .apply()
        } catch (e: Throwable) {
            android.util.Log.w("SONIX", "AndroidKeyStore encryption failed, using device fallback", e)
            val fallbackKey = getFallbackKey()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, fallbackKey)
            val encrypted = cipher.doFinal(plain)
            preferences.edit()
                .putString("fallback_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .remove("ciphertext")
                .remove("iv")
                .apply()
        }
    }

    fun read(): StoredCredentials? {
        // 1. Check primary keystore storage
        val encoded = preferences.getString("ciphertext", null)
        val encodedIv = preferences.getString("iv", null)
        if (!encoded.isNullOrBlank() && !encodedIv.isNullOrBlank()) {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
                )
                val plain = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
                val json = JSONObject(String(plain, Charsets.UTF_8))
                return StoredCredentials(
                    json.getString("server"),
                    json.getString("username"),
                    json.getString("password")
                )
            } catch (e: Throwable) {
                android.util.Log.w("SONIX", "KeyStore decrypt failed", e)
            }
        }

        // 2. Check fallback storage
        val fallbackEncoded = preferences.getString("fallback_ciphertext", null)
        if (!fallbackEncoded.isNullOrBlank()) {
            try {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, getFallbackKey())
                val plain = cipher.doFinal(Base64.decode(fallbackEncoded, Base64.NO_WRAP))
                val json = JSONObject(String(plain, Charsets.UTF_8))
                return StoredCredentials(
                    json.getString("server"),
                    json.getString("username"),
                    json.getString("password")
                )
            } catch (e: Throwable) {
                android.util.Log.w("SONIX", "Fallback decrypt failed", e)
            }
        }

        return null
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun getFallbackKey(): SecretKey {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "sonix_tv_default_id"
        val seed = "sonix_tv_secure_salt_${context.packageName}_${Build.FINGERPRINT}_$androidId"
        val sha = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(sha, "AES")
    }

    private companion object {
        const val KEY_ALIAS = "sonix_xtream_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private data class StoredCredentials(
    val server: String,
    val username: String,
    val password: String
)
