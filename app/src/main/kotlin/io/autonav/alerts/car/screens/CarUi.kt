// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car.screens

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Toggle
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import io.autonav.alerts.R
import io.autonav.alerts.car.CarScreenHost
import io.autonav.alerts.contacts.ContactAddress
import io.autonav.alerts.contacts.Located
import io.autonav.alerts.data.PlaceEntity
import io.autonav.alerts.nav.Destination
import io.autonav.alerts.places.SearchHit
import kotlinx.coroutines.launch

/**
 * Small helpers shared by the car screens (DESIGN.md §8): icons, actions, headers, rows (plain, contact),
 * host alert cards and list templates built the one way, the host's list limit, distance text in the
 * display units, and the contact pick shared by Search and Contacts (§8.3). Stateless; called from the screens'
 * `onGetTemplate` on the main thread. They only build what each screen built before, so every
 * template stays as it was (hard rules 2 and 3 are the screens' own).
 */
object CarUi {
    /** A header with [title] and the host's Back, as on every pushed list and message screen. */
    fun header(title: String): Header = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()

    /** A list still loading under [header]; the host shows its own spinner. */
    fun loading(header: Header): ListTemplate = ListTemplate.Builder().setHeader(header).setLoading(true).build()

    /** A one-list template under [header], with any [actions] (Refresh…). */
    fun list(header: Header, items: ItemList, vararg actions: Action): ListTemplate =
        ListTemplate.Builder().setHeader(header).setSingleList(items).apply { actions.forEach(::addAction) }.build()

    /** A row with an icon and up to two [text] lines that runs [onClick] (More's and Search's navigation rows). */
    fun row(ctx: CarContext, title: String, @DrawableRes res: Int, vararg text: String, onClick: () -> Unit): Row =
        row(title, *text, image = icon(ctx, res), onClick = onClick)

    /**
     * Any list row the screens build: [title], the non-null [text] lines, an [image] (a small one, as the host
     * draws a bitmap untinted), a [toggle], a row [action] (car API 6) and [onClick]; each left out when null.
     */
    fun row(
        title: String, vararg text: String?, image: CarIcon? = null, toggle: Toggle? = null, action: Action? = null, onClick: (() -> Unit)? = null,
    ): Row = Row.Builder().setTitle(title).apply {
        text.filterNotNull().forEach(::addText)
        image?.let(::setImage)
        toggle?.let(::setToggle)
        action?.let(::addAction)
        onClick?.let { setOnClickListener(it) }
    }.build()

    /** A contact's address as a row (§8.3): the name over [parts] joined by " · " (each screen's own order), the contact icon. */
    fun contactRow(ctx: CarContext, name: String, vararg parts: String?, onClick: () -> Unit): Row =
        row(name, parts.filterNotNull().joinToString(" · "), image = icon(ctx, R.drawable.ic_action_contact), onClick = onClick)

    /** A host alert card (car API ≥ 5; §12.1, §8.5): [text], [subtitle] and [icon] for [durationMs], with text [actions] (title to what it does). */
    fun alert(id: Int, text: String, durationMs: Long, icon: CarIcon? = null, subtitle: String? = null, vararg actions: Pair<String, () -> Unit>): Alert =
        Alert.Builder(id, CarText.create(text), durationMs).apply {
            icon?.let(::setIcon)
            subtitle?.takeIf { it.isNotEmpty() }?.let { setSubtitle(CarText.create(it)) }
            actions.forEach { (title, onClick) -> addAction(textAction(title, onClick = onClick)) }
        }.build()

    /** A stored place as a destination (Recents, the place detail and Search's empty state, §8.2). */
    fun PlaceEntity.toDestination(): Destination = Destination(name, lat, lon, placeId)

    /**
     * A tapped contact address (§8.3): located now (the 90-day cache, else one Places Text Search billed to the
     * user's own key; never the whole address book), then the route preview; several matches go to [onChoose];
     * none gives a toast. Shared by `SearchScreen` and `ContactsScreen`, which differ only in [onChoose].
     */
    fun pickContact(screen: Screen, session: CarScreenHost, c: ContactAddress, onChoose: (List<SearchHit>) -> Unit) {
        val ctx = screen.carContext
        screen.lifecycleScope.launch {
            when (val r = session.deps.contacts().locate(c, session.deps.location().lastLocation.value)) {
                is Located.Found -> screen.screenManager.push(RoutePreviewScreen(ctx, session, r.destination.copy(address = r.address)))
                is Located.Choose -> onChoose(r.options)
                Located.NotFound -> CarToast.makeText(ctx, ctx.getString(R.string.car_contact_not_found, c.name), CarToast.LENGTH_SHORT).show()
            }
        }
    }

    /** A drawable resource as a car icon; the host tints it to its theme. */
    fun icon(ctx: CarContext, @DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

    /** A bitmap as a car icon, drawn as-is (pair it with `Row.IMAGE_TYPE_SMALL` so the host doesn't tint it). */
    fun icon(bmp: android.graphics.Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()

    /** [persistent]: the host keeps it on screen instead of fading it out on the map (DESIGN.md §8). */
    fun action(ctx: CarContext, @DrawableRes res: Int, title: String? = null, persistent: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(ctx, res)).apply { title?.let { setTitle(it) } }
            .apply { if (persistent) setFlags(Action.FLAG_IS_PERSISTENT) }.setOnClickListener(onClick).build()

    /** How many rows the host lets a list show (6 if it won't say). */
    fun listLimit(ctx: CarContext): Int = runCatching {
        ctx.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6)

    /** A text-only action (Go, Yes, Refresh…); [persistent] as for [action]. */
    fun textAction(title: String, persistent: Boolean = false, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).apply { if (persistent) setFlags(Action.FLAG_IS_PERSISTENT) }.setOnClickListener(onClick).build()

    /**
     * [meters] as short row text: yards to the nearest 10 under 0.2 mi, else tenths of a mile;
     * metres under 1 km, else tenths of a kilometre. The same thresholds as `TripMapper.distance`,
     * for rows the host doesn't format itself.
     */
    fun formatDistance(meters: Int, imperial: Boolean): String = if (imperial) {
        val mi = meters / 1609.344
        if (mi < 0.2) "${(meters / 0.9144 / 10).toInt() * 10} yd" else "%.1f mi".format(mi)
    } else {
        if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0)
    }
}
