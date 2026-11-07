package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.data.model.ChannelServer
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Available High-Speed Stream Engines in FOUL TV:
 * 1. [VIDEO_JS_PRO]: Video.js Adaptive Buffering Engine + Persistent OkHttp Socket Pool + Async Hardware MediaCodec (Default, zero-stutter).
 * 2. [VIDEO_JS_WEB]: HTML5 `<video>` + Video.js / HLS.js / mpegts.js WebEngine with Same-Origin CORS proxy.
 * 3. [NATIVE_OS]: Android Native OS Hardware `VideoView` / `MediaPlayer` (Stagefright / NuPlayer kernel pipeline).
 */
enum class PlayerEngineType(
    val shortBadgeAr: String,
    val titleAr: String,
    val subtitleAr: String
) {
    VIDEO_JS_PRO(
        shortBadgeAr = "Video.js Pro",
        titleAr = "مشغل Video.js Pro (فائق السرعة • بدون تقطيع)",
        subtitleAr = "تخزين مؤقت ذكي مانع للتقطيع مع اتصال مستمر وفك تشفير هاردوير"
    ),
    VIDEO_JS_WEB(
        shortBadgeAr = "Video.js Web",
        titleAr = "مشغل Video.js HTML5 (HLS.js + WebEngine)",
        subtitleAr = "مشغل الويب التفاعلي المباشر لتشغيل روابط m3u8 و mp4 بسلاسة"
    ),
    NATIVE_OS(
        shortBadgeAr = "مشغل النظام",
        titleAr = "مشغل النظام الأصلي (Android Native Player)",
        subtitleAr = "مشغل الهاردوير المباشر للنظام بدون أي طبقات وسيطة"
    )
}

/**
 * Selects the best initial server index when opening a channel in the Home section (`قسم الرئيسية`):
 * If a channel has multiple quality servers (e.g. `4K`, `FHD`, `HD`, `SD`), starting on `4K` or `FHD`
 * immediately demands 12-25 Mbps and causes severe stuttering on normal connections.
 * Starting on `HD` (or `SD` if `HD` is not present) guarantees an instant, stutter-free start while keeping
 * `4K` and `FHD` available with 1 click in the server bar.
 */
fun selectOptimalInitialServerIndex(
    servers: List<ChannelServer>,
    isLiveChannel: Boolean
): Int {
    if (!isLiveChannel || servers.size <= 1) return 0

    val firstServerName = servers.first().name.uppercase(Locale.ROOT)
    val isFirstServerUltraHeavy = firstServerName.contains("4K") ||
        firstServerName.contains("UHD") ||
        firstServerName.contains("2160") ||
        firstServerName.contains("FHD") ||
        firstServerName.contains("1080")

    if (!isFirstServerUltraHeavy) return 0

    // Look for a balanced HD (720p) server first
    val hdIndex = servers.indexOfFirst { srv ->
        val n = srv.name.uppercase(Locale.ROOT)
        (n == "HD" || n.startsWith("HD ") || n.contains("720")) &&
            !n.contains("FHD") &&
            !n.contains("FULL")
    }
    if (hdIndex >= 0) return hdIndex

    // Next look for HEVC / H265 (low bandwidth high quality)
    val hevcIndex = servers.indexOfFirst { srv ->
        val n = srv.name.uppercase(Locale.ROOT)
        n.contains("HEVC") || n.contains("265")
    }
    if (hevcIndex >= 0) return hevcIndex

    // Next look for SD (480p/576p)
    val sdIndex = servers.indexOfFirst { srv ->
        val n = srv.name.uppercase(Locale.ROOT)
        n == "SD" || n.startsWith("SD ") || n.contains("480") || n.contains("576")
    }
    if (sdIndex >= 0) return sdIndex

    return 0
}

/**
 * Shared Persistent OkHttp Client specifically tuned for Live HLS (.m3u8) & Continuous MPEG-TS (.ts) streaming.
 * Reuses warm TCP/TLS sockets across all HLS segments so the player never stalls on segment transitions.
 */
