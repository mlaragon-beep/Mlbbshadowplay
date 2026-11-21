package com.mlbb.replayrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.mlbb.replayrecorder.START"
        const val ACTION_STOP = "com.mlbb.replayrecorder.STOP"
        const val ACTION_SAVE = "com.mlbb.replayrecorder.SAVE"
        const val ACTION_REC = "com.mlbb.replayrecorder.REC"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false
        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1
        private const val SR = 44100
    }

    private val main = Handler(Looper.getMainLooper())
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var aRec: AudioRecord? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var buffer = ReplayBuffer(30_000_000L)
    private var bufferSec = 30
    private var stopping = false

    @Volatile private var vFormat: MediaFormat? = null
    @Volatile private var aFormat: MediaFormat? = null
    @Volatile private var sink: MuxerSink? = null

    private var bubble: TextView? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Recorder", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) startCapture(intent)
            ACTION_SAVE -> saveClip()
            ACTION_REC -> toggleRecording()
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    @Suppress("DEPRECATION")
    private fun screenMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return dm
    }

    private fun startCapture(intent: Intent) {
        val data = readData(intent)
        if (data == null) {
            stopSelf()
            return
        }
        startForeground(NOTIF_ID, notif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    shutdown()
                }
            }, main)
            projection = proj

            // settings
            bufferSec = Prefs.getInt(this, Prefs.BUFFER, 30)
            buffer = ReplayBuffer(bufferSec * 1_000_000L)
            val fps = Prefs.getInt(this, Prefs.FPS, 30)
            val mbps = Prefs.getInt(this, Prefs.BITRATE, 8)

            // landscape size (MLBB is landscape), multiples of 16
            val dm = screenMetrics()
            val longSide = maxOf(dm.widthPixels, dm.heightPixels)
            val shortSide = minOf(dm.widthPixels, dm.heightPixels)
            val tShort = minOf(Prefs.getInt(this, Prefs.HEIGHT, 720), shortSide)
            val h = maxOf(16, (tShort + 8) / 16 * 16)
            val w = maxOf(16, ((tShort.toLong() * longSide / shortSide).toInt() + 8) / 16 * 16)

            // video encoder
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, mbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            vEnc = enc
            display = proj.createVirtualDisplay(
                "mlbb-rec", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )

            // internal audio (optional)
            if (Prefs.getBool(this, Prefs.AUDIO, true) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    setupAudio(proj)
                } catch (e: Exception) {
                    try { aRec?.release() } catch (x: Exception) { }
                    try { aEnc?.release() } catch (x: Exception) { }
                    aRec = null
                    aEnc = null
                    toast("Internal audio not available: ${e.message}")
                }
            }

            running = true
            vThread = Thread { videoLoop(enc) }.apply { name = "rec-video"; start() }
            val rec = aRec
            val aenc = aEnc
            if (rec != null && aenc != null) {
                aThread = Thread { audioLoop(rec, aenc) }.apply { name = "rec-audio"; start() }
            }
            updateUi()
            addBubble()
        } catch (e: Exception) {
            toast("Could not start: ${e.message}")
            shutdown()
        }
    }

    private fun setupAudio(proj: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf * 4, 32768))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("capture refused")
        }
        aRec = rec
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aenc.start()
        aEnc = aenc
    }

    // ---------------------------------------------------------------- encoder loops

    private fun videoLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                enc.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                break
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                vFormat = enc.outputFormat
            } else if (idx >= 0) {
                val buf = enc.getOutputBuffer(idx)
                if (buf != null && info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    val s = Sample(bytes, info.presentationTimeUs, info.flags)
                    buffer.addVideo(s)
                    val sk = sink
                    if (sk != null) writer.execute { sk.writeVideo(s) }
                }
                enc.releaseOutputBuffer(idx, false)
            }
        }
    }

    private fun audioLoop(rec: AudioRecord, enc: MediaCodec) {
        val chunk = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var frames = 0L
        var startUs = -1L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n == 0) continue
                val chunkUs = (n / 4) * 1_000_000L / SR
                if (startUs < 0) startUs = System.nanoTime() / 1000 - chunkUs
                val pts = startUs + frames * 1_000_000L / SR
                frames += (n / 4).toLong()
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)
                    if (ib != null) {
                        ib.clear()
                        ib.put(chunk, 0, n)
                        enc.queueInputBuffer(inIdx, 0, n, pts, 0)
                    }
                }
                drainAudio(enc, info)
            }
        } catch (e: Exception) {
            // stopped
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aFormat = enc.outputFormat
                continue
            }
            if (idx < 0) return
            val buf = enc.getOutputBuffer(idx)
            if (buf != null && info.size > 0 &&
                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            ) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val s = Sample(bytes, info.presentationTimeUs, info.flags)
                buffer.addAudio(s)
                val sk = sink
                if (sk != null) writer.execute { sk.writeAudio(s) }
            }
            enc.releaseOutputBuffer(idx, false)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun saveClip() {
        if (!running) return
        val vf = vFormat
        if (vf == null) {
            toast("Not ready yet, try again in a second")
            return
        }
        val (v, a) = buffer.snapshot()
        if (v.isEmpty()) {
            toast("Buffer is empty")
            return
        }
        val af = aFormat
        toast("Saving last ${bufferSec}s…")
        Thread {
            val uri = try {
                ClipSaver.save(applicationContext, v, a, vf, af)
            } catch (e: Exception) {
                null
            }
            toast(if (uri != null) "Clip saved (Movies/MLBB Recorder)" else "Save failed")
        }.start()
    }

    private fun toggleRecording() {
        if (!running) return
        val cur = sink
        if (cur == null) {
            val vf = vFormat
            if (vf == null) {
                toast("Not ready yet")
                return
            }
            try {
                sink = MuxerSink(applicationContext, vf, aFormat)
                toast("Recording started")
            } catch (e: Exception) {
                toast("Cannot start recording")
            }
        } else {
            sink = null
            writer.execute {
                val u = cur.close()
                toast(if (u != null) "Recording saved (Movies/MLBB Recorder)" else "Save failed")
            }
        }
        updateUi()
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        running = false
        val s = sink
        sink = null
        if (s != null) writer.execute { s.close() }
        removeBubble()
        try { vThread?.join(1000) } catch (e: Exception) { }
        try { aThread?.join(1000) } catch (e: Exception) { }
        try { aRec?.stop() } catch (e: Exception) { }
        try { aRec?.release() } catch (e: Exception) { }
        try { aEnc?.stop() } catch (e: Exception) { }
        try { aEnc?.release() } catch (e: Exception) { }
        try { display?.release() } catch (e: Exception) { }
        try { vEnc?.stop() } catch (e: Exception) { }
        try { vEnc?.release() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        writer.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- UI bits

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun notif(text: String): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MLBB Replay Recorder")
            .setContentText(text)
            .setOngoing(true)
        b.addAction(android.R.drawable.ic_menu_save, "Save clip", pi(ACTION_SAVE, 1))
        b.addAction(
            android.R.drawable.ic_media_play,
            if (sink == null) "Record" else "Stop rec",
            pi(ACTION_REC, 2)
        )
        b.addAction(android.R.drawable.ic_delete, "Exit", pi(ACTION_STOP, 3))
        return b.build()
    }

    private fun updateUi() {
        main.post {
            if (stopping) return@post
            val rec = sink != null
            bubble?.text = if (rec) "■" else "●"
            val t = if (rec) "Recording…" else "Replay buffer ON – last ${bufferSec}s"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(t))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (!Prefs.getBool(this, Prefs.BUBBLE, true) || !Settings.canDrawOverlays(this)) return
        main.post {
            if (stopping) return@post
            val w = getSystemService(WINDOW_SERVICE) as WindowManager
            val size = (52 * resources.displayMetrics.density).toInt()
            val tv = TextView(this).apply {
                text = "●"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99D32F2F.toInt())
                }
            }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 200
            }
            tv.setOnTouchListener(object : View.OnTouchListener {
                var sx = 0f
                var sy = 0f
                var ox = 0
                var oy = 0
                var moved = false
                var t0 = 0L
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                            moved = false
                            t0 = System.currentTimeMillis()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - sx).toInt()
                            val dy = (e.rawY - sy).toInt()
                            if (abs(dx) > 10 || abs(dy) > 10) moved = true
                            if (moved) {
                                lp.x = ox + dx
                                lp.y = oy + dy
                                w.updateViewLayout(v, lp)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                if (System.currentTimeMillis() - t0 > 600) toggleRecording() else saveClip()
                            }
                        }
                    }
                    return true
                }
            })
            try {
                w.addView(tv, lp)
                bubble = tv
                wm = w
            } catch (e: Exception) {
                toast("Floating button failed")
            }
        }
    }

    private fun removeBubble() {
        val b = bubble
        val w = wm
        bubble = null
        if (b != null && w != null) {
            try { w.removeView(b) } catch (e: Exception) { }
        }
    }
}
