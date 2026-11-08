package com.unciv.logic.automation.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayerCulture::DoSwapGreatWorks / MoveWorks / ThemeBuilding / FillBuilding and CvGameCulture::SwapGreatWorks on the BNW buildings */
@RunWith(GdxTestRunner::class)
class GreatWorkSwapAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var third: Civilization
    private lateinit var city: City
    private lateinit var secondCity: City

    private fun building(name: String): Building = game.ruleset.buildings[name]!!

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        civ = game.addCiv()
        other = game.addCiv()
        third = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        secondCity = game.addCity(civ, game.tileMap[0, 5])
        game.addCity(other, game.tileMap[5, 0], replacePalace = true)
        game.addCity(third, game.tileMap[-5, 0], replacePalace = true)
        for (a in listOf(civ, other, third)) for (b in listOf(civ, other, third)) if (a != b) a.diplomacyFunctions.makeCivilizationsMeet(b)
    }

    private fun art(era: String, by: Civilization, type: GreatWorkType = GreatWorkType.Art) =
        GreatWorkInstance(game.createGreatWork(type).name, by.civName, era, 1)

    private fun put(where: City, building: Building, slot: Int, work: GreatWorkInstance) =
        where.greatWorks.place(work, GreatWorkSlot(where, building, slot))

    private fun worksIn(where: City, building: Building) = where.greatWorks.getSlots(building).map { it.getWork() }

    @Test
    fun `the building with the higher AI priority is themed first with a whole set`() {
        // Hermitage (priority 4, art of 3 eras and 3 civilizations) before the Museum (priority 2, art of one era by the owner)
        val museum = building("Museum")
        val hermitage = building("Hermitage")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(hermitage)
        val ownAncient = art("Ancient era", civ)
        val ownAncientToo = art("Ancient era", civ)
        val classical = art("Classical era", other)
        val medieval = art("Medieval era", third)
        put(city, museum, 0, ownAncient)
        put(city, museum, 1, classical)
        put(secondCity, hermitage, 0, ownAncientToo)
        put(secondCity, hermitage, 1, medieval)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Starter is the first art in empire order (the Museum's first work), then each that keeps eras and civilizations unique
        assertEquals(listOf(ownAncient, classical, medieval), worksIn(secondCity, hermitage))
        assertEquals(3, secondCity.greatWorks.getThemingBonus(hermitage))
        // The Museum only gets what is left; theming it first (the two Ancient works of the owner) would have left the Hermitage unthemed
        assertEquals(listOf(ownAncientToo, null), worksIn(city, museum))
    }

    @Test
    fun `a theme that needs several works moved at once is completed`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(building("Hermitage"))
        val first = art("Renaissance era", civ)
        val second = art("Renaissance era", civ)
        put(city, museum, 0, art("Ancient era", other))
        put(city, museum, 1, art("Medieval era", third))
        val hermitage = building("Hermitage")
        put(secondCity, hermitage, 0, first)
        put(secondCity, hermitage, 1, second)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Hermitage needs 3 art of distinct eras and civilizations: Ancient(other), Medieval(third), Renaissance(civ) - all three positions change
        assertEquals(3, secondCity.greatWorks.getThemingBonus(hermitage))
        assertEquals(1, worksIn(city, museum).filterNotNull().size)
    }

    @Test
    fun `a damaged city is themed after the undamaged ones`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(museum)
        city.health = 100 // damaged, and first in empire order
        val a = art("Ancient era", civ)
        val b = art("Ancient era", civ)
        put(city, museum, 0, a)
        put(city, museum, 1, b)

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertEquals(listOf(a, b), worksIn(secondCity, museum))
        assertEquals(2, secondCity.greatWorks.getThemingBonus(museum))
        assertEquals(listOf(null, null), worksIn(city, museum))
    }

    @Test
    fun `the first unthemed work of each kind is designated as swappable`() {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        secondCity.cityConstructions.addBuilding(building("Amphitheater"))
        val themedA = art("Ancient era", civ)
        val themedB = art("Ancient era", civ)
        val leftover = art("Medieval era", other)
        val artifact = art("Classical era", other, GreatWorkType.Artifact)
        val writing = art("Classical era", civ, GreatWorkType.Literature)
        put(city, museum, 0, leftover)
        put(city, museum, 1, themedA)
        secondCity.cityConstructions.addBuilding(museum)
        put(secondCity, museum, 0, themedB)
        put(secondCity, museum, 1, artifact)
        put(secondCity, building("Amphitheater"), 0, writing)
        civ.culture.swappableMusic = "kept"

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        assertEquals(leftover.id, civ.culture.swappableArt)
        assertEquals(artifact.id, civ.culture.swappableArtifact)
        assertEquals(writing.id, civ.culture.swappableWriting)
        assertEquals("music is never designated", "kept", civ.culture.swappableMusic)
        assertEquals(2, city.greatWorks.getThemingBonus(museum))
    }

    private fun setUpSwap(): Triple<Building, GreatWorkInstance, GreatWorkInstance> {
        val museum = building("Museum")
        city.cityConstructions.addBuilding(museum)
        val ours = art("Ancient era", civ)
        // By a third civilization, so the two works make no theme of the Museum without a swap
        val toGive = art("Medieval era", third)
        put(city, museum, 0, ours)
        put(city, museum, 1, toGive)
        val otherCity = other.cities.first()
        val otherMuseum = building("Museum")
        otherCity.cityConstructions.addBuilding(otherMuseum)
        val wanted = art("Ancient era", other)
        put(otherCity, otherMuseum, 0, wanted)
        return Triple(museum, toGive, wanted)
    }

    @Test
    fun `one work short, the designated works of two AIs are swapped to complete the theme`() {
        val (museum, toGive, wanted) = setUpSwap()
        civ.culture.swappableArt = toGive.id
        other.culture.swappableArt = wanted.id

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)

        // Ancient art of the owner plus Ancient art of another civ: "Museum of [era] Art" (sameEra, mustBeArt), bonus 1
        assertTrue(worksIn(city, museum).any { it === wanted })
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === toGive })
        assertEquals(1, city.greatWorks.getThemingBonus(museum))
        assertEquals("the other's designation is used up", "", other.culture.swappableArt)
    }

    @Test
    fun `no swap unless both works are the designated ones`() {
        val (museum, toGive, wanted) = setUpSwap()
        other.culture.swappableArt = wanted.id
        civ.culture.swappableArt = "" // we did not designate the work we would give

        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === wanted })
        assertEquals(0, city.greatWorks.getThemingBonus(museum))

        civ.culture.swappableArt = toGive.id
        other.culture.swappableArt = ""
        GreatWorkSwapAutomation.doSwapGreatWorks(civ)
        assertTrue(other.cities.first().greatWorks.getWorks().any { it === wanted })
    }
}
