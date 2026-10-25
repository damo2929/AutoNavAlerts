// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.core.pgw

import io.autonav.alerts.core.poi.DirectionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The "Send Entry" fields of a reviewed camera report (DESIGN.md §8.5 table): field order, five
 * decimal places with a dot whatever the phone's language, the speed as the form's code, blanks
 * for an unknown camera id or heading, and the country. Made-up positions.
 */
class PgwSubmissionTest {
    private val report = PgwSubmission(4, "3209", 53.123456, -1.987654, 2, 40, 274, DirectionType.BOTH, "Van in lay-by", countryCode = 1)

    @Test
    fun fieldsInTheTablesOrder() {
        assertEquals(
            listOf("Action", "idCam", "Latitude", "Longitude", "Type", "Speed", "Heading", "Direction", "UsrComments", "mapCountry2", "SendEntry"),
            report.formFields().map { it.first },
        )
        assertEquals(
            listOf("4", "3209", "53.12346", "-1.98765", "2", "3", "274", "2", "Van in lay-by", "1", "Send Entry"),
            report.formFields().map { it.second },
        )
    }

    @Test
    fun coordinatesUseADotInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            for (l in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(l)
                val f = report.formFields().toMap()
                assertEquals(l.toString(), "53.12346", f["Latitude"])
                assertEquals(l.toString(), "-1.98765", f["Longitude"])
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun unknownsAreBlankOrTheUnknownCode() {
        val f = PgwSubmission(1, null, 54.0, -6.0, 0, null, null, DirectionType.ONE_WAY, "").formFields().toMap()
        assertEquals("", f["idCam"])
        assertEquals("", f["Heading"])
        assertEquals("0", f["Speed"])
        assertEquals("0", f["Direction"])
        assertEquals("0", f["mapCountry2"])
        assertEquals("54.00000", f["Latitude"])
        assertEquals("-6.00000", f["Longitude"])
    }
}
