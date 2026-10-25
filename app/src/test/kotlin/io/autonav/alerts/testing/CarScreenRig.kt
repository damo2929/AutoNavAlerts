// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.testing

import android.app.Application
import android.os.Looper
import androidx.car.app.HandshakeInfo
import androidx.car.app.Screen
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.autonav.alerts.alerts.AlertCoordinator
import io.autonav.alerts.alerts.AlertDispatcher
import io.autonav.alerts.alerts.NavTts
import io.autonav.alerts.car.CarDeps
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.contacts.ContactsSource
import io.autonav.alerts.data.AppDatabase
import io.autonav.alerts.data.IconRepository
import io.autonav.alerts.data.LimitSource
import io.autonav.alerts.data.PoiRepository
import io.autonav.alerts.diag.DriveRecorder
import io.autonav.alerts.diag.EventLog
import io.autonav.alerts.map.MapSurface
import io.autonav.alerts.nav.AdaptiveLocationSource
import io.autonav.alerts.nav.CarDataSource
import io.autonav.alerts.nav.DirectionsSpeaker
import io.autonav.alerts.nav.EcoRoutes
import io.autonav.alerts.nav.GuidanceController
import io.autonav.alerts.nav.NavigatorHolder
import io.autonav.alerts.nav.RouteView
import io.autonav.alerts.osm.SpeedLimitRepository
import io.autonav.alerts.places.PlacesSearch
import io.autonav.alerts.reports.CameraReportRepository
import io.autonav.alerts.reports.DrivingState
import io.autonav.alerts.settings.AppSettings
import io.autonav.alerts.settings.MapsKeyCreator
import io.autonav.alerts.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The car-screen test rig (DESIGN.md §8, §15.1): a Car App Library `TestCarContext`, the app's real singletons
 * built by hand over an in-memory Room database (what Hilt would inject), and a [CarScreenHost] the screens take
 * in place of `NavSession`. Templates are then read as values, and every `invalidate()` a screen makes shows up
 * as one more template in [TestAppManager] (hard rule 3 is counted there). Runs on Robolectric's main looper,
 * which the helpers drain; nothing here touches the Navigation SDK or the network. Pins no hard rule itself:
 * it is the ground the hard-rule 2 and 3 tests stand on.
 */