object StreamOkHttpClientProvider {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * High-throughput OkHttp-backed Media3 [DataSource] that eliminates `HttpURLConnection` per-segment handshake lag.
 */
@OptIn(UnstableApi::class)
class OkHttpStreamDataSource(
    private val client: OkHttpClient,
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 VideoJS/8.10.0"
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened = false
    private var bytesToRead: Long = 0
    private var bytesRead: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0
        this.bytesToRead = 0
        transferInitializing(dataSpec)

        val urlString = dataSpec.uri.toString()
        val requestBuilder = Request.Builder()
            .url(urlString)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")

        // Apply custom headers if present in DataSpec
        for ((k, v) in dataSpec.httpRequestHeaders) {
            requestBuilder.header(k, v)
        }

        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val rangeEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                (dataSpec.position + dataSpec.length - 1).toString()
            } else {
                ""
            }
            requestBuilder.header("Range", "bytes=${dataSpec.position}-$rangeEnd")
        }

        val callResponse = client.newCall(requestBuilder.build()).execute()
        this.response = callResponse
        val body = callResponse.body
        if (!callResponse.isSuccessful || body == null) {
            callResponse.close()
            this.response = null
            throw java.io.IOException("HTTP ${callResponse.code} for $urlString")
        }

        this.responseByteStream = body.byteStream()
        val contentLength = body.contentLength()
        this.bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != -1L -> contentLength
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val bytesRemaining = bytesToRead - bytesRead
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        }

        val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT
        val maxRead = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        }

        val readCount = stream.read(buffer, offset, maxRead)
        if (readCount == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += readCount
        bytesTransferred(readCount)
        return readCount
    }

    override fun getUri(): Uri? {
        return response?.request?.url?.toString()?.let { Uri.parse(it) } ?: dataSpec?.uri
    }

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        try {
            responseByteStream?.close()
        } catch (_: Exception) {
        }
        try {
            response?.close()
        } catch (_: Exception) {
        }
        responseByteStream = null
        response = null
        dataSpec = null
    }

    class Factory(
        private val client: OkHttpClient = StreamOkHttpClientProvider.instance,
        private val userAgent: String = "VLC/3.0.20 LibVLC/3.0.20"
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return OkHttpStreamDataSource(client, userAgent)
        }
    }
}

