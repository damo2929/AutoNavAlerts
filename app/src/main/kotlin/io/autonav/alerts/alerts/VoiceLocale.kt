// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.alerts

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import io.autonav.alerts.App
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.settings.SettingsStore
import io.autonav.alerts.settings.VoiceLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One voice language for everything (DESIGN.md §12.3, owner 2026-10-23): the setting becomes the
 * app's own language, which the Navigation SDK picks its turn-by-turn voice pack from, and
 * [locale] tells [NavTts] to speak the alerts in the same one.
 */
@Singleton
class VoiceLocale @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val eventLog: EventLog,
) {
    private val _locale = MutableStateFlow(Locale.forLanguageTag(VoiceLanguages.DEFAULT))
    val locale: StateFlow<Locale> = _locale

    /** From `App.onCreate`: applies the stored language now and every time it changes. */
    fun start() {
        App.appScope.launch(Dispatchers.Main) {
            settings.settings.map { it.voiceLanguage }.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(tag: String) {
        val loc = Locale.forLanguageTag(tag)
        Locale.setDefault(loc)
        // Android 13+: the app's own language, so the SDK (and anything else reading the app's
        // configuration) follows it. Only when different: setting it recreates open activities.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val lm = context.getSystemService(LocaleManager::class.java)
                val want = LocaleList.forLanguageTags(tag)
                if (lm.applicationLocales != want) lm.applicationLocales = want
            }.onFailure { eventLog.record(EventLog.Channel.NAV, "Voice", "app locale failed: ${it.message}") }
        }
        _locale.value = loc
        eventLog.record(EventLog.Channel.NAV, "Voice", "language=$tag")
    }
}
