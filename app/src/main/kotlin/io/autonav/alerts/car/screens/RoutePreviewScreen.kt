// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.BuildConfig
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.nav.RouteOption
import kotlinx.coroutines.launch

/**
 * Route preview with ETA and distance, then Go (DESIGN.md §8). Uses RoutePreviewNavigationTemplate,
 * deprecated in Car App 1.7 in favour of MapWithContentTemplate, which needs car API level 7; the
 * older template keeps the preview working on older head units.
 */
@Suppress("DEPRECATION")
class RoutePreviewScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val destination: Destination,
) : Screen(carContext) {
    private val deps get() = session.deps
    private var route: RouteOption? = null
    private var error: String? = null
    private var loading = true

    init {
        lifecycleScope.launch {
            error = deps.guidance().setDestination(destination)
            route = deps.guidance().currentRoute()
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let { msg ->
            return MessageTemplate.Builder(msg)
                .setHeader(Header.Builder().setTitle(destination.title).setStartHeaderAction(Action.BACK).build())
                .addAction(CarUi.textAction("Back") { screenManager.pop() })
                .build()
        }
        val header = Header.Builder().setTitle(destination.title).setStartHeaderAction(Action.BACK).build()
        val b = RoutePreviewNavigationTemplate.Builder().setHeader(header)
        if (loading) return b.setLoading(true).build()
        val r = route ?: return MessageTemplate.Builder("Couldn't find a route there")
            .setHeader(header)
            .addAction(CarUi.textAction("Back") { screenManager.pop() })
            .build()
        // The host requires a duration or distance span on every route row (it throws otherwise).
        val title = SpannableString("  ").apply { setSpan(DurationSpan.create(r.seconds.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        val text = SpannableString("  " + trafficText(r.delaySeverity)).apply {
            setSpan(DistanceSpan.create(io.autonav.alerts.car.TripMapper(carContext, session.imperial).distance(r.meters)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val items = ItemList.Builder()
        items.addItem(Row.Builder().setTitle(CarText.create(title)).addText(CarText.create(text)).build())
        items.setOnSelectedListener { }
        items.setSelectedIndex(0)
        b.setItemList(items.build())
        b.setNavigateAction(
            Action.Builder().setTitle(carContext.getString(R.string.car_go)).setOnClickListener { start(simulate = false) }.build(),
        )
        if (BuildConfig.DEBUG) {
            b.setActionStrip(ActionStrip.Builder().addAction(CarUi.textAction("Simulate") { start(simulate = true) }).build())
        }
        return b.build()
    }

    private fun trafficText(severity: Int): String = when (severity) {
        com.google.android.libraries.navigation.TimeAndDistance.DELAY_HEAVY -> " · heavy traffic"
        com.google.android.libraries.navigation.TimeAndDistance.DELAY_MEDIUM -> " · some traffic"
        else -> ""
    }

    private fun start(simulate: Boolean) {
        lifecycleScope.launch { deps.places().remember(destination, destination.address) }
        deps.guidance().startGuidance(simulate)
        screenManager.popToRoot()
    }
}
