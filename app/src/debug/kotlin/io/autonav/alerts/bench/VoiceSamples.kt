// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.bench

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Debug bench only (DESIGN.md §12.3, TESTING.md A25): synthesises [phrase] with every installed
 * voice of the voice language to `files/debug/voices/<voice>.wav`, so each can be compared with the
 * Navigation SDK's recorded prompts to pick the one that sounds the same. Logged as `VoiceSamples`.
 */
object VoiceSamples {
    fun write(context: Context, phrase: String, locale: Locale = Locale.getDefault()) {
        val dir = File(context.filesDir, "debug/voices").apply { deleteRecursively(); mkdirs() }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "init failed $status"); return@TextToSpeech }
            val voices = engine.voices.orEmpty()
                .filter { it.locale.language == locale.language && it.locale.country == locale.country }
                .sortedBy { it.name }
            Log.i(TAG, "default=${engine.defaultVoice?.name} voice=${engine.voice?.name} ${voices.size} voices: ${voices.joinToString { "${it.name}${if (it.isNetworkConnectionRequired) "(net)" else ""}" }}")
            File(dir, "voices.txt").writeText(
                "default=${engine.defaultVoice?.name}\n" + voices.joinToString("\n") { "${it.name} net=${it.isNetworkConnectionRequired} quality=${it.quality} features=${it.features}" },
            )
            var left = voices.size
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { Log.i(TAG, "wrote $id"); if (--left == 0) engine.shutdown() }
                @Deprecated("Deprecated in Java") override fun onError(id: String) { Log.w(TAG, "error $id"); if (--left == 0) engine.shutdown() }
            })
            for (v in voices) {
                engine.voice = v
                engine.synthesizeToFile(phrase, null, File(dir, "${v.name}.wav"), v.name)
            }
        }
    }

    private const val TAG = "VoiceSamples"
}
