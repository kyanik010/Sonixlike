package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.Shader
import android.graphics.RadialGradient
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.ColorFilter
import android.graphics.Canvas
import android.net.Uri
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.Cue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.ui.SubtitleView
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import com.example.playback.PlaybackController
import com.example.playback.PlaybackUiBridge
import com.example.playback.VideoEngineType
import com.example.playback.SonixDiagnosticLog
import org.json.JSONObject
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat

import org.json.JSONArray
import com.example.Media3PlaybackPolicy
import com.example.Media3PlaybackMode
import com.example.LiveReconnectDataSource
import com.example.OpenSubtitlesClient

internal data class TimedSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var rootContainer: FrameLayout? = null
    private var dedicatedSubtitleView: SubtitleView? = null
    private var sonixBackgroundDrawable: SonixBackgroundDrawable? = null
    private lateinit var playbackController: PlaybackController
    private var nativePlayer: ExoPlayer? = null
    private var nativePlayerView: PlayerView? = null
    private var nativeVideoStreamType: String? = null
    private var nativeVideoStreamId: String? = null
    private val playbackRecoveryHandler = Handler(Looper.getMainLooper())
    private var playbackRequestStartedAtMs = 0L
    private var bufferingStartedAtMs = 0L
    private var hasPlaybackReachedReady = false
    private var firstFrameLogged = false
    private var liveRecoveryAttempts = 0
    private var playbackWatchdogRunnable: Runnable? = null
    private var liveRecoveryRunnable: Runnable? = null
    private var nativePlayerCornerRadiusPx = 0f
    @Volatile private var immersiveVideoFullscreen = false
    private var activeMediaUri: Uri? = null
    private var activeSubtitleFile: File? = null
    private var activeSubtitleSourceFile: File? = null
    private var activeSubtitleLabel: String = ""
    @Volatile private var activeSubtitleEnabled: Boolean = true
    private var subtitleDelayMs = 0L
    private var lastSubtitleFont = "Reem Kufi"
    private var lastSubtitleSize = "24"
    private var lastSubtitleColor = "#FFFFFF"
    private var lastSubtitleBackground = "off"
    private var lastSubtitleOutline = "shadow"
    private var lastSubtitlePosition = "bottom"
    private var activeSubtitleCues: List<TimedSubtitleCue> = emptyList()
    private var subtitleCueCursor = 0
    private var lastSubtitlePositionMs = 0L
    private var lastRenderedSubtitleText: String? = null
    private val subtitleRenderHandler = Handler(Looper.getMainLooper())
    private val subtitleRenderRunnable = object : Runnable {
        override fun run() {
            if (!playbackController.isActive() || activeSubtitleCues.isEmpty() || !activeSubtitleEnabled) {
                playbackController.getActiveSubtitleView()?.setCues(emptyList())
                dedicatedSubtitleView?.setCues(emptyList())
                lastRenderedSubtitleText = null
                if (playbackController.isActive() && activeSubtitleCues.isNotEmpty()) {
                    subtitleRenderHandler.postDelayed(this, 500L)
                }
                return
            }
            // Apply sync at render time so every supported format (SRT, VTT, ASS/SSA, TTML and MicroDVD) responds to delay without rewriting its file.
            val position = (playbackController.getCurrentPosition() - subtitleDelayMs).coerceAtLeast(0L)
            if (position < lastSubtitlePositionMs) subtitleCueCursor = 0
            while (subtitleCueCursor < activeSubtitleCues.size &&
                activeSubtitleCues[subtitleCueCursor].endMs <= position) {
                subtitleCueCursor++
            }
            val activeText = StringBuilder()
            var cueIndex = subtitleCueCursor
            while (cueIndex < activeSubtitleCues.size &&
                activeSubtitleCues[cueIndex].startMs <= position) {
                val cue = activeSubtitleCues[cueIndex]
                if (position < cue.endMs) {
                    if (activeText.isNotEmpty()) activeText.append("\n")
                    activeText.append(cue.text)
                }
                cueIndex++
            }
            lastSubtitlePositionMs = position
            val text = activeText.toString()
            if (text != lastRenderedSubtitleText) {
                lastRenderedSubtitleText = text
                val cues = if (text.isBlank()) emptyList() else listOf(Cue.Builder().setText(text).build())
                playbackController.getActiveSubtitleView()?.setCues(cues)
                dedicatedSubtitleView?.setCues(cues)
            }
            subtitleRenderHandler.postDelayed(this, if (playbackController.isPlaying()) 100L else 250L)
        }
    }
    private val subtitleTypefaceCache = mutableMapOf<String, Typeface>()
    private val subtitleIoExecutor = Executors.newFixedThreadPool(3)
    private val codecDiagnosticsExecutor = Executors.newSingleThreadExecutor()
    private var lastUhdCodecDiagnosticKey: String? = null
    private val subtitleGeneration = AtomicLong(0)
    private val subtitleActivationHandler = Handler(Looper.getMainLooper())
    private val playbackProgressHandler = Handler(Looper.getMainLooper())
    private val playbackProgressRunnable = object : Runnable {
        override fun run() {
            if (playbackController.isActive()) {
                val rawDuration = playbackController.getDuration()
                val duration = if (rawDuration == C.TIME_UNSET || rawDuration <= 0L) -1L else rawDuration
                val position = playbackController.getCurrentPosition().coerceAtLeast(0L)
                val seekable = playbackController.isSeekable() && duration > 0L
                webView?.evaluateJavascript(
                    "window.onNativePlaybackProgress && window.onNativePlaybackProgress($position, $duration, $seekable)",
                    null
                )
                playbackProgressHandler.postDelayed(this, 500L)
            }
        }
    }
    private var subtitleActivationTimeout: Runnable? = null
    private var pendingSubtitleRequestId: String? = null
    private var pendingSubtitleLabel = ""
    private var pendingSubtitleNotifyLocal = false
    private var subtitlePickerGeneration = 0L
    private val subtitlePickerRequestCode = 7412
    private var resumeNativePlayerAfterPause = false
    @Volatile private var nativePlayerActive = false
    @Volatile private var nativePlayerPlaying = false

    private val mediaAudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
    private val httpUserAgent by lazy {
        "SONIX-TV/1.0 (Linux; Android " + android.os.Build.VERSION.RELEASE + "; " + android.os.Build.MODEL + ") ExoPlayerLib/1.5.1"
    }
    private lateinit var xtreamBridge: XtreamBridge
    private lateinit var sonixControlClient: SonixControlClient
    @Volatile private var sonixControlPageReady = false
    @Volatile private var webTextInputFocused = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            xtreamBridge = XtreamBridge(this)
            sonixControlClient = SonixControlClient(this)
            if (xtreamBridge.hasSavedCredentials()) CatalogRefreshScheduler.ensureScheduled(this)
            val wv = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // Secure defaults: restrict arbitrary local file access from web contents.
                    // Assets loaded via file:///android_asset/ work reliably with default settings.
                    allowFileAccess = false
                    allowContentAccess = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    // IPTV video playback is handled natively by ExoPlayer rather than WebView media elements,
                    // so mixed content inside WebView is restricted to standard compatibility mode.
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url == "file:///android_asset/sonix_tv.html") {
                            sonixControlPageReady = true
                            if (::sonixControlClient.isInitialized) sonixControlClient.sync()
                        }
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        android.util.Log.e("SONIXPlayback", "WebView render process gone; didCrash=" + detail?.didCrash())
                        view?.destroy()
                        recreate()
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }
                }

                addJavascriptInterface(WebAppInterface(this@MainActivity), "AndroidBridge")
                addJavascriptInterface(xtreamBridge, "XtreamBridge")
                addJavascriptInterface(SonixControlBridge(), "SonixControlBridge")
                addJavascriptInterface(NativePlaybackBridge(), "NativePlayback")
            }

            sonixControlClient.onSync = { result ->
                val payload = JSONObject.quote(result.toString())
                wv.post { wv.evaluateJavascript("window.onSonixControlSync && window.onSonixControlSync(JSON.parse($payload))", null) }
            }

            xtreamBridge.setAsyncResultHandler { requestId, result ->
                val quotedId = JSONObject.quote(requestId)
                wv.post {
                    wv.evaluateJavascript(
                        "window.onXtreamSectionLoaded && window.onXtreamSectionLoaded($quotedId, $result)",
                        null
                    )
                }
            }

            xtreamBridge.setDetailsResultHandler { requestId, result ->
                val quotedId = JSONObject.quote(requestId)
                wv.post {
                    wv.evaluateJavascript(
                        "window.onXtreamDetailsLoaded && window.onXtreamDetailsLoaded($quotedId, $result)",
                        null
                    )
                }
            }

            webView = wv
            val themedBackground = SonixBackgroundDrawable()
            sonixBackgroundDrawable = themedBackground
            val root = FrameLayout(this).apply {
                background = themedBackground
                // Keep the native video child clipped to the application window.
                clipChildren = true
                clipToPadding = true
            }
            root.addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            val overlaySubtitleView = SubtitleView(this).apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            dedicatedSubtitleView = overlaySubtitleView
            root.addView(overlaySubtitleView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            rootContainer = root
            playbackController = PlaybackController(
                context = this@MainActivity,
                containerProvider = { rootContainer },
                uiBridge = object : PlaybackUiBridge {
                    override fun onNativePlaybackBuffering(message: String?) {
                        runOnUiThread {
                            val js = message?.let { "window.onNativePlaybackBuffering && window.onNativePlaybackBuffering(${JSONObject.quote(it)})" }
                                ?: "window.onNativePlaybackBuffering && window.onNativePlaybackBuffering()"
                            webView?.evaluateJavascript(js, null)
                        }
                    }
                    override fun onNativePlaybackReady() {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onNativePlaybackReady && window.onNativePlaybackReady()", null)
                        }
                    }
                    override fun onNativePlaybackEnded() {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onNativePlaybackEnded && window.onNativePlaybackEnded()", null)
                        }
                    }
                    override fun onNativePlaybackStateChanged(isPlaying: Boolean) {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onNativePlaybackStateChanged && window.onNativePlaybackStateChanged($isPlaying)", null)
                        }
                    }
                    override fun onNativePlaybackError(message: String) {
                        runOnUiThread {
                            val quoted = JSONObject.quote(message)
                            webView?.evaluateJavascript("window.onNativePlaybackError && window.onNativePlaybackError($quoted)", null)
                        }
                    }
                    override fun onExternalAudioBuffering(message: String?) {
                        runOnUiThread {
                            val js = message?.let { "window.onExternalAudioBuffering && window.onExternalAudioBuffering(${JSONObject.quote(it)})" }
                                ?: "window.onExternalAudioBuffering && window.onExternalAudioBuffering()"
                            webView?.evaluateJavascript(js, null)
                        }
                    }
                    override fun onExternalAudioPlaying() {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onExternalAudioPlaying && window.onExternalAudioPlaying()", null)
                        }
                    }
                    override fun onExternalAudioError(message: String) {
                        runOnUiThread {
                            val quoted = JSONObject.quote(message)
                            webView?.evaluateJavascript("window.onExternalAudioError && window.onExternalAudioError($quoted)", null)
                        }
                    }
                    override fun onExternalAudioStopped() {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onExternalAudioStopped && window.onExternalAudioStopped()", null)
                        }
                    }
                    override fun onExternalAudioDetached() {
                        runOnUiThread {
                            webView?.evaluateJavascript("window.onExternalAudioDetached && window.onExternalAudioDetached()", null)
                        }
                    }
                },
                userAgent = httpUserAgent
            )
            setContentView(root)
            wv.requestFocus()

            // Let the IME consume the first Back action while it is visible.
            // Only route Back into the web app after text editing has ended.
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dispatchNativeBack()
                }
            })

            // Load the standalone SONIX TV interface
            wv.loadUrl("file:///android_asset/sonix_tv.html")
        } catch (e: Exception) {
            android.util.Log.e("SONIXPlayback", "Error initializing MainActivity", e)
        }
    }

    private inner class SonixControlBridge {
        @android.webkit.JavascriptInterface
        fun acknowledge(messageId: String) {
            if (::sonixControlClient.isInitialized) sonixControlClient.acknowledge(messageId)
        }
        @android.webkit.JavascriptInterface
        fun setTheme(themeId: String) {
            if (::sonixControlClient.isInitialized) sonixControlClient.setTheme(themeId)
        }
        @android.webkit.JavascriptInterface
        fun setBackgroundColor(color: String) {
            val parsed = try { Color.parseColor(color) } catch (_: IllegalArgumentException) { return }
            runOnUiThread { sonixBackgroundDrawable?.setThemeBackground(parsed) }
        }
    }

    private inner class NativePlaybackBridge {
        @android.webkit.JavascriptInterface
        fun playStream(type: String, streamId: String, extension: String, left: Float, top: Float, width: Float, height: Float, pixelRatio: Float, cornerRadiusCssPx: Float) {
            runOnUiThread {
                try {
                    val mediaUri = xtreamBridge.buildPlaybackUri(type, streamId, extension)
                    startPlaybackInternal(mediaUri, left, top, width, height, pixelRatio, cornerRadiusCssPx, type, streamId.trim())
                } catch (error: Exception) {
                    stopNativePlayer()
                    val message = JSONObject.quote(error.message ?: "تعذر تجهيز رابط البث من الاشتراك")
                    webView?.evaluateJavascript(
                        "window.onNativePlaybackError && window.onNativePlaybackError(" + message + ")", null)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun play(url: String, left: Float, top: Float, width: Float, height: Float, pixelRatio: Float, cornerRadiusCssPx: Float) {
            runOnUiThread {
                try {
                    val mediaUri = Uri.parse(url.trim())
                    require((mediaUri.scheme == "http" || mediaUri.scheme == "https") && !mediaUri.host.isNullOrBlank()) {
                        "رابط البث غير صالح"
                    }
                    startPlaybackInternal(mediaUri, left, top, width, height, pixelRatio, cornerRadiusCssPx, null, null)
                } catch (error: Exception) {
                    stopNativePlayer()
                    val message = JSONObject.quote(error.message ?: "تعذر تشغيل البث")
                    webView?.evaluateJavascript(
                        "window.onNativePlaybackError && window.onNativePlaybackError(" + message + ")", null)
                }
            }
        }

        private fun startPlaybackInternal(
            mediaUri: Uri,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
            pixelRatio: Float,
            cornerRadiusCssPx: Float,
            streamType: String?,
            streamId: String?
        ) {
            // Keep one ExoPlayer and one TextureView-backed PlayerView alive across
            // item changes. Explicit stop/release requests still use stopNativePlayer().
            resetNativePlayerForNextItem()
            nativeVideoStreamType = streamType
            nativeVideoStreamId = streamId
            playbackRequestStartedAtMs = SystemClock.elapsedRealtime()
            bufferingStartedAtMs = 0L
            hasPlaybackReachedReady = false
            firstFrameLogged = false
            liveRecoveryAttempts = 0

            val container = rootContainer ?: throw IllegalStateException("واجهة المشغل غير جاهزة")
            nativePlayerCornerRadiusPx = cssPixelsToAndroidPixels(cornerRadiusCssPx.coerceIn(0f, 32f), pixelRatio).toFloat()
            val params = calculatePlayerLayoutParams(left, top, width, height, pixelRatio, container, webView)
                ?: throw IllegalStateException("مساحة معاينة الفيديو غير صالحة")

            if (activeMediaUri != mediaUri) {
                subtitleGeneration.incrementAndGet()
                finishSubtitleActivation(false, "تغير المحتوى قبل تفعيل الترجمة")
                clearActiveSubtitleOverlay()
            }
            activeMediaUri = mediaUri
            val isLiveStream = (streamType.equals("live", ignoreCase = true))
            playbackController.setCornerRadius(nativePlayerCornerRadiusPx)
            playbackController.play(
                url = mediaUri.toString(),
                streamType = if (isLiveStream) "live" else "vod"
            )

            if (params != null) {
                playbackController.updateLayoutParams(params)
            }

            playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
            playbackProgressHandler.post(playbackProgressRunnable)
        }



        @android.webkit.JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            runOnUiThread {
                immersiveVideoFullscreen = enabled
                applyVideoFullscreenSystemUi()
                val view = playbackController.getView() ?: return@runOnUiThread
                if (enabled) {
                    view.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val syncRunnable = Runnable {
                    webView?.evaluateJavascript(
                        "(function(){if(window.videoPlaybackController){window.videoPlaybackController.syncNativeBounds();}})()",
                        null
                    )
                }
                webView?.post(syncRunnable)
                webView?.postDelayed(syncRunnable, 80L)
                webView?.postDelayed(syncRunnable, 250L)
            }
        }

        @android.webkit.JavascriptInterface
        fun stop() { runOnUiThread { stopNativePlayer() } }

        @android.webkit.JavascriptInterface
        fun toggle() {
            runOnUiThread {
                if (playbackController.isPlaying()) {
                    playbackController.pause()
                } else {
                    playbackController.resume()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun isPlaying(): Boolean = playbackController.isPlaying()

        @android.webkit.JavascriptInterface
        fun isActive(): Boolean = playbackController.isActive()

        @android.webkit.JavascriptInterface
        fun pause() {
            runOnUiThread {
                playbackController.pause()
            }
        }

        @android.webkit.JavascriptInterface
        fun resume() {
            runOnUiThread {
                playbackController.resume()
            }
        }

        @android.webkit.JavascriptInterface
        fun setMuted(muted: Boolean) {
            runOnUiThread {
                playbackController.setMuted(muted)
            }
        }

        @android.webkit.JavascriptInterface
        fun seekBy(offsetMs: Long) {
            runOnUiThread {
                playbackController.seekBy(offsetMs)
            }
        }

        @android.webkit.JavascriptInterface
        fun seekTo(positionMs: Long) {
            runOnUiThread {
                playbackController.seekTo(positionMs)
            }
        }

        @android.webkit.JavascriptInterface
        fun setVideoQuality(maxHeight: Int) {
            runOnUiThread {
                playbackController.setVideoQuality(maxHeight)
            }
        }

        @android.webkit.JavascriptInterface
        fun fetchTextAsync(rawUrl: String, requestId: String) {
            subtitleIoExecutor.execute {
                val payload = try {
                    val uri = Uri.parse(rawUrl.trim())
                    require((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) { "رابط الطلب غير صالح" }
                    val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 12000
                    connection.readTimeout = 18000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "SONIX-TV/1.0")
                    connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")
                        val bytes = connection.inputStream.use { it.readBytes() }
                        require(bytes.size <= 4 * 1024 * 1024) { "استجابة الإضافة أكبر من الحد المسموح" }
                        JSONObject().put("success", true).put("body", String(bytes, Charsets.UTF_8)).toString()
                    } finally { connection.disconnect() }
                } catch (error: Exception) {
                    JSONObject().put("success", false).put("error", error.message ?: "تعذر الاتصال بمصدر الإضافة").toString()
                }
                runOnUiThread { deliverSubtitleResponse(requestId, payload) }
            }
        }

        private fun downloadSubtitlePayload(rawUrl: String): DecodedSubtitle {
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("opensubtitles://", ignoreCase = true)) {
                val fileId = trimmed.substringAfter("opensubtitles://").toLongOrNull() ?: 0L
                val pair = OpenSubtitlesClient.downloadAndDecode("", fileId = fileId)
                return DecodedSubtitle(pair.first, pair.second, "opensubtitles_$fileId.${pair.second}")
            }
            var currentUrl = trimmed
            require(currentUrl.startsWith("https://", ignoreCase = true) || currentUrl.startsWith("http://", ignoreCase = true)) {
                "رابط الترجمة غير صالح"
            }
            var redirects = 0
            val maxRedirects = 6
            while (redirects < maxRedirects) {
                val urlObj = URL(currentUrl)
                val connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "SONIX-TV/1.0")
                connection.setRequestProperty(
                    "Accept",
                    "text/plain,text/vtt,application/x-subrip,application/octet-stream,application/zip,application/gzip,*/*"
                )
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("HTTP $code without Location header")
                        currentUrl = URL(urlObj, location).toString()
                        redirects++
                        continue
                    }
                    require(code in 200..299) { "HTTP $code" }
                    val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                    require(!contentType.contains("text/html") && !contentType.contains("application/json")) {
                        "مصدر الترجمة أعاد صفحة ويب أو JSON بدلاً من ملف ترجمة"
                    }
                    val encoding = connection.contentEncoding
                    val disposition = connection.getHeaderField("Content-Disposition")
                    val rawBytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 20 * 1024 * 1024) { "ملف الترجمة أكبر من 20MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    android.util.Log.i(
                        "SONIXSubtitle",
                        "Downloaded subtitle: url=$currentUrl bytes=${rawBytes.size} encoding=$encoding disposition=$disposition"
                    )
                    return SubtitlePipeline.decodePayload(rawBytes, encoding, currentUrl, disposition)
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many redirects: $rawUrl")
        }

        @android.webkit.JavascriptInterface
        fun searchSubtitlesAsync(rawQuery: String, lang: String, requestId: String) {
            subtitleIoExecutor.execute {
                val payload = try {
                    val results = OpenSubtitlesClient.search(rawQuery, lang)
                    val jsonArray = JSONArray()
                    results.forEach { item ->
                        jsonArray.put(JSONObject().apply {
                            put("fileId", item.fileId)
                            put("name", item.name)
                            put("langName", item.langName)
                            put("iso", item.iso)
                            put("rating", item.rating)
                            put("downloads", item.downloads)
                            put("downloadUrl", item.downloadUrl ?: "opensubtitles://${item.fileId}")
                        })
                    }
                    JSONObject().put("success", true).put("results", jsonArray).toString()
                } catch (error: Exception) {
                    JSONObject().put("success", false).put("error", error.message ?: "فشل البحث").toString()
                }
                runOnUiThread { deliverSubtitleResponse(requestId, payload) }
            }
        }

        @android.webkit.JavascriptInterface
        fun applySubtitleAsync(rawUrl: String, label: String, requestId: String) {
            val requestGeneration = subtitleGeneration.incrementAndGet()
            runOnUiThread {
                if (requestGeneration == subtitleGeneration.get()) {
                    finishSubtitleActivation(false, "تم استبدال طلب الترجمة")
                }
            }
            subtitleIoExecutor.execute {
                var downloaded: File? = null
                val payload = try {
                    val decoded = downloadSubtitlePayload(rawUrl)
                    val folder = File(cacheDir, "subtitles").apply { mkdirs() }
                    File(folder, "online_${System.currentTimeMillis()}.${decoded.extension}").also { file ->
                        file.writeText(decoded.text, Charsets.UTF_8)
                        downloaded = file
                    }
                    android.util.Log.i(
                        "SONIXSubtitle",
                        "Saved subtitle file: ${downloaded?.absolutePath} size=${downloaded?.length()} ext=${decoded.extension}"
                    )
                    JSONObject().put("success", true).toString()
                } catch (error: Exception) {
                    android.util.Log.w("SONIXSubtitle", "Online subtitle download failed: rawUrl=$rawUrl", error)
                    JSONObject().put("success", false)
                        .put("error", error.message ?: "تعذر تنزيل الترجمة").toString()
                }
                runOnUiThread {
                    if (requestGeneration != subtitleGeneration.get()) {
                        deliverSubtitleResponse(requestId, JSONObject().put("success", false)
                            .put("error", "تغير المحتوى قبل اكتمال تحميل الترجمة").toString())
                    } else if (downloaded != null) {
                        attachSubtitleFile(downloaded!!, label.ifBlank { "Online subtitle" }, false, requestId, sourceFile = downloaded!!)
                    } else {
                        deliverSubtitleResponse(requestId, payload)
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun pickSubtitle() {
            runOnUiThread {
                try {
                    subtitlePickerGeneration = subtitleGeneration.incrementAndGet()
                    finishSubtitleActivation(false, "تم اختيار ملف ترجمة جديد")
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "application/x-subrip", "text/vtt", "text/plain",
                            "application/x-ass", "application/ttml+xml", "application/xml",
                            "application/gzip", "application/x-gzip"
                        ))
                    }
                    startActivityForResult(intent, subtitlePickerRequestCode)
                } catch (error: Exception) {
                    notifyLocalSubtitleResult(false, "", error.message ?: "تعذر فتح مدير الملفات")
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun hasTextTracks(): Boolean {
            return try {
                activeSubtitleFile != null
            } catch (_: Exception) { false }
        }

        @android.webkit.JavascriptInterface
        fun isSubtitlesEnabled(): Boolean {
            return activeSubtitleEnabled
        }

        @android.webkit.JavascriptInterface
        fun hasActiveSubtitle(): Boolean {
            return activeSubtitleFile != null && activeSubtitleFile!!.exists()
        }

        @android.webkit.JavascriptInterface
        fun getActiveSubtitleLabel(): String {
            return activeSubtitleLabel
        }

        @android.webkit.JavascriptInterface
        fun toggleSubtitles(): Boolean {
            val newState = !activeSubtitleEnabled
            runOnUiThread {
                setSubtitlesEnabledInternal(newState)
            }
            return newState
        }

        @android.webkit.JavascriptInterface
        fun setSubtitlesEnabled(enabled: Boolean) {
            runOnUiThread {
                setSubtitlesEnabledInternal(enabled)
            }
        }

        @android.webkit.JavascriptInterface
        fun clearSubtitle() {
            runOnUiThread {
                subtitleGeneration.incrementAndGet()
                finishSubtitleActivation(false, "تم إيقاف الترجمة")
                clearActiveSubtitleOverlay()
            }
        }

        @android.webkit.JavascriptInterface
        fun setSubtitleStyle(font: String, size: String, color: String, background: String, outline: String, position: String) {
            runOnUiThread {
                lastSubtitleFont = font
                lastSubtitleSize = size
                lastSubtitleColor = color
                lastSubtitleBackground = background
                lastSubtitleOutline = outline
                lastSubtitlePosition = position
                applySubtitleStyleToView(playbackController.getActiveSubtitleView())
            }
        }

        @android.webkit.JavascriptInterface
        fun setSubtitleDelay(offsetMs: Int) {
            runOnUiThread {
                refreshSubtitleSync(offsetMs.toLong())
            }
        }

        @android.webkit.JavascriptInterface
        fun getSubtitleDelay(): Long = subtitleDelayMs

        @android.webkit.JavascriptInterface
        fun attachExternalAudioStream(streamId: String, extension: String) {
            runOnUiThread {
                try {
                    val normalizedStreamId = streamId.trim()
                    require(normalizedStreamId.isNotBlank()) { "معرف القناة الصوتية غير صالح" }
                    require(!(nativeVideoStreamType == "live" && nativeVideoStreamId == normalizedStreamId)) {
                        "اختر قناة صوتية مختلفة عن قناة الفيديو"
                    }
                    val mediaUri = xtreamBridge.buildPlaybackUri("live", normalizedStreamId, extension)
                    playbackController.attachExternalAudio(mediaUri.toString())
                } catch (error: Exception) {
                    playbackController.detachExternalAudio()
                    val message = JSONObject.quote(error.message ?: "تعذر استخراج رابط القناة الصوتية من الاشتراك")
                    webView?.evaluateJavascript("window.onExternalAudioError && window.onExternalAudioError($message)", null)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun attachExternalAudio(url: String) { runOnUiThread { playbackController.attachExternalAudio(url) } }

        @android.webkit.JavascriptInterface
        fun detachExternalAudio() { runOnUiThread { playbackController.detachExternalAudio() } }

        @android.webkit.JavascriptInterface
        fun setExternalAudioSyncOffset(offsetMs: Int) {
            runOnUiThread { playbackController.setExternalAudioSyncOffset(offsetMs.toLong()) }
        }

        @android.webkit.JavascriptInterface
        fun setExternalAudioVolume(volume: Float) {
            runOnUiThread { playbackController.setExternalAudioVolume(volume) }
        }

        @android.webkit.JavascriptInterface
        fun pauseExternalAudio() { runOnUiThread { playbackController.pauseExternalAudio() } }

        @android.webkit.JavascriptInterface
        fun resumeExternalAudio() {
            runOnUiThread { playbackController.resumeExternalAudio() }
        }

        @android.webkit.JavascriptInterface
        fun isExternalAudioActive(): Boolean = playbackController.isExternalAudioActive()

        @android.webkit.JavascriptInterface
        fun resize(left: Float, top: Float, width: Float, height: Float, pixelRatio: Float, cornerRadiusCssPx: Float) {
            runOnUiThread {
                val container = rootContainer ?: return@runOnUiThread
                nativePlayerCornerRadiusPx = cssPixelsToAndroidPixels(cornerRadiusCssPx.coerceIn(0f, 32f), pixelRatio).toFloat()
                playbackController.setCornerRadius(nativePlayerCornerRadiusPx)
                val params = calculatePlayerLayoutParams(left, top, width, height, pixelRatio, container, webView)
                val view = playbackController.getView()
                if (params == null) {
                    view?.visibility = android.view.View.INVISIBLE
                    return@runOnUiThread
                }
                params.gravity = Gravity.TOP or Gravity.LEFT
                view?.visibility = android.view.View.VISIBLE
                playbackController.updateLayoutParams(params)
            }
        }

        @android.webkit.JavascriptInterface
        fun getVideoEngine(): String {
            return playbackController.settings.getSelectedEngine().id
        }

        @android.webkit.JavascriptInterface
        fun setVideoEngine(engineId: String): String {
            val target = VideoEngineType.fromId(engineId)
            playbackController.settings.setSelectedEngine(target)
            SonixDiagnosticLog.logEngineSwitch(
                fromEngine = playbackController.settings.getSelectedEngine(),
                toEngine = target,
                reason = "User selected engine from settings UI"
            )
            return target.id
        }

        @android.webkit.JavascriptInterface
        fun isMpvAvailable(): Boolean {
            return playbackController.settings.isMpvAvailable()
        }
    }

    private fun applyVideoFullscreenSystemUi() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersiveVideoFullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun deliverSubtitleResponse(requestId: String, payload: String) {
        val js = "window.SonixSubtitles && window.SonixSubtitles.onNativeResponse(" +
            JSONObject.quote(requestId) + "," + JSONObject.quote(payload) + ")"
        webView?.evaluateJavascript(js, null)
    }

    private fun loadSubtitleTypeface(font: String): Typeface? {
        subtitleTypefaceCache[font]?.let { return it }
        val source = when (font) {
            "Reem Kufi" -> "arabic-fonts-01.b64" to "reem_kufi"
            "Amiri" -> "arabic-fonts-01.b64" to "amiri"
            "Aref Ruqaa" -> "arabic-fonts-02.b64" to "aref_ruqaa"
            "Lalezar" -> "arabic-fonts-02.b64" to "lalezar"
            "Marhey" -> "arabic-fonts-03.b64" to "marhey"
            "Rakkas" -> "arabic-fonts-03.b64" to "rakkas"
            "Changa" -> "arabic-fonts-04.b64" to "changa"
            "Noto Naskh" -> "arabic-fonts-04.b64" to "noto_naskh_arabic"
            "Lemonada" -> "arabic-fonts-05.b64" to "lemonada"
            "Noto Kufi" -> "arabic-fonts-05.b64" to "noto_kufi_arabic"
            else -> return null
        }
        return try {
            val lines = assets.open("subtitle-fonts/" + source.first).bufferedReader().use { it.readLines() }
            val marker = "@@" + source.second + "@@"
            val index = lines.indexOf(marker)
            if (index < 0 || index + 1 >= lines.size) return null
            val bytes = android.util.Base64.decode(lines[index + 1], android.util.Base64.DEFAULT)
            val directory = File(cacheDir, "subtitle-fonts").apply { mkdirs() }
            val file = File(directory, source.second + ".ttf")
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                FileOutputStream(file).use { it.write(bytes) }
            }
            Typeface.createFromFile(file).also { subtitleTypefaceCache[font] = it }
        } catch (error: Exception) {
            android.util.Log.w("SONIXSubtitle", "Could not load subtitle font $font", error)
            null
        }
    }

    private fun normalizeSubtitleExtension(rawNameOrUrl: String): String =
        SubtitlePipeline.normalizeExtension(rawNameOrUrl)

    private fun applySubtitleStyleToView(subtitleView: SubtitleView?) {
        val targets = listOfNotNull(subtitleView, dedicatedSubtitleView).distinct()
        if (targets.isEmpty()) return
        val foreground = try { android.graphics.Color.parseColor(lastSubtitleColor) } catch (_: Exception) { android.graphics.Color.WHITE }
        val backgroundColor = when (lastSubtitleBackground) {
            "translucent" -> android.graphics.Color.argb(150, 0, 0, 0)
            "solid" -> android.graphics.Color.BLACK
            else -> android.graphics.Color.TRANSPARENT
        }
        val edgeType = when (lastSubtitleOutline) {
            "outline" -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        val family = when (lastSubtitleFont) {
            "Aref Ruqaa", "Amiri", "Noto Naskh" -> "serif"
            "Lalezar", "Changa", "Noto Kufi" -> "sans-serif-condensed"
            "Marhey", "Rakkas", "Lemonada" -> "casual"
            else -> "sans-serif"
        }
        val typeface = loadSubtitleTypeface(lastSubtitleFont) ?: Typeface.create(family, Typeface.BOLD)
        val textSize = lastSubtitleSize.toFloatOrNull()?.coerceIn(14f, 42f) ?: 24f
        val bottomPadding = when (lastSubtitlePosition) {
            "raised" -> 0.18f
            "middle" -> 0.42f
            else -> 0.08f
        }
        for (view in targets) {
            view.setApplyEmbeddedStyles(false)
            view.setStyle(CaptionStyleCompat(
                foreground, backgroundColor, android.graphics.Color.TRANSPARENT,
                edgeType, android.graphics.Color.BLACK, typeface
            ))
            view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            view.setBottomPaddingFraction(bottomPadding)
        }
    }

    private fun prepareExternalSubtitleWithSync(sourceFile: File, offsetMs: Long): File {
        if (offsetMs == 0L) return sourceFile
        val extension = sourceFile.extension.lowercase(Locale.ROOT)
        if (extension != "srt" && extension != "vtt") return sourceFile
        val folder = File(cacheDir, "subtitles").apply { mkdirs() }
        val target = File(folder, "synced_${System.currentTimeMillis()}.$extension")
        val sourceText = sourceFile.readText(Charsets.UTF_8)
        val shifted = SubtitlePipeline.applyDelay(sourceText, extension, offsetMs)
        target.writeText(shifted, Charsets.UTF_8)
        return target
    }

    private fun refreshSubtitleSync(newOffsetMs: Long) {
        subtitleDelayMs = newOffsetMs
        if (!playbackController.isActive() || activeSubtitleCues.isEmpty()) return
        // Re-render immediately at the adjusted media position. Do not re-download,
        // rewrite, or re-parse the active subtitle when the user moves the delay control.
        subtitleCueCursor = 0
        lastSubtitlePositionMs = 0L
        lastRenderedSubtitleText = null
        subtitleRenderHandler.removeCallbacks(subtitleRenderRunnable)
        subtitleRenderHandler.post(subtitleRenderRunnable)
        android.util.Log.i("SONIXSubtitle", "Subtitle render-time delay updated: offsetMs=$newOffsetMs")
    }

    private fun detectLanguageFromLabelOrName(label: String, fileName: String): String {
        val text = "$label $fileName".lowercase(Locale.ROOT)
        return when {
            text.contains("ara") || text.contains("عرب") -> "ar"
            text.contains("eng") || text.contains("انجليز") -> "en"
            text.contains("hin") || text.contains("هند") -> "hi"
            text.contains("urd") || text.contains("اردو") -> "ur"
            text.contains("fre") || text.contains("fra") || text.contains("فرنس") -> "fr"
            text.contains("spa") || text.contains("اسبان") -> "es"
            text.contains("ger") || text.contains("deu") || text.contains("المان") -> "de"
            text.contains("ita") || text.contains("ايطال") -> "it"
            text.contains("tur") || text.contains("ترك") -> "tr"
            text.contains("per") || text.contains("fas") || text.contains("فارس") -> "fa"
            else -> "und"
        }
    }

    private fun setSubtitlesEnabledInternal(enabled: Boolean) {
        activeSubtitleEnabled = enabled
        if (!enabled) {
            playbackController.getActiveSubtitleView()?.setCues(emptyList())
            dedicatedSubtitleView?.setCues(emptyList())
            lastRenderedSubtitleText = null
        } else {
            lastRenderedSubtitleText = null
            subtitleRenderHandler.removeCallbacks(subtitleRenderRunnable)
            subtitleRenderHandler.post(subtitleRenderRunnable)
        }
        notifySubtitleStateChanged(
            activeSubtitleEnabled,
            activeSubtitleLabel,
            activeSubtitleFile != null && activeSubtitleFile!!.exists()
        )
    }

    private fun toggleSubtitlesInternal(): Boolean {
        val newState = !activeSubtitleEnabled
        setSubtitlesEnabledInternal(newState)
        return newState
    }

    private fun notifySubtitleStateChanged(enabled: Boolean, label: String, hasTrack: Boolean) {
        val js = "window.SonixSubtitles && window.SonixSubtitles.onStateChanged && window.SonixSubtitles.onStateChanged(" +
            enabled + "," + JSONObject.quote(label) + "," + hasTrack + ")"
        webView?.evaluateJavascript(js, null)
    }

    /**
     * Activates an external subtitle as a lightweight timed-cue overlay.
     *
     * Do not add the subtitle through MediaItem.SubtitleConfiguration here:
     * calling setMediaItem()/prepare() on the active live item tears down and
     * reopens the stream, which causes a long BUFFERING stall for IPTV sources.
     * The subtitle is parsed off the UI thread and rendered by PlayerView's
     * SubtitleView without touching the active MediaItem or ExoPlayer timeline.
     */
    private fun attachSubtitleFile(
        file: File,
        label: String,
        notify: Boolean = true,
        requestId: String? = null,
        sourceFile: File = file
    ) {
        val mediaUri = activeMediaUri
        if (!playbackController.isActive() || mediaUri == null) {
            val error = "شغّل الفيديو أولاً قبل إضافة الترجمة"
            if (requestId != null) {
                deliverSubtitleResponse(requestId, JSONObject().put("success", false).put("error", error).toString())
            } else if (notify) notifyLocalSubtitleResult(false, label, error)
            return
        }
        if (!file.exists() || file.length() <= 0L) {
            val error = "ملف الترجمة غير موجود أو فارغ"
            if (requestId != null) {
                deliverSubtitleResponse(requestId, JSONObject().put("success", false).put("error", error).toString())
            } else if (notify) notifyLocalSubtitleResult(false, label, error)
            return
        }
        finishSubtitleActivation(false, "تم استبدال طلب الترجمة")
        val activationGeneration = subtitleGeneration.incrementAndGet()
        pendingSubtitleRequestId = requestId
        pendingSubtitleLabel = label
        pendingSubtitleNotifyLocal = notify
        android.util.Log.i(
            "SONIXSubtitle",
            "Preparing external subtitle off playback thread: label='$label' size=${file.length()} delayMs=$subtitleDelayMs"
        )
        subtitleIoExecutor.execute {
            try {
                val workingFile = file
                val cues = parseSubtitleFile(workingFile)
                require(cues.isNotEmpty()) {
                    "لم يتم العثور على توقيتات ترجمة صالحة داخل الملف"
                }

                runOnUiThread {
                    if (activationGeneration != subtitleGeneration.get() ||
                        !playbackController.isActive() || mediaUri != activeMediaUri) {
                        finishSubtitleActivation(false, "تغير الفيديو قبل اكتمال تفعيل الترجمة")
                        return@runOnUiThread
                    }

                    activeSubtitleSourceFile = sourceFile
                    activeSubtitleFile = workingFile
                    activeSubtitleLabel = label
                    activeSubtitleEnabled = true
                    activeSubtitleCues = cues
                    subtitleCueCursor = 0
                    lastSubtitlePositionMs = 0L
                    lastRenderedSubtitleText = null
                    subtitleRenderHandler.removeCallbacks(subtitleRenderRunnable)
                    playbackController.getActiveSubtitleView()?.let { applySubtitleStyleToView(it) }
                    subtitleRenderHandler.post(subtitleRenderRunnable)

                    android.util.Log.i(
                        "SONIXSubtitle",
                        "External subtitle activated as overlay: cues=${cues.size} file=${workingFile.name}; playback item unchanged"
                    )
                    notifySubtitleStateChanged(true, activeSubtitleLabel, true)
                    finishSubtitleActivation(true, "")
                }
            } catch (error: Exception) {
                android.util.Log.e("SONIXSubtitle", "External subtitle parsing/activation failed", error)
                runOnUiThread {
                    if (activationGeneration == subtitleGeneration.get()) {
                        finishSubtitleActivation(false, error.message ?: "تعذر تفعيل الترجمة")
                    }
                }
            }
        }
    }

    private fun finishSubtitleActivation(success: Boolean, message: String) {
        subtitleActivationTimeout?.let(subtitleActivationHandler::removeCallbacks)
        subtitleActivationTimeout = null
        val requestId = pendingSubtitleRequestId
        val label = pendingSubtitleLabel
        val notifyLocal = pendingSubtitleNotifyLocal
        pendingSubtitleRequestId = null
        pendingSubtitleLabel = ""
        pendingSubtitleNotifyLocal = false
        if (requestId != null) {
            val response = JSONObject().put("success", success)
            if (!success) {
                response.put("stage", "activation")
                response.put("error", message.ifBlank { "تعذر تفعيل الترجمة" })
            }
            deliverSubtitleResponse(requestId, response.toString())
        } else if (notifyLocal && label.isNotBlank()) {
            notifyLocalSubtitleResult(success, label, message)
        }
    }

    private fun clearActiveSubtitleOverlay() {
        subtitleRenderHandler.removeCallbacks(subtitleRenderRunnable)
        activeSubtitleFile = null
        activeSubtitleSourceFile = null
        activeSubtitleLabel = ""
        activeSubtitleEnabled = false
        activeSubtitleCues = emptyList()
        subtitleCueCursor = 0
        lastSubtitlePositionMs = 0L
        lastRenderedSubtitleText = null
        subtitleDelayMs = 0L
        playbackController.getActiveSubtitleView()?.setCues(emptyList())
        dedicatedSubtitleView?.setCues(emptyList())
        notifySubtitleStateChanged(false, "", false)
    }

    private fun parseSubtitleFile(file: File): List<TimedSubtitleCue> {
        val source = file.readText(Charsets.UTF_8)
        return SubtitlePipeline.parse(source, file.extension)
    }

    private fun notifyLocalSubtitleResult(success: Boolean, label: String, message: String) {
        val js = "window.SonixSubtitles && window.SonixSubtitles.onLocalSubtitleResult(" +
            success + "," + JSONObject.quote(label) + "," + JSONObject.quote(message) + ")"
        webView?.evaluateJavascript(js, null)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != subtitlePickerRequestCode || resultCode != RESULT_OK) return
        if (subtitlePickerGeneration != subtitleGeneration.get()) {
            notifyLocalSubtitleResult(false, "", "تغير المحتوى قبل اختيار ملف الترجمة")
            return
        }
        val uri = data?.data ?: return
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "subtitle.srt"
            val rawBytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20 * 1024 * 1024) { "ملف الترجمة أكبر من 20MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalStateException("تعذر قراءة الملف")
            val decoded = SubtitlePipeline.decodePayload(rawBytes, null, name)
            val folder = File(cacheDir, "subtitles").apply { mkdirs() }
            val file = File(folder, "local_${System.currentTimeMillis()}.${decoded.extension}")
            file.writeText(decoded.text, Charsets.UTF_8)
            attachSubtitleFile(file, name.substringBeforeLast('.', name), notify = true, requestId = null, sourceFile = file)
        } catch (error: Exception) {
            notifyLocalSubtitleResult(false, "", error.message ?: "تعذر قراءة ملف الترجمة")
        }
    }

    private fun inspectUhdCodecCapabilities(format: Format) {
        val mimeType = format.sampleMimeType ?: return
        val width = format.width
        val height = format.height
        if (width <= 0 || height <= 0) return
        val frameRate = format.frameRate.toDouble()
        val results = mutableListOf<String>()
        try {
            val codecInfos = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
            for (codecInfo in codecInfos) {
                if (codecInfo.isEncoder) continue
                val supportedType = codecInfo.supportedTypes.firstOrNull { it.equals(mimeType, ignoreCase = true) } ?: continue
                try {
                    val videoCapabilities = codecInfo.getCapabilitiesForType(supportedType).videoCapabilities ?: continue
                    val supportsFormat = if (frameRate > 0.0) {
                        videoCapabilities.areSizeAndRateSupported(width, height, frameRate)
                    } else {
                        videoCapabilities.isSizeSupported(width, height)
                    }
                    val acceleration = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        "hardware=" + codecInfo.isHardwareAccelerated
                    } else {
                        "hardware=unknown"
                    }
                    results += codecInfo.name + "(supports=" + supportsFormat + "," + acceleration + ")"
                } catch (error: Exception) {
                    android.util.Log.w("SONIXPlayback", "Could not inspect codec " + codecInfo.name, error)
                }
            }
            val supportedCount = results.count { it.contains("supports=true") }
            android.util.Log.i(
                "SONIXPlayback",
                "UHD_CODEC_SCAN mime=" + mimeType + " size=" + width + "x" + height +
                    " fps=" + frameRate + " supportedDecoders=" + supportedCount +
                    " candidates=" + results.joinToString()
            )
        } catch (error: Exception) {
            android.util.Log.w("SONIXPlayback", "UHD codec capability scan failed", error)
        }
    }

    private fun resetNativePlayerForNextItem() {
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        playbackWatchdogRunnable?.let(playbackRecoveryHandler::removeCallbacks)
        playbackWatchdogRunnable = null
        liveRecoveryRunnable?.let(playbackRecoveryHandler::removeCallbacks)
        liveRecoveryRunnable = null
        bufferingStartedAtMs = 0L
        hasPlaybackReachedReady = false
        liveRecoveryAttempts = 0
        subtitleGeneration.incrementAndGet()
        finishSubtitleActivation(false, "تم تغيير المحتوى قبل تفعيل الترجمة")
        playbackController.detachExternalAudio()
        activeMediaUri = null
        activeSubtitleFile = null
        nativePlayerActive = false
        nativePlayerPlaying = false
        nativeVideoStreamType = null
        nativeVideoStreamId = null
        playbackController.stop()
        webView?.evaluateJavascript(
            "window.onNativePlaybackStateChanged && window.onNativePlaybackStateChanged(false)", null)
    }

    private fun notifyNativePlaybackError(message: String) {
        val quoted = JSONObject.quote(message)
        webView?.evaluateJavascript(
            "window.onNativePlaybackError && window.onNativePlaybackError(" + quoted + ")", null)
    }

    private fun stopNativePlayer() {
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        playbackWatchdogRunnable?.let(playbackRecoveryHandler::removeCallbacks)
        playbackWatchdogRunnable = null
        liveRecoveryRunnable?.let(playbackRecoveryHandler::removeCallbacks)
        liveRecoveryRunnable = null
        bufferingStartedAtMs = 0L
        hasPlaybackReachedReady = false
        liveRecoveryAttempts = 0
        subtitleGeneration.incrementAndGet()
        finishSubtitleActivation(false, "توقف تشغيل المحتوى قبل تفعيل الترجمة")
        clearActiveSubtitleOverlay()
        playbackController.detachExternalAudio()
        activeMediaUri = null
        nativePlayerActive = false
        nativePlayerPlaying = false
        nativeVideoStreamType = null
        nativeVideoStreamId = null
        playbackController.stop()

        nativePlayerView?.let { view ->
            view.player = null
            rootContainer?.removeView(view)
        }
        nativePlayerView = null
        nativePlayer = null

        webView?.evaluateJavascript(
            "window.onNativePlaybackStateChanged && window.onNativePlaybackStateChanged(false)", null)
    }

    class WebAppInterface(private val activity: MainActivity) {
        @android.webkit.JavascriptInterface
        fun openExternalUrl(rawUrl: String) {
            activity.runOnUiThread {
                try {
                    val uri = Uri.parse(rawUrl.trim())
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                } catch (error: Exception) {
                    android.util.Log.w("SONIXAddons", "Unable to open addon configuration URL", error)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun setTextInputFocused(focused: Boolean) {
            activity.webTextInputFocused = focused
        }

        @android.webkit.JavascriptInterface
        fun closeApp() {
            activity.runOnUiThread {
                activity.finish()
            }
        }
    }

    private fun isSoftwareKeyboardVisible(): Boolean {
        val wv = webView ?: return false
        return ViewCompat.getRootWindowInsets(wv)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun dispatchNativeBack() {
        val wv = webView ?: return
        if (isSoftwareKeyboardVisible()) {
            WindowInsetsControllerCompat(window, wv).hide(WindowInsetsCompat.Type.ime())
            return
        }
        wv.evaluateJavascript(
            "(function() { if (window.handleAndroidBack) { window.handleAndroidBack(); } else if (window.remoteNavigator) { window.remoteNavigator.handleBackAction(); } })()",
            null
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val wv = webView ?: return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        val action = event.action

        // 1. When an editable text input is focused in the web view:
        if (webTextInputFocused) {
            // Allow vertical D-pad navigation to smoothly move between fields
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (action == KeyEvent.ACTION_DOWN) {
                    dispatchWebKey("ArrowDown")
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (action == KeyEvent.ACTION_DOWN) {
                    dispatchWebKey("ArrowUp")
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return wv.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (action == KeyEvent.ACTION_UP) {
                    dispatchNativeBack()
                }
                return true
            }

            // Route typing keys (alphanumeric, symbols, space, backspace, etc.) to webView
            if (wv.dispatchKeyEvent(event)) {
                return true
            }

            // CRITICAL TV FIX: Always consume numeric keys (0-9) and standard keyboard characters
            // while in text editing mode so Android TV OS NEVER interprets them as TV tuner channel switches!
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ||
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_PERIOD ||
                keyCode == KeyEvent.KEYCODE_MINUS ||
                keyCode == KeyEvent.KEYCODE_SLASH ||
                keyCode == KeyEvent.KEYCODE_SEMICOLON ||
                keyCode == KeyEvent.KEYCODE_AT) {
                return true
            }
        }

        // 2. Standard Android TV Remote Control Navigation (outside text input):
        if (action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    dispatchWebKey("ArrowUp")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dispatchWebKey("ArrowDown")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    dispatchWebKey("ArrowLeft")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    dispatchWebKey("ArrowRight")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    dispatchWebKey("Enter")
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    dispatchNativeBack()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    dispatchWebKey("Menu")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    dispatchWebKey("MediaPlayPause")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    dispatchWebKey("MediaPlay")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    dispatchWebKey("MediaPause")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    dispatchWebKey("MediaFastForward")
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    dispatchWebKey("MediaRewind")
                    return true
                }
            }
        } else if (action == KeyEvent.ACTION_UP) {
            // Consume ACTION_UP for remote keys to prevent double-press or system defaults
            if (keyCode in setOf(
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_MEDIA_REWIND
                )) {
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun dispatchWebKey(keyName: String) {
        val wv = webView ?: return
        val js = "if(window.handleRemoteKey){window.handleRemoteKey('$keyName');}else{var ev=new KeyboardEvent('keydown',{key:'$keyName',code:'$keyName',bubbles:true,cancelable:true});window.dispatchEvent(ev);}"
        wv.evaluateJavascript(js, null)
    }

    override fun onResume() {
        super.onResume()
        if (immersiveVideoFullscreen) window.decorView.post { applyVideoFullscreenSystemUi() }
        webView?.onResume()
        if (::sonixControlClient.isInitialized && sonixControlPageReady) sonixControlClient.sync()
        if (resumeNativePlayerAfterPause) playbackController.resume()
        resumeNativePlayerAfterPause = false
    }

    override fun onPause() {
        super.onPause()
        webView?.evaluateJavascript(
            "(function() { if (window.videoPlaybackController) { window.videoPlaybackController.pause(); } })()",
            null
        )
        resumeNativePlayerAfterPause = playbackController.isPlaying()
        playbackController.pause()
        webView?.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveVideoFullscreen) {
            window.decorView.post { applyVideoFullscreenSystemUi() }
        }
    }

    override fun onDestroy() {
        immersiveVideoFullscreen = false
        applyVideoFullscreenSystemUi()
        playbackController.release()
        stopNativePlayer()
        if (::sonixControlClient.isInitialized) sonixControlClient.shutdown()
        if (::xtreamBridge.isInitialized) xtreamBridge.shutdown()
        subtitleIoExecutor.shutdownNow()
        codecDiagnosticsExecutor.shutdownNow()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}


/** Native equivalent of the SONIX screen gradients, visible behind the transparent WebView. */
private class SonixBackgroundDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startColor = Color.rgb(11, 17, 24)
    private var endColor = Color.rgb(26, 36, 48)

    fun setThemeBackground(color: Int) {
        startColor = color
        endColor = Color.rgb(
            (Color.red(color) + 18).coerceAtMost(255),
            (Color.green(color) + 18).coerceAtMost(255),
            (Color.blue(color) + 18).coerceAtMost(255)
        )
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val width = area.width().toFloat().coerceAtLeast(1f)
        val height = area.height().toFloat().coerceAtLeast(1f)
        paint.shader = LinearGradient(
            area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(),
            startColor, endColor, Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradient(
            area.left + width * 0.85f, area.top + height * 0.15f,
            maxOf(width, height) * 0.48f,
            intArrayOf(Color.argb(46, 184, 115, 51), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = RadialGradient(
            area.left + width * 0.15f, area.top + height * 0.85f,
            maxOf(width, height) * 0.48f,
            intArrayOf(Color.argb(56, 31, 111, 107), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}


internal fun externalAudioRetryDelayMs(attempt: Int): Long {
    val delays = longArrayOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
    return delays[(attempt.coerceAtLeast(1) - 1).coerceAtMost(delays.lastIndex)]
}


internal fun cssPixelsToAndroidPixels(value: Float, pixelRatio: Float): Int =
    (value * pixelRatio).roundToInt()

internal fun calculatePlayerLayoutParams(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    pixelRatio: Float,
    root: FrameLayout?,
    web: WebView?
): FrameLayout.LayoutParams? {
    if (root == null) return null
    val rootWidth = root.width
    val rootHeight = root.height
    if (rootWidth <= 0 || rootHeight <= 0) return null

    var webOffsetX = 0
    var webOffsetY = 0
    if (web != null) {
        val webLocation = IntArray(2)
        val rootLocation = IntArray(2)
        web.getLocationOnScreen(webLocation)
        root.getLocationOnScreen(rootLocation)
        webOffsetX = webLocation[0] - rootLocation[0]
        webOffsetY = webLocation[1] - rootLocation[1]
    }

    val rect = PreviewLayoutCalculator.calculate(
        left, top, width, height, pixelRatio, rootWidth, rootHeight, webOffsetX, webOffsetY
    ) ?: return null

    return FrameLayout.LayoutParams(rect.width, rect.height).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        leftMargin = rect.left
        topMargin = rect.top
    }
}
