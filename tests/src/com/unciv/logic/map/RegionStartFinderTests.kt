package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions
import com.unciv.logic.map.mapgenerator.mapregions.RegionStartFinder
import org.junit.Assert.assertEquals
import org.junit.Test

/** AssignStartingPlots:FindStart, EvaluateCandidatePlot and PlaceImpactAndRipples */
class RegionStartFinderTests {

    @Test
    fun `the center and middle areas of a region are 34 and 67 percent, the margin rounded down`() {
        val region = Rectangle(10f, 4f, 20f, 15f)
        // center: margin floor((20 - 6.8) / 2) = 6 and floor((15 - 5.1) / 2) = 4; middle: floor(3.3) = 3 and floor(2.475) = 2
        assertEquals(Rectangle(16f, 8f, 8f, 7f), RegionStartFinder.getCentralRectangle(region, 34))
        assertEquals(Rectangle(13f, 6f, 14f, 11f), RegionStartFinder.getCentralRectangle(region, 67))
    }

    @Test
    fun `production in the second ring only counts up to twice the food`() {
        assertEquals(3, RegionStartFinder.effectiveSecondRingProduction(totalFood = 4, totalProd = 3))
        assertEquals(8, RegionStartFinder.effectiveSecondRingProduction(totalFood = 4, totalProd = 8))
        assertEquals(1, RegionStartFinder.effectiveSecondRingProduction(totalFood = 2, totalProd = 6)) // ceil(2 / 2)
        assertEquals(3, RegionStartFinder.effectiveSecondRingProduction(totalFood = 5, totalProd = 11)) // ceil(5 / 2)
    }

    @Test
    fun `a start weighs on its surroundings by 8 rings and no further`() {
        assertEquals(
            mapOf(0 to 99, 1 to 97, 2 to 95, 3 to 92, 4 to 89, 5 to 69, 6 to 57, 7 to 24, 8 to 15),
            MapRegions.closeStartPenaltyForRing
        )
    }
}