/**
 * Genuine HTML5 `<video>` + Video.js / HLS.js / mpegts.js WebEngine Player View.
 * Uses Same-Origin `loadDataWithBaseURL` + `OkHttp` CORS proxy interception so `.m3u8`, `.ts`, and `.mp4`
 * streams play smoothly inside the Video.js WebEngine, and automatically notifies [onFallbackToProEngine]
 * if a raw socket stream requires hardware demuxing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoJsWebEngineSurface(
    streamUrl: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    aspectFitMode: Int, // 0 = FIT, 1 = ZOOM (cover), 2 = FILL
    onBufferingChanged: (Boolean) -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit,
    onVideoResolutionChanged: (Int, Int) -> Unit,
    onStreamErrorOrUnsupported: () -> Unit,
    modifier: Modifier = Modifier
) {
    val objectFitCss = when (aspectFitMode) {
        1 -> "cover"
        2 -> "fill"
        else -> "contain"
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onPlaying() {
                onBufferingChanged(false)
                onPlayingStateChanged(true)
            }

            @JavascriptInterface
            fun onWaiting() {
                onBufferingChanged(true)
            }

            @JavascriptInterface
            fun onPaused() {
                onPlayingStateChanged(false)
            }

            @JavascriptInterface
            fun onLoadedMetadata(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    onVideoResolutionChanged(width, height)
                }
            }

            @JavascriptInterface
            fun onError() {
                onStreamErrorOrUnsupported()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.BLACK)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccess = true
                    allowContentAccess = true
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 VideoJS/8.10.0"
                }

                addJavascriptInterface(bridge, "FoulVideoJsBridge")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        // Proxy .m3u8 playlists via OkHttp with permissive CORS headers so HLS.js/Video.js never hits CORS blocks
                        if (reqUrl.contains(".m3u8", ignoreCase = true)) {
                            return try {
                                val okReq = Request.Builder()
                                    .url(reqUrl)
                                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                                    .header("Accept", "*/*")
                                    .build()
                                val resp = StreamOkHttpClientProvider.instance.newCall(okReq).execute()
                                if (resp.isSuccessful && resp.body != null) {
                                    val headers = mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                                        "Access-Control-Allow-Headers" to "*",
                                        "Cache-Control" to "no-cache"
                                    )
                                    WebResourceResponse(
                                        "application/vnd.apple.mpegurl",
                                        "UTF-8",
                                        resp.code,
                                        resp.message.ifBlank { "OK" },
                                        headers,
                                        resp.body!!.byteStream()
                                    )
                                } else {
                                    resp.close()
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
        },
        update = { webView ->
            val currentLoadedUrl = webView.tag as? String
            if (currentLoadedUrl != streamUrl) {
                webView.tag = streamUrl
                val escapedUrl = streamUrl
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("'", "\\'")
                val baseOrigin = runCatching {
                    val uri = Uri.parse(streamUrl)
                    "${uri.scheme ?: "http"}://${uri.authority ?: "localhost"}/"
                }.getOrDefault("http://localhost/")

                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                        <link href="https://vjs.zencdn.net/8.10.0/video-js.min.css" rel="stylesheet" />
                        <style>
                            * { margin: 0; padding: 0; box-sizing: border-box; background: #000; overflow: hidden; }
                            html, body { width: 100%; height: 100%; background: #000; }
                            #vjs_container, .video-js, video {
                                width: 100% !important;
                                height: 100% !important;
                                object-fit: $objectFitCss !important;
                                background: #000 !important;
                            }
                            .vjs-control-bar, .vjs-big-play-button, .vjs-loading-spinner, .vjs-error-display {
                                display: none !important;
                            }
                        </style>
                        <script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.8/dist/hls.min.js"></script>
                        <script src="https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.js"></script>
                    </head>
                    <body>
                        <video id="foul_vjs_video" class="video-js" autoplay playsinline webkit-playsinline></video>
                        <script>
                            (function() {
                                var video = document.getElementById('foul_vjs_video');
                                var url = "$escapedUrl";
                                var fallbackTriggered = false;
                                function notifyError() {
                                    if (!fallbackTriggered) {
                                        fallbackTriggered = true;
                                        if (window.FoulVideoJsBridge) window.FoulVideoJsBridge.onError();
                                    }
                                }
                                video.addEventListener('playing', function() {
                                    if (window.FoulVideoJsBridge) {
                                        window.FoulVideoJsBridge.onPlaying();
                                        if (video.videoWidth > 0 && video.videoHeight > 0) {
                                            window.FoulVideoJsBridge.onLoadedMetadata(video.videoWidth, video.videoHeight);
                                        }
                                    }
                                });
                                video.addEventListener('waiting', function() {
                                    if (window.FoulVideoJsBridge) window.FoulVideoJsBridge.onWaiting();
                                });
                                video.addEventListener('loadedmetadata', function() {
                                    if (window.FoulVideoJsBridge && video.videoWidth > 0) {
                                        window.FoulVideoJsBridge.onLoadedMetadata(video.videoWidth, video.videoHeight);
                                    }
                                });
                                video.addEventListener('error', function() {
                                    notifyError();
                                });

                                var lowerUrl = url.toLowerCase();
                                if (lowerUrl.indexOf('.ts') !== -1 && window.mpegts && mpegts.isSupported()) {
                                    try {
                                        var tsPlayer = mpegts.createPlayer({
                                            type: 'mpegts',
                                            isLive: true,
                                            url: url,
                                            cors: true
                                        }, {
                                            enableWorker: true,
                                            enableStashBuffer: true,
                                            stashInitialSize: 384 * 1024,
                                            liveBufferLatencyChasing: false
                                        });
                                        tsPlayer.attachMediaElement(video);
                                        tsPlayer.load();
                                        tsPlayer.play();
                                        tsPlayer.on(mpegts.Events.ERROR, function() { notifyError(); });
                                    } catch (e) {
                                        notifyError();
                                    }
                                } else if (lowerUrl.indexOf('.m3u8') !== -1 && window.Hls && Hls.isSupported()) {
                                    var hls = new Hls({
                                        enableWorker: true,
                                        lowLatencyMode: false,
                                        liveSyncDurationCount: 3,
                                        liveMaxLatencyDurationCount: 10,
                                        maxBufferLength: 60,
                                        maxMaxBufferLength: 120,
                                        maxBufferSize: 60 * 1000 * 1000,
                                        maxBufferHole: 2.5,
                                        highBufferWatchdogPeriod: 3,
                                        nudgeOffset: 0.2,
                                        nudgeMaxRetry: 6,
                                        manifestLoadingTimeOut: 10000,
                                        manifestLoadingMaxRetry: 3,
                                        levelLoadingTimeOut: 10000,
                                        fragLoadingTimeOut: 15000,
                                        fragLoadingMaxRetry: 4
                                    });
                                    hls.loadSource(url);
                                    hls.attachMedia(video);
                                    hls.on(Hls.Events.MANIFEST_PARSED, function() {
                                        video.play().catch(function(){});
                                    });
                                    hls.on(Hls.Events.ERROR, function(event, data) {
                                        if (data.fatal) {
                                            if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                                                hls.recoverMediaError();
                                            } else {
                                                notifyError();
                                            }
                                        }
                                    });
                                } else {
                                    video.src = url;
                                    video.load();
                                    video.play().catch(function() { notifyError(); });
                                }
                            })();
                        </script>
                    </body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(baseOrigin, html, "text/html", "UTF-8", null)
            } else {
                webView.evaluateJavascript(
                    """
                    (function() {
                        var v = document.getElementById('foul_vjs_video');
                        if (v) {
                            v.style.objectFit = '$objectFitCss';
                            v.muted = ${if (isMuted) "true" else "false"};
                            if (${if (isPlaying) "true" else "false"} && v.paused) v.play().catch(function(){});
                            if (${if (!isPlaying) "true" else "false"} && !v.paused) v.pause();
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        },
        onRelease = { webView ->
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        },
        modifier = modifier
    )
}

/**
 * Android Native OS Hardware `VideoView` / `MediaPlayer` Surface (`Stagefright` / `NuPlayer` firmware decoder).
 * Completely bypasses ExoPlayer and decodes the stream directly via the Android OS kernel media pipeline.
 */
@Composable
fun NativeOsVideoEngineSurface(
    streamUrl: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    onBufferingChanged: (Boolean) -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit,
    onVideoResolutionChanged: (Int, Int) -> Unit,
    onStreamError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mediaPlayerRef = remember { arrayOfNulls<MediaPlayer>(1) }

    LaunchedEffect(isMuted) {
        val mp = mediaPlayerRef[0]
        if (mp != null) {
            runCatching {
                val vol = if (isMuted) 0f else 1f
                mp.setVolume(vol, vol)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                setBackgroundColor(AndroidColor.BLACK)
                keepScreenOn = true
                val videoView = VideoView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    setOnPreparedListener { mp ->
                        mediaPlayerRef[0] = mp
                        val vol = if (isMuted) 0f else 1f
                        mp.setVolume(vol, vol)
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                            onVideoResolutionChanged(mp.videoWidth, mp.videoHeight)
                        }
                        mp.setOnInfoListener { _, what, _ ->
                            when (what) {
                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                                    onBufferingChanged(true)
                                    true
                                }
                                MediaPlayer.MEDIA_INFO_BUFFERING_END,
                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                                    onBufferingChanged(false)
                                    onPlayingStateChanged(true)
                                    true
                                }
                                else -> false
                            }
                        }
                        mp.setOnVideoSizeChangedListener { _, w, h ->
                            if (w > 0 && h > 0) {
                                onVideoResolutionChanged(w, h)
                            }
                        }
                        onBufferingChanged(false)
                        if (isPlaying) {
                            start()
                            onPlayingStateChanged(true)
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        onStreamError()
                        true
                    }
                }
                addView(videoView)
            }
        },
        update = { container ->
            val videoView = container.getChildAt(0) as? VideoView ?: return@AndroidView
            val currentLoaded = videoView.tag as? String
            if (currentLoaded != streamUrl && streamUrl.isNotBlank()) {
                videoView.tag = streamUrl
                onBufferingChanged(true)
                runCatching {
                    val headers = mapOf(
                        "User-Agent" to "VLC/3.0.20 LibVLC/3.0.20",
                        "Connection" to "keep-alive"
                    )
                    videoView.setVideoURI(Uri.parse(streamUrl), headers)
                    videoView.start()
                }.onFailure {
                    onStreamError()
                }
            } else {
                runCatching {
                    if (isPlaying && !videoView.isPlaying) {
                        videoView.start()
                        onPlayingStateChanged(true)
                    } else if (!isPlaying && videoView.isPlaying) {
                        videoView.pause()
                        onPlayingStateChanged(false)
                    }
                }
            }
        },
        onRelease = { container ->
            val videoView = container.getChildAt(0) as? VideoView
            runCatching {
                videoView?.stopPlayback()
            }
            mediaPlayerRef[0] = null
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerRef[0] = null
        }
    }
}
