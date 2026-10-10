package com.xmen12c.bgtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that owns the WebView playing the YouTube embed.
 * A CPU wakelock keeps audio decoding with the screen off; the media-style
 * notification provides play/pause/stop controls and lock-screen artwork.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.xmen12c.bgtube.action.PLAY"
        const val ACTION_TOGGLE = "com.xmen12c.bgtube.action.TOGGLE"
        const val ACTION_STOP = "com.xmen12c.bgtube.action.STOP"
        const val EXTRA_VIDEO_ID = "video_id"

        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 42
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L

        @Volatile
        var instance: PlaybackService? = null
            private set

        private var hostRef: WeakReference<MainActivity>? = null

        fun hostActivity(activity: MainActivity) {
            hostRef = WeakReference(activity)
            maybeAttach()
        }

        fun clearHost(activity: MainActivity) {
            if (hostRef?.get() === activity) hostRef = null
        }

        fun maybeAttach() {
            val service = instance ?: return
            val activity = hostRef?.get() ?: return
            service.attachTo(activity)
        }

        fun toggle() {
            instance?.postJs("toggle")
        }

        fun stopFromUi() {
            instance?.stopPlayback()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wakeLock: PowerManager.WakeLock
    private var contextWrapper: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var videoId: String? = null
    private var title: String? = null
    private var playing = false
    private var foregroundActive = false
    private var thumbnail: Bitmap? = null
    private var errorNotice: String? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> postJs("pause")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BgTube::playback")
        mediaSession = MediaSessionCompat(this, "BgTube").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = postJs("play")
                override fun onPause() = postJs("pause")
                override fun onStop() {
                    mainHandler.post { stopPlayback() }
                }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: even a STOP started via
        // startForegroundService must call startForeground() or the system crashes us.
        startForegroundCompat(currentNotification())
        foregroundActive = true

        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_VIDEO_ID)
                if (id != null && VideoId.isValid(id)) playVideo(id) else stopPlayback()
            }
            ACTION_TOGGLE -> postJs("toggle")
            ACTION_STOP -> stopPlayback()
            else -> if (videoId == null) stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        hostRef?.clear()
        releaseWebview()
        abandonAudioFocus()
        if (wakeLock.isHeld) wakeLock.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ playback

    private fun playVideo(id: String) {
        videoId = id
        title = null
        thumbnail = null
        playing = false
        errorNotice = null

        ensureWebView()
        PlayerHub.update(PlayerHub.State(videoId = id))
        startForegroundCompat(buildNotification(getString(R.string.status_loading), ongoing = true))
        foregroundActive = true
        acquireWakeLock()
        requestAudioFocus()
        loadInWebView(id)
        fetchThumbnail(id)
        maybeAttach()
    }

    private fun stopPlayback() {
        videoId = null
        title = null
        thumbnail = null
        playing = false
        abandonAudioFocus()
        if (wakeLock.isHeld) wakeLock.release()
        PlayerHub.update(PlayerHub.State())
        if (foregroundActive) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        releaseWebview()
        stopSelf()
    }

    private fun releaseWebview() {
        webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.loadUrl("about:blank")
            web.destroy()
        }
        webView = null
        contextWrapper = null
    }

    // ------------------------------------------------------------------ webview

    private fun ensureWebView() {
        if (webView != null) return
        val wrapper = MutableContextWrapper(applicationContext)
        contextWrapper = wrapper

        val web = BackgroundWebView(wrapper)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // YouTube refuses embed playback (error 152) for WebView user agents;
            // drop the "; wv" token so the player sees regular Chrome on Android.
            userAgentString = WebSettings.getDefaultUserAgent(applicationContext).replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // Skip the EU consent wall if possible (accept-all SOCS cookie).
        runCatching {
            CookieManager.getInstance()
                .setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/; Secure")
        }
        web.addJavascriptInterface(
            JsBridge(
                stateCallback = { state, newTitle -> mainHandler.post { onPlayerState(state, newTitle) } },
                errorCallback = { mainHandler.post { onPlayerError() } },
            ),
            "BgTube",
        )
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        webView = web
    }

    private fun loadInWebView(id: String) {
        webView?.loadDataWithBaseURL("https://www.youtube.com/", playerHtml(id), "text/html", "utf-8", null)
    }

    private fun postJs(function: String) {
        mainHandler.post {
            webView?.evaluateJavascript("window.bgtube && window.bgtube.$function()", null)
        }
    }

    private fun playerHtml(videoId: String): String {
        // videoId is validated as an 11-char [A-Za-z0-9_-] id before it gets here.
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
              #player{width:100%;height:100%;border:0}
            </style></head><body>
            <div id="player"></div>
            <script>
              var tag = document.createElement('script');
              tag.src = 'https://www.youtube.com/iframe_api';
              document.head.appendChild(tag);
              var player = null;
              window.onYouTubeIframeAPIReady = function() {
                player = new YT.Player('player', {
                  width: '100%', height: '100%', videoId: '$videoId',
                  playerVars: { autoplay: 1, playsinline: 1, rel: 0 },
                  events: {
                    onReady: function(e) { e.target.playVideo(); },
                    onStateChange: function() { report(); },
                    onError: function(code) { if (window.BgTube) BgTube.onError(code); }
                  }
                });
              };
              function report() {
                if (!player || !player.getPlayerState) return;
                var t = '';
                try { t = player.getVideoData().title || ''; } catch (e) {}
                if (window.BgTube) BgTube.onState(player.getPlayerState(), t);
              }
              setInterval(report, 2000);
              window.bgtube = {
                play:   function() { player && player.playVideo && player.playVideo(); },
                pause:  function() { player && player.pauseVideo && player.pauseVideo(); },
                toggle: function() {
                  if (!player) return;
                  var s = player.getPlayerState();
                  if (s === 1 || s === 3) { player.pauseVideo(); } else { player.playVideo(); }
                }
              };
            </script></body></html>
        """.trimIndent()
    }

    // ------------------------------------------------------------------ player events

    private fun onPlayerState(state: Int, newTitle: String) {
        when (state) {
            1 -> playing = true              // PLAYING
            2 -> playing = false             // PAUSED
            0 -> {                            // ENDED
                playing = false
                publishState()
                stopPlayback()
                return
            }
        }
        val hadTitle = title != null
        if (newTitle.isNotBlank() && newTitle != title) {
            title = newTitle
            val id = videoId
            if (id != null && !hadTitle) RecentsStore.setTitle(this, id, newTitle)
        }
        publishState()
        updateMediaSession()
        if (foregroundActive) notifyManager().notify(NOTIF_ID, currentNotification())
    }

    private fun onPlayerError() {
        playing = false
        errorNotice = getString(R.string.status_error)
        publishState()
        updateMediaSession()
        if (foregroundActive) notifyManager().notify(NOTIF_ID, currentNotification())
    }

    private fun publishState() {
        PlayerHub.update(
            PlayerHub.State(videoId = videoId, title = title, isPlaying = playing, notice = errorNotice)
        )
    }

    // ------------------------------------------------------------------ media session + notification

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_PLAY_PAUSE
                        or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    0L, 0f,
                )
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "YouTube")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, thumbnail)
                .build()
        )
    }

    private fun currentNotification(): Notification =
        buildNotification(
            when {
                videoId == null -> getString(R.string.app_name)
                playing -> getString(R.string.status_playing, title ?: "")
                title != null -> getString(R.string.status_paused, title)
                else -> getString(R.string.status_loading)
            },
            ongoing = playing || videoId != null && title == null,
        )

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val togglePending = servicePendingIntent(ACTION_TOGGLE)
        val stopPending = servicePendingIntent(ACTION_STOP)
        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val toggleLabel = if (playing) getString(R.string.action_pause) else getString(R.string.action_play)

        val style = MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(text)
            .setLargeIcon(thumbnail)
            .setContentIntent(contentIntent)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(toggleIcon, toggleLabel, togglePending)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopPending)
            .setStyle(style)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getService(this, action.hashCode(), intent, flags)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        notifyManager().createNotificationChannel(channel)
    }

    private fun notifyManager() = getSystemService(NotificationManager::class.java)

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        foregroundActive = true
    }

    // ------------------------------------------------------------------ wakelock + audio focus

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    // ------------------------------------------------------------------ thumbnail + attachment

    private fun fetchThumbnail(id: String) {
        thread(start = true) {
            try {
                val connection = URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()
                if (bitmap != null && id == videoId) {
                    mainHandler.post {
                        thumbnail = bitmap
                        updateMediaSession()
                        if (foregroundActive) notifyManager().notify(NOTIF_ID, currentNotification())
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun attachTo(activity: MainActivity) {
        val web = webView ?: return
        val wrapper = contextWrapper ?: return
        if (web.parent === activity.videoContainer) return
        (web.parent as? ViewGroup)?.removeView(web)
        wrapper.baseContext = activity
        activity.videoContainer.removeAllViews()
        activity.videoContainer.addView(
            web,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    fun detach() {
        val web = webView ?: return
        (web.parent as? ViewGroup)?.removeView(web)
        contextWrapper?.baseContext = applicationContext
    }
}
