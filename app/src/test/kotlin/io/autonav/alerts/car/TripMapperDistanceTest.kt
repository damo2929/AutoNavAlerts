// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.car

import androidx.car.app.model.Distance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The turn card's and travel-estimate card's distances as the host shows them (DESIGN.md §8 "Navigating",
 * §13 units; [TripMapper.distance]): whole miles from 10 mi, tenths from 0.2 mi, else yards to the nearest 10;
 * metric the same way with kilometres and metres to the nearest 10. `displayKey` compares this rounded form,
 * so a metre of drift doesn't re-send the template (hard rule 3).
 */
class TripMapperDistanceTest {
    private fun imperial(m: Int) = TripMapper.distance(m, imperial = true).let { it.displayDistance to it.displayUnit }
    private fun metric(m: Int) = TripMapper.distance(m, imperial = false).let { it.displayDistance to it.displayUnit }

    @Test fun yardsToTheNearestTenUnderAFifthOfAMile() {
        assertEquals(0.0 to Distance.UNIT_YARDS, imperial(0))
        assertEquals(110.0 to Distance.UNIT_YARDS, imperial(100))
        assertEquals(20.0 to Distance.UNIT_YARDS, imperial(18))
        // 321 m is 0.1995 mi: still yards, 351 yd → 350.
        assertEquals(350.0 to Distance.UNIT_YARDS, imperial(321))
    }

    @Test fun tenthsOfAMileFromAFifthUpToTen() {
        assertEquals(0.2 to Distance.UNIT_MILES_P1, imperial(322))
        assertEquals(1.0 to Distance.UNIT_MILES_P1, imperial(1609))
        assertEquals(2.5 to Distance.UNIT_MILES_P1, imperial(4023))
        // Just under 10 mi rounds up to "10.0" in tenths.
        assertEquals(10.0 to Distance.UNIT_MILES_P1, imperial(16093))
    }

    @Test fun wholeMilesFromTen() {
        assertEquals(10.0 to Distance.UNIT_MILES, imperial(16094))
        assertEquals(25.0 to Distance.UNIT_MILES, imperial(40234))
        assertEquals(26.0 to Distance.UNIT_MILES, imperial(41039))
    }

    @Test fun metresToTheNearestTenUnderAKilometre() {
        assertEquals(0.0 to Distance.UNIT_METERS, metric(4))
        // Half rounds up.
        assertEquals(10.0 to Distance.UNIT_METERS, metric(5))
        assertEquals(250.0 to Distance.UNIT_METERS, metric(249))
        // 995–999 m show as "1000 m", the step before "1.0 km".
        assertEquals(1000.0 to Distance.UNIT_METERS, metric(999))
    }

    @Test fun kilometresInTenthsThenWhole() {
        assertEquals(1.0 to Distance.UNIT_KILOMETERS_P1, metric(1000))
        assertEquals(1.0 to Distance.UNIT_KILOMETERS_P1, metric(1049))
        assertEquals(1.1 to Distance.UNIT_KILOMETERS_P1, metric(1050))
        assertEquals(10.0 to Distance.UNIT_KILOMETERS_P1, metric(9999))
        assertEquals(10.0 to Distance.UNIT_KILOMETERS, metric(10_000))
        assertEquals(10.0 to Distance.UNIT_KILOMETERS, metric(10_499))
        assertEquals(11.0 to Distance.UNIT_KILOMETERS, metric(10_500))
    }

    @Test fun driftWithinOneStepShowsTheSame() {
        // The no-shake rule leans on this: 3 m of GPS drift on a 1.4 mi leg is the same card.
        assertEquals(imperial(2253), imperial(2256))
        assertEquals(metric(5321), metric(5324))
    }
}
