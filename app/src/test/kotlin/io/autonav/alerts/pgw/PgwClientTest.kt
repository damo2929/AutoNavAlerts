// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.pgw

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.autonav.alerts.core.pgw.PgwDownload
import io.autonav.alerts.core.pgw.PgwSubmission
import io.autonav.alerts.core.poi.DirectionType
import io.autonav.alerts.settings.AppSettings
import io.autonav.alerts.settings.PgwState
import io.autonav.alerts.settings.SettingsStore
import io.autonav.alerts.testing.DataFixtures
import io.autonav.alerts.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * `PgwClient` against a made-up PocketGPSWorld on MockWebServer (DESIGN.md §6.1 login, download and checks,
 * §6.2 the conditional feed GET, §6.3 the failure kinds, §8.5 the report form; the PgwClient rows of §15.2).
 * The login is invented and stored through the real `SettingsStore` with a software Keystore; hard rule 6:
 * the zips are made up. Calls run on Dispatchers.IO inside the client; the test waits with runBlocking.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class PgwClientTest {
    private val site = FakePgwSite()
    private lateinit var context: Context
    private lateinit var settings: SettingsStore
    private lateinit var client: PgwClient

    @Before fun setUp() = runBlocking<Unit> {
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        settings.update { AppSettings() }
        settings.updatePgw { PgwState() }
        settings.setPgwCredentials("driver", "secret")
        site.server.start()
        client = PgwClient(context, settings, site.base(), site.rss())
    }

    @After fun tearDown() = site.server.shutdown()

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try { block() } catch (t: Throwable) { if (t is T) return t; throw t }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test fun aLiveSavedSessionSkipsTheLoginForm() = runBlocking<Unit> {
        settings.setPgwCookies("PHPSESSID=good; path=/")
        client.ensureLoggedIn()
        assertEquals(0, site.logins())
        assertEquals("PHPSESSID=good", site.requests.single().cookie)
    }

    @Test fun anExpiredCookieLogsInOnceAndTheNewSessionIsSaved() = runBlocking<Unit> {
        settings.setPgwCookies("PHPSESSID=stale; expires=Thu, 01 Jan 2015 00:00:00 GMT; path=/")

        client.ensureLoggedIn()
        client.ensureLoggedIn()

        assertEquals(1, site.logins())
        // The expired cookie was never sent; the login is the plain PHP-Nuke form (§6.1 step 2.1).
        assertNull(site.requests.first().cookie)
        val form = site.requests.single { it.path.endsWith("Your_Account") }.body
        assertEquals("username=driver&user_password=secret&op=login&redirect=&mode=&f=&t=", form)
        assertTrue(settings.pgwCookies()!!.startsWith("PHPSESSID=good"))
        // Every request names the app (§6.1 step 2).
        assertTrue(site.requests.all { it.userAgent!!.startsWith("AutoNavAlerts/") && it.userAgent.endsWith("(Android)") })

        // A fresh client (a new process) restores the saved session instead of logging in again.
        PgwClient(context, settings, site.base(), site.rss()).ensureLoggedIn()
        assertEquals(1, site.logins())
    }

    @Test fun aWrongPasswordIsAnAuthFailure() = runBlocking<Unit> {
        site.password = "the-real-one"
        val e = thrown<PgwAuthException> { runBlocking { client.ensureLoggedIn() } }
        assertEquals("Login failed: check your username and password", e.message)
        assertEquals(1, site.logins())
        assertNull(settings.pgwCookies())
    }

    @Test fun noSavedLoginIsAnAuthFailureWithoutAnyLoginAttempt() = runBlocking<Unit> {
        settings.setPgwCredentials(null, null)
        val e = thrown<PgwAuthException> { runBlocking { client.ensureLoggedIn() } }
        assertEquals("No PocketGPSWorld login saved", e.message)
        assertEquals(0, site.logins())
    }

    @Test fun aCaptchaOnTheLoginReplyIsTreatedAsAFailedLogin() = runBlocking<Unit> {
        // §6.3 wants "Captcha / 2FA detected" as PARSE_FAILED with a browser hint; today it reads as logged out.
        site.captcha = true
        val e = thrown<PgwAuthException> { runBlocking { client.ensureLoggedIn() } }
        assertEquals("Login failed: check your username and password", e.message)
    }

    @Test fun forgettingTheSessionMeansTheNextCallLogsInAfresh() = runBlocking<Unit> {
        client.ensureLoggedIn()
        client.forgetSession()
        assertNull(settings.pgwCookies())
        client.ensureLoggedIn()
        assertEquals(2, site.logins())
    }

    @Test fun theDownloadIsRequestedAndStreamedToDisk() = runBlocking<Unit> {
        site.zip = DataFixtures.padding(80_000)
        val dest = File(client.cacheDir(), "pgw-test.zip")

        val out = client.download(dest, PgwDownload.TOMTOM_OV2)

        assertEquals(dest, out)
        assertArrayEquals(site.zip, dest.readBytes())
        val post = site.requests.single { it.method == "POST" && it.path == "/modules.php?name=Cameras" }.body
        assertEquals("op=DownloadPackage&idPackage=1&getPmob=yes&getFrance=no&getSwiss=no&System=TomTom+OV2&voicePack=noVoice", post)
        val get = site.requests.last()
        assertEquals("/temp/users/pgpsw-speed-cams-AbC12-(99-1).zip", get.path)
        dest.delete()
    }

    @Test fun theIgoPackageAsksForItsOwnPackageId() = runBlocking<Unit> {
        site.zip = DataFixtures.padding(60_000)
        client.download(File(client.cacheDir(), "igo.zip"), PgwDownload.IGO)
        val post = site.requests.single { it.method == "POST" && it.path == "/modules.php?name=Cameras" }.body
        assertTrue(post, post.contains("idPackage=288") && post.contains("System=iGO"))
    }

    @Test fun downloadFailuresAreTheKindsTheSyncActsOn() = runBlocking<Unit> {
        val dest = File(client.cacheDir(), "x.zip")
        site.downloadPage = FakePgwSite.DownloadPage.NO_LINK
        assertEquals("No download link: is your subscription active?",
            thrown<PgwSubscriptionException> { runBlocking { client.download(dest, PgwDownload.DEFAULT) } }.message)

        site.downloadPage = FakePgwSite.DownloadPage.LOGGED_OUT
        assertEquals("Logged out while downloading", thrown<PgwAuthException> { runBlocking { client.download(dest, PgwDownload.DEFAULT) } }.message)

        site.downloadPage = FakePgwSite.DownloadPage.LINK
        site.zipType = "text/html"
        site.zip = DataFixtures.padding()
        assertEquals("Download wasn't a zip (text/html)", thrown<PgwParseException> { runBlocking { client.download(dest, PgwDownload.DEFAULT) } }.message)

        site.zipType = "application/zip"
        site.zip = ByteArray(1_000)
        assertEquals("Download too small (1000 bytes)", thrown<PgwParseException> { runBlocking { client.download(dest, PgwDownload.DEFAULT) } }.message)

        site.zipCode = 500
        assertEquals("Download HTTP 500", thrown<IOException> { runBlocking { client.download(dest, PgwDownload.DEFAULT) } }.message)
    }

    @Test fun theFeedIsAConditionalGetAndTheNewestReleaseWins() = runBlocking<Unit> {
        site.feed = site.feedOf(
            "version 24.99:30 Sep 26" to "Older",
            "version 24.101:07 Oct 26" to "This release sees 20 made-up changes",
            "version 23.500:01 Jan 26" to "Oldest",
        )
        val r = client.checkFeed("\"e1\"", "Tue, 01 Jan 2099 00:00:00 GMT") as FeedResult.Releases
        assertEquals("24.101", r.newest!!.version)
        assertEquals("This release sees 20 made-up changes", r.newest!!.summary)
        assertEquals("\"e2\"", r.etag)
        assertEquals("Wed, 01 Jan 2099 00:00:00 GMT", r.lastModified)
        val sent = site.requests.single()
        assertEquals("\"e1\"", sent.ifNoneMatch)
        assertEquals("Tue, 01 Jan 2099 00:00:00 GMT", sent.ifModifiedSince)
        // The feed is public: no cookie, no login (§6.4).
        assertNull(sent.cookie)
        assertEquals(0, site.logins())

        site.feed = MockResponse().setResponseCode(304)
        assertEquals(FeedResult.NotModified, client.checkFeed("\"e2\"", null))
        site.feed = MockResponse().setResponseCode(503)
        assertEquals("Feed HTTP 503", thrown<IOException> { runBlocking { client.checkFeed(null, null) } }.message)
    }

    @Test fun aRedirectToAnotherHostIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        val elsewhere = MockWebServer().apply { start() }
        val host = if (site.server.hostName == "localhost") "127.0.0.1" else "localhost"
        val target = elsewhere.url("/steal").newBuilder().host(host).build()
        site.feed = MockResponse().setResponseCode(302).setHeader("Location", target.toString())
        try {
            client.checkFeed(null, null)
            fail("A redirect off the site's host must not be followed")
        } catch (_: Exception) {
        }
        assertEquals(0, elsewhere.requestCount)
        elsewhere.shutdown()
    }

    @Test fun aReportIsSentWithThePagesHiddenFieldsAndTheMappedForm() = runBlocking<Unit> {
        val html = client.submit(PgwSubmission(2, "3209", 52.123456, -1.5, 0, 30, 274, DirectionType.ONE_WAY, "wrong limit. Reported via AutoNavAlerts"))
        assertTrue(html.contains("Thank you"))
        val body = site.requests.last { it.method == "POST" && it.path == "/modules.php?name=Cameras" }.body
        for (field in listOf("POISubmitted" to "1", "userid" to "u-42", "Action" to "2", "idCam" to "3209", "Latitude" to "52.12346",
                "Longitude" to "-1.50000", "Speed" to "2", "Heading" to "274", "Direction" to "0", "mapCountry2" to "0")) {
            assertTrue("${field.first} in body", body.contains("name=\"${field.first}\"\r\nContent-Length: ${field.second.length}\r\n\r\n${field.second}\r\n") ||
                body.contains("name=\"${field.first}\"\r\n\r\n${field.second}\r\n"))
        }

        site.submitForm = null
        assertEquals("Submission form not found", thrown<PgwParseException> {
            runBlocking { client.submit(PgwSubmission(1, null, 52.0, -1.0, 0, null, null, DirectionType.ANY, "x")) }
        }.message)
    }
}
