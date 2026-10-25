// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.pgw

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.Collections

/**
 * A made-up PocketGPSWorld on a MockWebServer (DESIGN.md §6.1, §6.2, §15.2): the PHP-Nuke login form, the
 * Cameras page and its "Download Same Format" POST, the generated temp zip and the public RSS feed, with the
 * page shapes `PgwPages` reads (a `user_password` field when logged out, "Logout" when in, the temp-link
 * pattern). The account is invented and the zips come from the test (hard rule 6: never PGW's data). The
 * server answers on its own thread; the fields are set by the test before each call.
 */
class FakePgwSite {
    val server = MockWebServer()
    /** Every request in arrival order, for counting logins and checking form bodies. */
    val requests: MutableList<Recorded> = Collections.synchronizedList(ArrayList())

    /** One request as the site saw it: method, path, headers that matter, and the body as text. */
    data class Recorded(val method: String, val path: String, val cookie: String?, val userAgent: String?, val ifNoneMatch: String?, val ifModifiedSince: String?, val body: String)

    var user = "driver"
    var password = "secret"
    /** The download page: a temp link (the normal case), no link (subscription lapsed), or the login form (session lost). */
    var downloadPage: DownloadPage = DownloadPage.LINK
    /** A captcha on the login reply, as §6.3 fears the site might add. */
    var captcha = false
    var zip: ByteArray = ByteArray(0)
    var zipType = "application/zip"
    var zipCode = 200
    var feed: MockResponse = MockResponse().setResponseCode(304)
    /** The Send Entry form (§8.5); null makes it a page without the form. */
    var submitForm: String? = """<form method="post"><input type="hidden" name="POISubmitted" value="1"><input type="hidden" name="userid" value="u-42"><input name="idCam"></form>"""

    enum class DownloadPage { LINK, NO_LINK, LOGGED_OUT }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val path = request.path ?: ""
                requests += Recorded(request.method ?: "", path, request.getHeader("Cookie"), request.getHeader("User-Agent"),
                    request.getHeader("If-None-Match"), request.getHeader("If-Modified-Since"), body)
                val inSession = request.getHeader("Cookie")?.contains("PHPSESSID=good") == true
                return when {
                    path == "/speed-camera-rss.php" -> feed
                    path.startsWith("/temp/users/") -> MockResponse().setResponseCode(zipCode).setHeader("Content-Type", zipType).setBody(Buffer().write(zip))
                    path == "/modules.php?name=Your_Account" && request.method == "POST" -> login(body)
                    path == "/modules.php?name=Cameras&op=SubmitCamera" -> page(if (inSession) submitForm ?: "<p>Logout</p>" else LOGIN_FORM)
                    path == "/modules.php?name=Cameras" && request.method == "GET" -> page(if (inSession) CAMERAS else LOGIN_FORM)
                    path == "/modules.php?name=Cameras" && request.method == "POST" -> when {
                        !inSession -> page(LOGIN_FORM)
                        body.contains("op=DownloadPackage") -> page(when (downloadPage) {
                            DownloadPage.LINK -> "$CAMERAS<a href=\"/temp/users/pgpsw-speed-cams-AbC12-(99-1).zip\">Download</a>"
                            DownloadPage.NO_LINK -> "$CAMERAS<p>Your subscription has expired.</p>"
                            DownloadPage.LOGGED_OUT -> LOGIN_FORM
                        })
                        else -> page("<p>Thank you for your submission. Logout</p>")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun login(body: String): MockResponse {
        val ok = body.contains("username=$user") && body.contains("user_password=$password") && body.contains("op=login")
        if (captcha) return page("$LOGIN_FORM<div class=\"g-recaptcha\">Please prove you are human</div>")
        return if (ok) page("<p>Welcome back. <a href=\"/logout\">Logout</a> | My Account</p>").addHeader("Set-Cookie", "PHPSESSID=good; Path=/")
        else page("<p>Login incorrect.</p>$LOGIN_FORM")
    }

    private fun page(html: String) = MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>$html</body></html>")

    /** The site root the client is pointed at, ending in "/" like `PgwConfig.BASE`. */
    fun base(): String = server.url("/").toString()
    fun rss(): String = server.url("/speed-camera-rss.php").toString()
    fun logins(): Int = requests.count { it.path == "/modules.php?name=Your_Account" }

    /** An RSS 2.0 feed of made-up release items (§6.2): each a title and a description. */
    fun feedOf(vararg items: Pair<String, String>, etag: String? = "\"e2\"", lastModified: String? = "Wed, 01 Jan 2099 00:00:00 GMT"): MockResponse =
        MockResponse().setHeader("Content-Type", "application/rss+xml").apply {
            etag?.let { setHeader("ETag", it) }
            lastModified?.let { setHeader("Last-Modified", it) }
        }.setBody(
            "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Made-up feed</title>" +
                items.joinToString("") { (t, d) -> "<item><title>$t</title><description>$d</description><link>https://example.invalid/</link></item>" } +
                "</channel></rss>",
        )

    companion object {
        const val LOGIN_FORM = "<form action=\"modules.php?name=Your_Account\" method=\"post\"><input name=\"username\"><input type=\"password\" name=\"user_password\"></form>"
        const val CAMERAS = "<h1>Speed cameras</h1><a href=\"/logout\">Logout</a>"
    }
}
