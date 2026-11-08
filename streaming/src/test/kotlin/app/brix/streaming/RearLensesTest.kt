package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class RearLensesTest {
    @Test
    fun duplicateZoomLabelsCollapseToClosestCameraAndKeepVirtualTele() {
        val lenses = buildRearLenses(
            listOf(
                RearCameraInfo(id = "0", focalLength = 8f, maxZoom = 4f),
                RearCameraInfo(id = "1", focalLength = 2.4f, maxZoom = 1f),
                RearCameraInfo(id = "2", focalLength = 7.95f, maxZoom = 1f),
                RearCameraInfo(id = "3", focalLength = 7.9f, maxZoom = 1f),
            ),
        )

        assertEquals(listOf("0.3x", "1.0x", "3.0x"), lenses.map { it.label })
        assertEquals(listOf("1", "0", "0@3.0"), lenses.map { it.id })
        assertEquals(3f, lenses.last().zoom)
    }

    @Test
    fun realTeleLensPreventsDuplicateVirtualTele() {
        val lenses = buildRearLenses(
            listOf(
                RearCameraInfo(id = "0", focalLength = 6f, maxZoom = 5f),
                RearCameraInfo(id = "1", focalLength = 18f, maxZoom = 1f),
            ),
        )

        assertEquals(listOf("1.0x", "3.0x"), lenses.map { it.label })
        assertEquals(listOf("0", "1"), lenses.map { it.id })
    }
}
