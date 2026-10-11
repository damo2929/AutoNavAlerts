// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.reports

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Records a spoken comment from the car's microphone (DESIGN.md §8.5): up to 15 s, stopping after
 * 2 s of silence once speech has started, or 6 s if nothing is said. Output is raw 16 kHz mono PCM.
 */
class CarVoiceRecorder(private val carContext: CarContext, private val out: File) {
    private val stopRequested = AtomicBoolean(false)
    @Volatile var heardSpeech = false
        private set

    fun stop() = stopRequested.set(true)

    /** Blocks the calling (background) thread until recording ends. Returns the file, or null if nothing was said. */
    @android.annotation.SuppressLint("MissingPermission") // checked just below
    fun recordBlocking(): File? {
        if (!available(carContext)) return null
        val rec = runCatching { CarAudioRecord.create(carContext) }.getOrElse { Log.w(TAG, "No car mic: ${it.message}"); return null }
        val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val started = System.currentTimeMillis()
        var lastLoud = 0L
        out.parentFile?.mkdirs()
        try {
            rec.startRecording()
            out.outputStream().buffered().use { os ->
                while (!stopRequested.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    os.write(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (rms(buf, n) > LOUD_RMS) { heardSpeech = true; lastLoud = now }
                    val elapsed = now - started
                    when {
                        elapsed > MAX_MS -> break
                        !heardSpeech && elapsed > NO_SPEECH_MS -> break
                        heardSpeech && now - lastLoud > SILENCE_MS -> break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car recording failed: ${t.message}")
        } finally {
            runCatching { rec.stopRecording() }
        }
        if (!heardSpeech) { out.delete(); return null }
        return out
    }

    private fun rms(b: ByteArray, n: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / (n / 2).coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "CarVoice"
        private const val MAX_MS = 15_000L
        private const val NO_SPEECH_MS = 6_000L
        private const val SILENCE_MS = 2_000L
        private const val LOUD_RMS = 900.0

        /** Car mic needs car API 5 and the RECORD_AUDIO permission (granted on the phone). */
        fun available(carContext: CarContext): Boolean =
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5 &&
                ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}

/** Speech-to-text from a saved PCM recording, on the phone (Android 13+). Must be started on the main thread. */
object Transcriber {
    private const val TAG = "Transcriber"

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun transcribe(context: Context, pcm: File): String? {
        if (!supported(context) || !pcm.exists()) return null
        val onDevice = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        return run(context, pcm, onDevice) ?: if (onDevice) run(context, pcm, false) else null
    }

    private suspend fun run(context: Context, pcm: File, onDevice: Boolean): String? = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            val sr = runCatching {
                if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            }.getOrNull()
            if (sr == null) { if (cont.isActive) cont.resume(null); return@post }
            val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
            fun finish(text: String?) {
                runCatching { sr.destroy() }
                runCatching { pfd.close() }
                if (cont.isActive) cont.resume(text)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) =
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() })
                override fun onError(error: Int) { Log.w(TAG, "Recognition error $error (onDevice=$onDevice)"); finish(null) }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            }
            runCatching { sr.startListening(intent) }.onFailure { finish(null) }
            cont.invokeOnCancellation { main.post { runCatching { sr.destroy() }; runCatching { pfd.close() } } }
        }
    }

    /** Plays a recording back on the phone (review screen). */
    fun play(pcm: File) {
        if (!pcm.exists()) return
        val data = pcm.readBytes()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size.coerceAtLeast(1024))
            .build()
        track.write(data, 0, data.size)
        track.setNotificationMarkerPosition(data.size / 2)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) { t?.release() }
            override fun onPeriodicNotification(t: AudioTrack?) = Unit
        })
        track.play()
    }
}
