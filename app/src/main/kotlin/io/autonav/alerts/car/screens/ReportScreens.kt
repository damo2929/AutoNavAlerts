// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.CarToast
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.App
import io.autonav.alerts.R
import io.autonav.alerts.car.NavSession
import io.autonav.alerts.data.ReportKind
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.reports.ReportSnapshot
import kotlinx.coroutines.launch

/** Step 1 of reporting: the type, in big icons (DESIGN.md §8.5). */
class ReportTypeScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val snap: ReportSnapshot,
) : Screen(carContext) {

    private val kinds = listOf(
        ReportKind.MOBILE_SEEN to R.drawable.ic_poi_camera_mobile,
        ReportKind.NEW_FIXED to R.drawable.ic_poi_camera_fixed,
        ReportKind.NEW_AVERAGE to R.drawable.ic_poi_camera_average,
        ReportKind.NEW_RED_LIGHT to R.drawable.ic_poi_camera_redlight,
        ReportKind.NEW_RED_LIGHT_SPEED to R.drawable.ic_poi_camera_redlight_speed,
        ReportKind.CAMERA_GONE to R.drawable.ic_action_close,
        ReportKind.WRONG_LIMIT to R.drawable.ic_action_report,
    )

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        kinds.forEach { (kind, icon) ->
            list.addItem(
                GridItem.Builder()
                    .setTitle(short(kind))
                    .setImage(CarUi.icon(carContext, icon), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { choose(kind) }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Report").setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    private fun choose(kind: ReportKind) {
        when (kind) {
            ReportKind.CAMERA_GONE -> {
                val fix = session.deps.alerts().lastFix.value
                val candidates = session.deps.alerts().nearby.value.map { it.poi.id to (it.poi.lat to it.poi.lon) }
                val related = session.deps.alerts().active.value?.poiId?.takeIf { it >= 0 }
                    ?: fix?.let { session.deps.reports().justPassed(it, candidates) }
                if (related == null) {
                    CarToast.makeText(carContext, "No camera found just behind you", CarToast.LENGTH_LONG).show()
                    return
                }
                ReportSaver.save(this, session, kind, snap, null, related)
            }
            ReportKind.WRONG_LIMIT -> screenManager.push(ReportSpeedScreen(carContext, session, kind, snap,
                related = session.deps.alerts().active.value?.poiId?.takeIf { it >= 0 }))
            else -> screenManager.push(ReportSpeedScreen(carContext, session, kind, snap, related = null))
        }
    }

    private fun short(kind: ReportKind) = when (kind) {
        ReportKind.MOBILE_SEEN -> "Mobile"
        ReportKind.NEW_FIXED -> "Fixed"
        ReportKind.NEW_AVERAGE -> "Average speed"
        ReportKind.NEW_RED_LIGHT -> "Red light"
        ReportKind.NEW_RED_LIGHT_SPEED -> "Red light + speed"
        ReportKind.CAMERA_GONE -> "Not there"
        ReportKind.WRONG_LIMIT -> "Wrong limit"
        else -> kind.name
    }
}

/** Step 2: optional speed limit (DESIGN.md §8.5). */
class ReportSpeedScreen(
    carContext: CarContext,
    private val session: NavSession,
    private val kind: ReportKind,
    private val snap: ReportSnapshot,
    private val related: Long?,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val speeds = if (session.imperial) listOf(20, 30, 40, 50, 60, 70) else listOf(30, 50, 60, 80, 100, 120)
        val list = ItemList.Builder()
        speeds.forEach { s ->
            list.addItem(GridItem.Builder().setTitle("$s").setImage(CarUi.speedSign("$s"), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { ReportSaver.save(this, session, kind, snap, s, related) }.build())
        }
        list.addItem(GridItem.Builder().setTitle("Skip").setImage(CarUi.icon(carContext, R.drawable.ic_action_skip), GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener { ReportSaver.save(this, session, kind, snap, null, related) }.build())
        return GridTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Speed limit").setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Saves a report, returns to the map, and offers Undo for 5 s. */
object ReportSaver {
    fun save(screen: Screen, session: NavSession, kind: ReportKind, snap: ReportSnapshot, speed: Int?, related: Long?) {
        val ctx = screen.carContext
        App.appScope.launch {
            val id = session.deps.reports().create(kind, snap, speed, related)
            val text = CameraReportRepository.label(kind).removeSuffix(" (reported)") + " reported" +
                (speed?.let { " ($it)" } ?: "") + ". Sent after your drive."
            screen.lifecycleScope.launch {
                screen.screenManager.popToRoot()
                if (ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                    val alertId = 900_000 + (id % 1000).toInt()
                    ctx.getCarService(AppManager::class.java).showAlert(
                        Alert.Builder(alertId, CarText.create(text), 5_000)
                            .addAction(Action.Builder().setTitle("Undo").setOnClickListener {
                                App.appScope.launch { session.deps.reports().undo(id) }
                                ctx.getCarService(AppManager::class.java).dismissAlert(alertId)
                            }.build())
                            .build(),
                    )
                } else {
                    CarToast.makeText(ctx, text, CarToast.LENGTH_LONG).show()
                }
            }
        }
    }
}