class CarScreenRig(imperial: Boolean = true, apiLevel: Int = CarAppApiLevels.LEVEL_7) {
    val app: Application = ApplicationProvider.getApplicationContext()
    val car: TestCarContext = TestCarContext.createCarContext(app).apply { apiLevel(apiLevel) }
    private val direct = Executor { it.run() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
        .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
    val deps = Deps(app, db)
    val host = Host(deps, car, imperial)

    init {
        // DataStore is one per process, so each test starts from the defaults (§13), never a previous test's.
        settings { AppSettings() }
    }

    /** The real singletons, as `CarDeps` hands them to the screens. */
    class Deps(private val app: Application, private val db: AppDatabase) : CarDeps {
        val log by lazy { EventLog(app) }
        val store by lazy { SettingsStore(app) }
        val nav by lazy { NavigatorHolder(app, store) }
        val tts by lazy { NavTts(app, log) }
        val iconRepo by lazy { IconRepository(app) }
        val poiRepo by lazy { PoiRepository(app, db, iconRepo) }
        val placesSearch by lazy { PlacesSearch(nav, db, log) }
        val contactsSource by lazy { ContactsSource(app, db, placesSearch) }
        val recorder by lazy { DriveRecorder(app, log, store) }
        val car by lazy { CarDataSource(log, recorder) }
        val loc by lazy { AdaptiveLocationSource(app, car, log, recorder) }
        val guide by lazy {
            GuidanceController(app, nav, loc, store, EcoRoutes(app, store, MapsKeyCreator(app)), log, DirectionsSpeaker(tts, loc, store, log), tts)
        }
        val dispatch by lazy { AlertDispatcher(app, tts, iconRepo, log) }
        val limits by lazy { SpeedLimitRepository(app) }
        val coordinator by lazy { AlertCoordinator(loc, poiRepo, store, guide, dispatch, car, log, tts, limits) }
        val reportRepo by lazy { CameraReportRepository(app, db, poiRepo) }
        val drivingState by lazy { DrivingState(app) }

        override fun navigator() = nav
        override fun guidance() = guide
        override fun location() = loc
        override fun alerts() = coordinator
        override fun dispatcher() = dispatch
        override fun tts() = tts
        override fun icons() = iconRepo
        override fun pois() = poiRepo
        override fun places() = placesSearch
        override fun reports() = reportRepo
        override fun settings() = store
        override fun carData() = car
        override fun driving() = drivingState
        override fun contacts() = contactsSource
        override fun speedLimits() = limits
        override fun eventLog() = log
        override fun driveRecorder() = recorder

        /** Puts the route state the screens collect (guiding, choices…) where `GuidanceController` keeps it. */
        fun setRoute(view: RouteView) { guide.privateFlow<RouteView>("_route").value = view }
    }

    /** The screens' session, as `NavSession` is to them; [limit] is what a report would capture. */
    class Host(override val deps: CarDeps, car: TestCarContext, override var imperial: Boolean) : CarScreenHost {
        override val surface: MapSurface by lazy { MapSurface(car) { false } }
        var limit: Pair<Int?, LimitSource> = null to LimitSource.NONE
        override fun currentLimit() = limit
    }

    val appManager: TestAppManager get() = car.getCarService(TestAppManager::class.java)
    val screens: TestScreenManager get() = car.getCarService(TestScreenManager::class.java)

    /** Settings written through the store, so every screen reading it sees them, waited for. */
    fun settings(change: (AppSettings) -> AppSettings) = runBlocking { deps.store.update(change) }

    /** Drives [screen] to [state] (pushed onto the test screen manager on the way), then drains the main looper. */
    fun <S : Screen> start(screen: S, state: Lifecycle.State = Lifecycle.State.RESUMED): ScreenController =
        ScreenController(screen).moveToState(state).also { idle() }

    /** A root screen under the one being tested, so its own `pop()` has somewhere to go back to (the host never pops the root). */
    fun root() {
        start(object : Screen(car) {
            override fun onGetTemplate(): Template = androidx.car.app.navigation.model.NavigationTemplate.Builder()
                .setActionStrip(androidx.car.app.model.ActionStrip.Builder().addAction(androidx.car.app.model.Action.APP_ICON).build()).build()
        })
    }

    /** Runs what is due on the main looper. */
    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Advances the main looper's clock by [ms] (coroutine `delay`s on Main resume). */
    fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** Drains the main looper until [done], for work that hops to IO (DataStore, the contacts provider) and back. */
    fun awaitUntil(timeoutMs: Long = 5_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "condition not met within $timeoutMs ms" }
            Thread.sleep(5)
        }
    }

    /** How many templates the host has been sent for [screen] by `invalidate()`. */
    fun invalidations(screen: Screen): Int = appManager.templatesReturned.count { it.first === screen }
}

/** Tells the car context which car API level the host speaks (row actions need 6, alerts and map taps 5). */
@Suppress("RestrictedApi")
fun TestCarContext.apiLevel(level: Int) = updateHandshakeInfo(HandshakeInfo("com.google.android.projection.gearhead", level))

/** A private field of this object or a superclass, by name. */
fun Any.field(name: String): java.lang.reflect.Field {
    var c: Class<*>? = javaClass
    while (c != null) {
        runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
        c = c.superclass
    }
    error("no field $name on $javaClass")
}

/** Sets a private field: the screen state a test can't reach through the Navigation SDK or Places. */
fun Any.setPrivate(name: String, value: Any?) = field(name).set(this, value)

