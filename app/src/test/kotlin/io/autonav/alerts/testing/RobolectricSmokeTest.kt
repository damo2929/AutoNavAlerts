// SPDX-License-Identifier: Apache-2.0
package io.autonav.alerts.testing

import android.app.Application
import android.content.Context
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Proves the Robolectric tier runs on this machine (DESIGN.md §15.1): a plain Application context, the Car App
 * Library's TestCarContext and a template built as a value. Runs on the JVM test thread. Upholds nothing by itself;
 * every screen test in this tier builds on these three facts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class RobolectricSmokeTest {
    @Test
    fun applicationContextAndCarContextAreAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(context.packageName)
        val car = TestCarContext.createCarContext(context)
        assertNotNull(car.getCarService(androidx.car.app.CarContext.APP_SERVICE))
        val template = ListTemplate.Builder()
            .setTitle("Smoke")
            .setSingleList(androidx.car.app.model.ItemList.Builder().addItem(Row.Builder().setTitle("one").build()).build())
            .build()
        assertEquals("one", (template.singleList!!.items.single() as Row).title.toString())
    }
}
