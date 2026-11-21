package com.mlbb.replayrecorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One encoded frame (video or audio) kept in RAM. */
class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

/** A group of pictures: starts with a keyframe. */
class Gop(val startPts: Long) {
    val frames = ArrayList<Sample>()
}

/**
 * Keeps the last [maxUs] microseconds of already-encoded video/audio in memory.
 * Nothing is written to storage until you save a clip, so there is no disk lag.
 */
class ReplayBuffer(private val maxUs: Long) {
    private val lock = Any()
    private val gops = ArrayList<Gop>()
    private val audio = java.util.ArrayDeque<Sample>()

    fun addVideo(s: Sample) {
        synchronized(lock) {
            val key = (s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (key) gops.add(Gop(s.ptsUs))
            if (gops.isEmpty()) return
            gops[gops.size - 1].frames.add(s)
            // drop the oldest GOP while the rest still covers the full window
            while (gops.size > 1 && s.ptsUs - gops[1].startPts >= maxUs) gops.removeAt(0)
            val oldest = gops[0].startPts
            while (true) {
                val a = audio.peekFirst() ?: break
                if (a.ptsUs < oldest) audio.pollFirst() else break
            }
        }
    }

    fun addAudio(s: Sample) {
        synchronized(lock) {
            audio.addLast(s)
            // safety trim in case video frames stall (static screen)
            while (true) {
                val a = audio.peekFirst() ?: break
                if (a.ptsUs < s.ptsUs - maxUs - 3_000_000L) audio.pollFirst() else break
            }
        }
    }

    fun snapshot(): Pair<List<Sample>, List<Sample>> {
        synchronized(lock) {
            val v = ArrayList<Sample>()
            for (g in gops) v.addAll(g.frames)
            return Pair(v, ArrayList(audio))
        }
    }
}

/** Writes samples into an MP4 in Movies/MLBB Recorder. Used for clips and for normal recording. */
class MuxerSink(ctx: Context, vf: MediaFormat, af: MediaFormat?) {
    private val resolver = ctx.contentResolver
    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val vTrack: Int
    private val aTrack: Int
    private var base = -1L
    private var closed = false
    private val info = MediaCodec.BufferInfo()

    init {
        val name = "MLBB_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MLBB Recorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file")
        pfd = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Could not open file")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        vTrack = muxer.addTrack(vf)
        aTrack = if (af != null) muxer.addTrack(af) else -1
        muxer.start()
    }

    @Synchronized
    fun writeVideo(s: Sample) {
        if (closed) return
        if (base < 0) {
            // always start on a keyframe
            if ((s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return
            base = s.ptsUs
        }
        write(vTrack, s)
    }

    @Synchronized
    fun writeAudio(s: Sample) {
        if (closed || aTrack < 0 || base < 0 || s.ptsUs < base) return
        write(aTrack, s)
    }

    private fun write(track: Int, s: Sample) {
        try {
            info.set(0, s.data.size, s.ptsUs - base, s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
        } catch (e: Exception) {
            // skip a bad sample instead of losing the whole clip
        }
    }

    @Synchronized
    fun close(): Uri? {
        if (closed) return null
        closed = true
        var ok = true
        try { muxer.stop() } catch (e: Exception) { ok = false }
        try { muxer.release() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
        return if (ok) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }
}

object ClipSaver {
    /** Saves a snapshot of the replay buffer as one MP4. Returns the file Uri or null. */
    fun save(ctx: Context, v: List<Sample>, a: List<Sample>, vf: MediaFormat, af: MediaFormat?): Uri? {
        if (v.isEmpty()) return null
        val sink = MuxerSink(ctx, vf, af)
        var i = 0
        var j = 0
        while (i < v.size || j < a.size) {
            if (j >= a.size || (i < v.size && v[i].ptsUs <= a[j].ptsUs)) {
                sink.writeVideo(v[i])
                i++
            } else {
                sink.writeAudio(a[j])
                j++
            }
        }
        return sink.close()
    }
}