/** A private `MutableStateFlow` behind a public `StateFlow`. */
@Suppress("UNCHECKED_CAST")
fun <T> Any.privateFlow(name: String): MutableStateFlow<T> = field(name).get(this) as MutableStateFlow<T>

/** The rows a [io.autonav.alerts.car.screens.LoadingListScreen] builds for [items], read even where the template itself can't be built. */
fun Screen.rowsFor(items: Any): List<Row> {
    val m = javaClass.declaredMethods.single { it.name == "addRows" && it.parameterCount == 2 && it.parameterTypes[1] != Any::class.java }
        .apply { isAccessible = true }
    val b = ItemList.Builder()
    m.invoke(this, b, items)
    return b.build().rows
}

/** The template actions a [io.autonav.alerts.car.screens.LoadingListScreen] offers for [items]. */
@Suppress("UNCHECKED_CAST")
fun Screen.actionsFor(items: Any): List<androidx.car.app.model.Action> {
    val m = javaClass.declaredMethods.single { it.name == "actions" && it.parameterCount == 1 && it.parameterTypes[0] != Any::class.java }
        .apply { isAccessible = true }
    return m.invoke(this, items) as List<androidx.car.app.model.Action>
}

/** A list template's single list. */
val Template.list: ItemList get() = (this as ListTemplate).singleList!!

/** The rows of an item list. */
val ItemList.rows: List<Row> get() = items.map { it as Row }

/** A row's title as text. */
val Row.titleText: String get() = title.toString()

/** A row's text lines. */
val Row.lines: List<String> get() = texts.map(CarText::toString)

/** The header title of a list template. */
val Template.headerTitle: String get() = (this as ListTemplate).header!!.title.toString()

/** The host's done-callback, ignored: the tests read the effect of a tap, not the host's acknowledgement. */
private val done = object : androidx.car.app.OnDoneCallback {}

/** A tap on a row, as the host sends it. */
fun Row.tap() = onClickDelegate!!.sendClick(done)

/** A tap on an action, as the host sends it. */
fun androidx.car.app.model.Action.tap() = onClickDelegate!!.sendClick(done)

/** A row's toggle switched to [on], as the host sends it. */
fun Row.switch(on: Boolean) = toggle!!.onCheckedChangeDelegate.sendCheckedChange(on, done)

/** A row's image as a drawable resource id (0 for a bitmap or none). */
val Row.iconRes: Int get() = image?.icon?.takeIf { it.type == androidx.core.graphics.drawable.IconCompat.TYPE_RESOURCE }?.resId ?: 0

/** An action's icon as a drawable resource id (0 for none). */
val androidx.car.app.model.Action.iconRes: Int get() = icon?.icon?.takeIf { it.type == androidx.core.graphics.drawable.IconCompat.TYPE_RESOURCE }?.resId ?: 0

/** True when the host keeps the action on screen (`Action.FLAG_IS_PERSISTENT`). */
val androidx.car.app.model.Action.persistent: Boolean get() = flags and androidx.car.app.model.Action.FLAG_IS_PERSISTENT != 0

/**
 * The address book as the contacts provider would serve it (DESIGN.md §8.3 "Source"): `StructuredPostal` rows of
 * id, name, type, label and formatted address. Every query returns them all; `ContactsSource` filters and orders.
 */
class FakeContactsProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor =
        android.database.MatrixCursor(projection ?: COLUMNS).apply { rows.forEach { addRow(it) } }
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        private val COLUMNS = arrayOf("_id", "display_name", "data2", "data3", "data1")
        /** id, name, type (1 home, 2 work, 3 other), custom label, formatted address. */
        val rows = mutableListOf<Array<Any?>>()

        /** Serves [entries] as the address book and grants READ_CONTACTS, so `ContactsSource.available` is true. */
        fun install(app: Application, vararg entries: Array<Any?>) {
            rows.clear()
            rows += entries
            org.robolectric.Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
            shadowOf(app).grantPermissions(android.Manifest.permission.READ_CONTACTS)
        }
    }
}
