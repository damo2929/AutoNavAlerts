// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.places

import io.autonav.alerts.places.LabeledPlaces.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabeledPlacesTest {
    @Test fun labeledPlacesWithPositions() {
        val p = LabeledPlaces.parse("""{"type":"FeatureCollection","features":[
            {"geometry":{"coordinates":[-1.4701,53.3811],"type":"Point"},"properties":{"address":"1 High St, Sheffield S1 2AB","name":"Home"},"type":"Feature"},
            {"geometry":{"coordinates":[-1.4746,52.9225],"type":"Point"},"properties":{"address":"Derby DE1","name":"Work"},"type":"Feature"},
            {"geometry":{"coordinates":[-0.1281,51.5080],"type":"Point"},"properties":{"name":"Gym"},"type":"Feature"}]}""")
        assertEquals(listOf(Label.HOME, Label.WORK, Label.OTHER), p.map { it.label })
        assertEquals(53.3811, p[0].lat!!, 1e-9)
        assertEquals(-1.4701, p[0].lon!!, 1e-9)
        assertEquals("1 High St, Sheffield S1 2AB", p[0].address)
    }

    @Test fun dataPortabilityWithSemanticTypeAndNoPosition() {
        val p = LabeledPlaces.parse("""{"features":[
            {"geometry":{"type":"Point"},"property":{"name":"My house","address":"2 Low Rd","semantic_type":"TYPE_HOME"}},
            {"geometry":{"coordinates":[0,0],"type":"Point"},"properties":{"name":"Office","semanticType":"TYPE_WORK","address":"3 Mill Ln"}}]}""")
        assertEquals(listOf(Label.HOME, Label.WORK), p.map { it.label })
        assertNull(p[0].lat)
        assertNull(p[1].lat) // [0, 0] means no position
        assertEquals("3 Mill Ln", p[1].address)
    }

    @Test fun junkIsEmpty() {
        assertEquals(emptyList<LabeledPlaces.Labeled>(), LabeledPlaces.parse("not json"))
        assertEquals(emptyList<LabeledPlaces.Labeled>(), LabeledPlaces.parse("{}"))
    }
}
