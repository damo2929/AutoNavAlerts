// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Toggle
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.R
import io.autonav.alerts.testing.apiLevel
import io.autonav.alerts.testing.iconRes
import io.autonav.alerts.testing.lines
import io.autonav.alerts.testing.persistent
import io.autonav.alerts.testing.tap
import io.autonav.alerts.testing.titleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared car-screen builders as values (DESIGN.md §8, §8.3, §12.1): every pushed screen's header has the
 * host's Back and nothing else; a loading list is the host's spinner; a row carries only the parts it is given; a
 * contact row reads "Name" over "Home · 12 High St · 3.2 mi"; the fallback alert card has its text, subtitle and
 * text actions in order; the strip actions are persistent when asked. Hard rule 2 (nothing on the car screen that
 * doesn't work, nothing twice) starts here: no builder adds an action the caller didn't ask for. JVM test thread.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class CarUiTemplateTest {
    private val car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext()).apply { apiLevel(7) }

    @Test fun headerHasTitleAndOnlyTheHostsBack() {
        val h = CarUi.header("Recents")
        assertEquals("Recents", h.title.toString())
        assertSame(Action.BACK, h.startHeaderAction)
        assertTrue("no end actions of ours", h.endHeaderActions.isEmpty())
    }

    @Test fun loadingIsTheHostsSpinnerUnderTheHeaderWithNoRowsOrActions() {
        val t = CarUi.loading(CarUi.header("Contacts"))
        assertTrue(t.isLoading)
        assertEquals("Contacts", t.header!!.title.toString())
        assertNull(t.singleList)
        assertTrue(t.actions.isEmpty())
    }

    @Test fun listKeepsItsRowsInOrder() {
        val items = ItemList.Builder().addItem(CarUi.row("one")).addItem(CarUi.row("two")).build()
        val t = CarUi.list(CarUi.header("Alerts"), items)
        assertFalse(t.isLoading)
        assertEquals(listOf("one", "two"), t.singleList!!.items.map { (it as androidx.car.app.model.Row).titleText })
    }

    /**
     * The Car App Library 1.7 `ListTemplate` takes only floating action buttons (`ACTIONS_CONSTRAINTS_FAB`: no
     * custom title, and an icon with a background colour), so neither a text action such as Refresh or Clear all
     * nor a plain icon action can be passed to [CarUi.list]: the builder throws. Pinned as found:
     * `AlertsRecordsScreen` and `RecentsScreen` pass text actions (see their tests).
     */
    @Test fun listRefusesTheActionsCarUiBuildsAsTheLibraryDoes() {
        val text = runCatching { CarUi.list(CarUi.header("Alerts"), ItemList.Builder().build(), CarUi.textAction("Refresh") { }) }.exceptionOrNull()
        assertTrue("got $text", text is IllegalArgumentException && "custom titles" in text.message.orEmpty())
        val icon = runCatching { CarUi.list(CarUi.header("Alerts"), ItemList.Builder().build(), CarUi.action(car, R.drawable.ic_action_delete) { }) }.exceptionOrNull()
        assertTrue("got $icon", icon is IllegalArgumentException && "background color" in icon.message.orEmpty())
    }

    @Test fun listWithoutActionsHasNone() {
        val t = CarUi.list(CarUi.header("POI layers"), ItemList.Builder().build())
        assertTrue(t.actions.isEmpty())
    }

    @Test fun plainRowHasTitleOnlyAndNothingToTap() {
        val r = CarUi.row("Searching…")
        assertEquals("Searching…", r.titleText)
        assertTrue(r.lines.isEmpty())
        assertNull(r.image)
        assertNull(r.toggle)
        assertTrue(r.actions.isEmpty())
        assertNull("a row that does nothing takes no tap (hard rule 2)", r.onClickDelegate)
    }

    @Test fun rowDropsNullLinesAndKeepsTheRestInOrder() {
        val r = CarUi.row("Home", null, "12 High St", null, "3.2 mi")
        assertEquals(listOf("12 High St", "3.2 mi"), r.lines)
    }

    @Test fun rowWithIconResourceRunsItsClick() {
        var taps = 0
        val r = CarUi.row(car, "Recents", R.drawable.ic_action_recents, "Your last places") { taps++ }
        assertEquals(R.drawable.ic_action_recents, r.iconRes)
        assertEquals(listOf("Your last places"), r.lines)
        r.tap()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, taps)
    }

    @Test fun rowWithToggleShowsItsStateAndNoClick() {
        val r = CarUi.row("Satellite map", toggle = Toggle.Builder { }.setChecked(true).build())
        assertTrue(r.toggle!!.isChecked)
        assertNull(r.onClickDelegate)
    }

    @Test fun rowWithSecondaryActionCarriesExactlyThatOne() {
        val delete = CarUi.action(car, R.drawable.ic_action_delete) { }
        val r = CarUi.row("Tesco", "Banbury", action = delete) { }
        assertEquals(1, r.actions.size)
        assertEquals(R.drawable.ic_action_delete, r.actions.single().iconRes)
    }

    @Test fun contactRowIsNameOverPartsJoinedWithTheContactIcon() {
        val r = CarUi.contactRow(car, "Mum", "Home", "12 High St", "3.2 mi") { }
        assertEquals("Mum", r.titleText)
        assertEquals(listOf("Home · 12 High St · 3.2 mi"), r.lines)
        assertEquals(R.drawable.ic_action_contact, r.iconRes)
    }

    @Test fun contactRowLeavesOutAnUnknownDistance() {
        val r = CarUi.contactRow(car, "Dave Smith", "Work", "1 Mill Lane", null) { }
        assertEquals(listOf("Work · 1 Mill Lane"), r.lines)
    }

    @Test fun alertCardHasTextSubtitleDurationAndActionsInOrder() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val a = CarUi.alert(42, "Fixed camera 30", 8_000, CarUi.icon(bmp), "140 yd · 30 mph", "Dismiss" to {}, "Mute 30 min" to {})
        assertEquals(42, a.id)
        assertEquals("Fixed camera 30", a.title.toString())
        assertEquals("140 yd · 30 mph", a.subtitle.toString())
        assertEquals(8_000, a.durationMillis)
        assertEquals(listOf("Dismiss", "Mute 30 min"), a.actions.map { it.title.toString() })
    }

    @Test fun alertCardWithoutSubtitleOrIconLeavesThemOut() {
        val a = CarUi.alert(900_007, "Camera reported. Check it on your phone after the drive.", 5_000, actions = arrayOf("Undo" to {}))
        assertNull("an empty subtitle is not sent", a.subtitle)
        assertNull(a.icon)
        assertEquals(listOf("Undo"), a.actions.map { it.title.toString() })
        assertNull(CarUi.alert(1, "x", 5_000, subtitle = "").subtitle)
    }

    @Test fun iconActionIsPersistentOnlyWhenAsked() {
        val strip = CarUi.action(car, R.drawable.ic_action_search, persistent = true) { }
        val plain = CarUi.action(car, R.drawable.ic_action_zoom_in) { }
        assertTrue(strip.persistent)
        assertFalse(plain.persistent)
        assertNull("icon-only: no title", plain.title)
        assertEquals(R.drawable.ic_action_search, strip.iconRes)
    }

    @Test fun iconActionCanCarryATitle() {
        assertEquals("Delete", CarUi.action(car, R.drawable.ic_action_delete, title = "Delete") { }.title.toString())
    }

    @Test fun textActionIsTitleOnly() {
        val a = CarUi.textAction("Go") { }
        assertEquals("Go", a.title.toString())
        assertNull(a.icon)
        assertFalse(a.persistent)
        assertTrue(CarUi.textAction("Go", persistent = true) { }.persistent)
    }

    @Test fun listLimitIsWhatTheHostAllowsOrSix() {
        val limit = CarUi.listLimit(car)
        assertTrue("host limit or the default 6, got $limit", limit >= 6)
    }

    @Test fun formatDistanceImperialYardsCutDownToTheTenUnderAFifthOfAMile() {
        assertEquals("0 yd", CarUi.formatDistance(0, imperial = true))
        assertEquals("100 yd", CarUi.formatDistance(100, imperial = true))
        // 108.3 yd: cut down to the ten below, where TripMapper.distance rounds to the nearest (110).
        assertEquals("100 yd", CarUi.formatDistance(99, imperial = true))
        assertEquals("350 yd", CarUi.formatDistance(321, imperial = true))
    }

    @Test fun formatDistanceImperialTenthsOfAMileFromAFifth() {
        assertEquals("0.2 mi", CarUi.formatDistance(322, imperial = true))
        assertEquals("3.2 mi", CarUi.formatDistance(5_150, imperial = true))
        // Tenths even past 10 mi, where the host's own cards (TripMapper.distance) go to whole miles.
        assertEquals("12.4 mi", CarUi.formatDistance(20_000, imperial = true))
    }

    @Test fun formatDistanceMetricMetresThenTenthsOfAKilometre() {
        assertEquals("999 m", CarUi.formatDistance(999, imperial = false))
        assertEquals("1.0 km", CarUi.formatDistance(1_000, imperial = false))
        assertEquals("12.3 km", CarUi.formatDistance(12_340, imperial = false))
    }
}
