// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import io.autonav.alerts.core.poi.DirectionType
import org.jsoup.Jsoup

/**
 * Everything about PocketGPSWorld's pages lives here (DESIGN.md §6.1 "PgwConfig"), so a site change
 * is a one-file fix. Values checked against the live site on 2026-10-11.
 */
object PgwConfig {
    const val BASE = "https://www.pocketgpsworld.com/"
    const val LOGIN_PATH = "modules.php?name=Your_Account"
    const val CAMERAS_PATH = "modules.php?name=Cameras"
    const val SUBMIT_PATH = "modules.php?name=Cameras&op=SubmitCamera"
    const val RSS_URL = "https://www.pocketgpsworld.com/speed-camera-rss.php"

    /** iGO, UK, consolidated by type (one file per camera type): the package the app uses. */
    const val PACKAGE_UK_IGO_BY_TYPE = 288

    /** TomTom OV2, UK, speed zoned complete. No direction data. */
    const val PACKAGE_UK_OV2_SPEED_ZONED = 1

    /** Kept for callers that want the default package id. */
    const val PACKAGE_UK_IGO = PACKAGE_UK_OV2_SPEED_ZONED

    fun loginForm(user: String, password: String) = listOf(
        "username" to user, "user_password" to password, "op" to "login",
        "redirect" to "", "mode" to "", "f" to "", "t" to "",
    )

    fun downloadForm(idPackage: Int = PACKAGE_UK_IGO, pendingMobiles: Boolean = true) = listOf(
        "op" to "DownloadPackage", "idPackage" to idPackage.toString(),
        "getPmob" to if (pendingMobiles) "yes" else "no", "getFrance" to "no", "getSwiss" to "no",
    )

    val ZIP_LINK = Regex("""(?:https?://www\.pocketgpsworld\.com)?/?temp/users/pgpsw-speed-cams-[A-Za-z0-9]+-\(\d+-\d+\)\.zip""")

    const val RELEASE_FILE = "Release.txt"
}

/**
 * The download the user picks on the Cameras tab (DESIGN.md §5.2 / §5.2a). Both carry direction:
 * iGO as exact degrees, TomTom OV2 as a compass point in each entry's name, plus the PGW camera id.
 */
enum class PgwDownload(val idPackage: Int, val system: String, val label: String, val detail: String) {
    TOMTOM_OV2(
        PgwConfig.PACKAGE_UK_OV2_SPEED_ZONED, "TomTom OV2", "TomTom OV2",
        "UK speed zoned complete. Direction as a compass point, camera make (Gatso, Truvelo, RedSpeed…), " +
            "temporary roadworks cameras, and PGW camera ids (used when you report a known camera).",
    ),
    IGO(
        PgwConfig.PACKAGE_UK_IGO_BY_TYPE, "iGO", "iGO",
        "UK consolidated by type. Exact direction in degrees; fewer details about camera make.",
    );

    companion object {
        val DEFAULT = TOMTOM_OV2
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: DEFAULT
    }
}

object PgwPages {
    enum class LoginState { LOGGED_IN, LOGGED_OUT }

    fun loginState(html: String): LoginState {
        val doc = Jsoup.parse(html)
        val hasPasswordField = doc.select("input[name=user_password]").isNotEmpty()
        val mentionsLogout = Regex("""log\s?out""", RegexOption.IGNORE_CASE).containsMatchIn(doc.text())
        return if (!hasPasswordField && mentionsLogout) LoginState.LOGGED_IN else LoginState.LOGGED_OUT
    }

    /** Absolute URL of the generated zip, or null if the page has none (e.g. subscription expired). */
    fun findZipLink(html: String): String? {
        val m = PgwConfig.ZIP_LINK.find(html) ?: return null
        val path = m.value.substringAfter("pocketgpsworld.com").trimStart('/')
        return PgwConfig.BASE + path
    }

    /** Per-user hidden fields of the submission form (never logged or stored). */
    fun submitHiddenFields(html: String): Map<String, String> {
        val form = Jsoup.parse(html).select("form").firstOrNull { it.select("[name=idCam]").isNotEmpty() } ?: return emptyMap()
        return form.select("input[type=hidden]").associate { it.attr("name") to it.attr("value") }
            .filterKeys { it.isNotBlank() }
    }

    /** PGW submission-form speed codes: the value sent is a code, not the mph number (§8.5). */
    private val SPEED_CODES = mapOf(
        20 to "1", 25 to "14", 30 to "2", 35 to "15", 40 to "3", 45 to "16", 50 to "4", 55 to "17",
        60 to "5", 65 to "18", 70 to "6", 80 to "8", 90 to "9", 100 to "10", 110 to "11", 120 to "12",
        130 to "13", 140 to "19", 160 to "20",
    )

    fun speedCode(speed: Int?): String = speed?.let { SPEED_CODES[it] } ?: "0"

    fun directionCode(dir: DirectionType): String = if (dir == DirectionType.BOTH) "2" else "0"
}

/** A camera report as sent to PGW's "Send Entry" (DESIGN.md §8.5). */
data class PgwSubmission(
    /** 1 add, 2 change, 3 remove, 4 mobile seen again. */
    val action: Int,
    val idCam: String?,
    val lat: Double,
    val lon: Double,
    /** PGW form type code (0 not sure, 2 mobile, 4 specs, 5 red light, 10 RedSpeed...). */
    val type: Int,
    val speed: Int?,
    val heading: Int?,
    val directionType: DirectionType,
    val comments: String,
    /** 0 United Kingdom, 1 Ireland. */
    val countryCode: Int = 0,
) {
    fun formFields(): List<Pair<String, String>> = listOf(
        "Action" to action.toString(),
        "idCam" to (idCam ?: ""),
        "Latitude" to "%.5f".format(java.util.Locale.ROOT, lat),
        "Longitude" to "%.5f".format(java.util.Locale.ROOT, lon),
        "Type" to type.toString(),
        "Speed" to PgwPages.speedCode(speed),
        "Heading" to (heading?.toString() ?: ""),
        "Direction" to PgwPages.directionCode(directionType),
        "UsrComments" to comments,
        "mapCountry2" to countryCode.toString(),
        "SendEntry" to "Send Entry",
    )
}
