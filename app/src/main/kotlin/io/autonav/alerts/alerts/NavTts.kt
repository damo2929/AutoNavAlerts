// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech on the navigation-guidance channel (DESIGN.md §12.3): ducks media, never pauses it, and
 * queues behind the Navigation SDK's own prompts. PASSING waits at most 1 s; others up to 6 s.
 */
@Singleton
class NavTts @Inject constructor(@ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focus: AudioFocusRequest? = null

    private data class Pending(val text: String, val priority: Int, val poiId: Long, val chime: Boolean, val queuedAt: Long)
    private val queue = ArrayList<Pending>()
    private var speaking = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) = main.post { finished() }.let { }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = main.post { finished() }.let { }
                })
                main.post { pump() }
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        abandonFocus()
    }

    /** Queue speech. A newer utterance for the same POI replaces a waiting one. */
    fun speak(text: String, priority: Int, poiId: Long, chime: Boolean) = main.post {
        warmUp()
        queue.removeAll { it.poiId == poiId && poiId >= 0 }
        queue += Pending(text, priority, poiId, chime, System.currentTimeMillis())
        queue.sortByDescending { it.priority }
        pump()
    }

    private fun sdkSpeaking(): Boolean = runCatching {
        audio.activePlaybackConfigurations.any { c: AudioPlaybackConfiguration ->
            c.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        }
    }.getOrDefault(false)

    private fun pump() {
        if (speaking || !ready || queue.isEmpty()) return
        val next = queue.first()
        val waited = System.currentTimeMillis() - next.queuedAt
        val maxWait = if (next.priority >= 100) 1_000 else 6_000
        if (sdkSpeaking() && waited < maxWait) {
            main.postDelayed({ pump() }, 250)
            return
        }
        queue.removeAt(0)
        speaking = true
        requestFocus()
        if (next.chime) runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        }
        val delay = if (next.chime) 250L else 0L
        main.postDelayed({
            val r = tts?.speak(next.text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            if (r != TextToSpeech.SUCCESS) finished()
        }, delay)
    }

    private fun finished() {
        speaking = false
        if (queue.isEmpty()) abandonFocus()
        pump()
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
