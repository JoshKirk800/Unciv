package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.managers.CultureManager
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.Victory
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TourismTests {
    private lateinit var game: TestGame
    private lateinit var civA: Civilization
    private lateinit var civB: Civilization
    private lateinit var cityA: City

    private val gallerySlots = "Has [40] [Art/Artifact] Great Work slots"

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        civA = game.addCiv()
        civB = game.addCiv()
        cityA = game.addCity(civA, game.tileMap[0, 0], replacePalace = true)
        game.addCity(civB, game.tileMap[0, 5], replacePalace = true)
        civA.diplomacyFunctions.makeCivilizationsMeet(civB)
        civA.getDiplomacyManager(civB)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(civA, true)
    }

    private fun addBuilding(vararg uniques: String, to: City = cityA): Building =
        game.createBuilding(*uniques).also { to.cityConstructions.addBuilding(it) }

    /** A city with [works] Great Works in it, extra uniques on its building, and no other source of tourism */
    private fun galleryWith(works: Int, vararg uniques: String, to: City = cityA): Building {
        val gallery = addBuilding(gallerySlots, *uniques, to = to)
        repeat(works) {
            val work = game.createGreatWork()
            to.greatWorks.place(GreatWorkInstance(work.name, to.civ.civName, "Ancient era", 1), GreatWorkSlot(to, gallery, it))
        }
        return gallery
    }

    /** A civilization that lives, which one without a city does not */
    private fun livingCiv(y: Int, vararg uniques: String): Civilization {
        val civ = game.addCiv(*uniques)
        game.addCity(civ, game.tileMap[0, y], replacePalace = true)
        return civ
    }

    private fun tourism(city: City = cityA) = city.civ.culture.getCityTourism(city)

    //region Tourism of a city
    @Test
    fun `each Great Work makes two tourism`() {
        galleryWith(3)
        assertEquals(6, tourism())
    }

    @Test
    fun `a percentage for Great Works applies to the works but not to the theming bonus`() {
        val gallery = addBuilding("Has [2] [Art/Artifact] Great Work slots", "[+50]% Tourism from Great Works [in this city]")
        gallery.themingBonuses.add(com.unciv.models.ruleset.ThemingBonus().apply { bonus = 5; name = "any" })
        for (slot in 0..1)
            cityA.greatWorks.place(GreatWorkInstance(game.createGreatWork().name, civA.civName, "Ancient era", 1), GreatWorkSlot(cityA, gallery, slot))
        // 2 works make 4, 50% more is 6, theming 5 is added after
        assertEquals(11, tourism())
    }

    @Test
    fun `flat tourism that depends on a technology counts once it is known`() {
        addBuilding("[+12] Tourism [in this city] <after discovering [Pottery]>")
        assertEquals(0, tourism())
        civA.tech.addTechnology("Pottery")
        assertEquals(12, tourism())
    }

    @Test
    fun `percentages of the city are added up, and only apply if they add up to a gain`() {
        galleryWith(5)  // 10
        addBuilding("[+50]% Tourism [in this city]", "[+30]% Tourism [in this city]")
        assertEquals(18, tourism())

        val other = game.addCity(civA, game.tileMap[0, -4])
        galleryWith(5, "[-50]% Tourism [in this city]", to = other)
        assertEquals("a malus is not applied", 10, tourism(other))
    }

    @Test
    fun `culture of world wonders, but not of national wonders, becomes tourism by the share given`() {
        val wonder = game.createWonder(); wonder.culture = 5f
        val national = game.createBuilding(); national.culture = 7f; national.isNationalWonder = true
        cityA.cityConstructions.addBuilding(wonder)
        cityA.cityConstructions.addBuilding(national)
        assertEquals(0, tourism())
        addBuilding("[50]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [in this city]")
        assertEquals(2, tourism())  // half of 5, rounded down
    }

    @Test
    fun `a city in resistance makes no tourism`() {
        galleryWith(3)
        cityA.setFlag(CityFlags.Resistance, 3)
        assertEquals(0, tourism())
    }
    //endregion

    //region Between civilizations
    private fun tenTourism() = galleryWith(5)

    @Test
    fun `the whole tourism goes to every civilization that has been met, none to others`() {
        tenTourism()
        val stranger = livingCiv(-6)
        assertEquals(10, civA.culture.getInfluencePerTurn(civB))
        assertEquals(0, civA.culture.getInfluencePerTurn(stranger))
        assertEquals(0, civA.culture.getInfluencePerTurn(civA))
        civA.culture.endTurn()
        assertEquals(10, civA.culture.getInfluence(civB))
        assertEquals(0, civA.culture.getInfluence(stranger))
    }

    @Test
    fun `open borders and the policies that add to it change the modifier`() {
        tenTourism()
        civA.getDiplomacyManager(civB)!!.hasOpenBorders = true
        assertEquals("the base of 25% for open borders", 12, civA.culture.getInfluencePerTurn(civB))

        val exchange = game.addCiv("[+15]% Tourism towards civilizations with [Open Borders]")
        val cityE = game.addCity(exchange, game.tileMap[0, -5], replacePalace = true)
        galleryWith(5, to = cityE)
        exchange.diplomacyFunctions.makeCivilizationsMeet(civB)
        exchange.getDiplomacyManager(civB)!!.makePeace()
        exchange.getDiplomacyManager(civB)!!.hasOpenBorders = true
        assertEquals("25% and 15% more", 14, exchange.culture.getInfluencePerTurn(civB))
    }

    @Test
    fun `being happier and having common enemies add what policies give for them`() {
        val emperor = game.addCiv("[+34]% Tourism towards civilizations with [Lower Happiness]", "[+50]% Tourism towards civilizations with [Common Enemies]")
        val capital = game.addCity(emperor, game.tileMap[0, -5], replacePalace = true)
        galleryWith(5, to = capital)
        emperor.diplomacyFunctions.makeCivilizationsMeet(civB)
        emperor.getDiplomacyManager(civB)!!.makePeace()
        emperor.stats.happiness = 5
        civB.stats.happiness = 1
        assertEquals(13, emperor.culture.getInfluencePerTurn(civB))

        val enemyOfBoth = livingCiv(-7)
        val third = livingCiv(-8)
        for (civ in listOf(emperor, civB)) {
            civ.diplomacyFunctions.makeCivilizationsMeet(enemyOfBoth)
            civ.getDiplomacyManager(enemyOfBoth)!!.declareWar()
        }
        for (civ in listOf(emperor, civB)) {
            civ.diplomacyFunctions.makeCivilizationsMeet(third)
            civ.getDiplomacyManager(third)!!.makePeace()
        }
        assertEquals("one enemy in common: 34% and 50%, relations ${emperor.culture.getRelationsWith(civB)}, war with enemy ${emperor.isAtWarWith(enemyOfBoth)} ${civB.isAtWarWith(enemyOfBoth)}", 18, emperor.culture.getInfluencePerTurn(civB))
    }

    @Test
    fun `a civilization with the blocking building does not get the tourism bonus that it blocks`() {
        val blocker = game.createBuilding()
        galleryWith(5, "[+100]% Tourism [in this city] unless the target has a [${blocker.name}]")
        assertEquals(20, civA.culture.getInfluencePerTurn(civB))
        civB.cities.first().cityConstructions.addBuilding(blocker)
        assertEquals(10, civA.culture.getInfluencePerTurn(civB))
    }

    //endregion

    //region Levels
    private fun levelAt(percent: Int): InfluenceLevel? {
        civB.totalCultureForContests = 1000
        civA.culture.addInfluence(civB, percent * 10)
        return civA.culture.getInfluenceLevel(civB)
    }

    @Test
    fun `the level follows the share of the culture of the other, at the thresholds exactly`() {
        for ((percent, level) in listOf(0 to InfluenceLevel.Unknown, 9 to InfluenceLevel.Unknown, 10 to InfluenceLevel.Exotic, 29 to InfluenceLevel.Exotic,
            30 to InfluenceLevel.Familiar, 59 to InfluenceLevel.Familiar, 60 to InfluenceLevel.Popular, 99 to InfluenceLevel.Popular,
            100 to InfluenceLevel.Influential, 199 to InfluenceLevel.Influential, 200 to InfluenceLevel.Dominant, 500 to InfluenceLevel.Dominant)) {
            civA.culture.influence.clear()
            assertEquals("at $percent%", level, levelAt(percent))
        }
    }

    @Test
    fun `a civilization that has not been met has no level, one without culture has the lowest`() {
        val stranger = livingCiv(-6)
        assertNull(civA.culture.getInfluenceLevel(stranger))
        civB.totalCultureForContests = 0
        civA.culture.addInfluence(civB, 500)
        assertEquals(InfluenceLevel.Unknown, civA.culture.getInfluenceLevel(civB))
    }

    @Test
    fun `influence only grows when the culture of the other grows`() {
        civB.totalCultureForContests = 100
        civA.culture.addInfluence(civB, 100)
        assertEquals(InfluenceLevel.Influential, civA.culture.getInfluenceLevel(civB))
        civB.totalCultureForContests = 250
        assertEquals(InfluenceLevel.Familiar, civA.culture.getInfluenceLevel(civB))
        assertEquals(100, civA.culture.getInfluence(civB))
    }

    @Test
    fun `the trend compares how fast influence and culture of the other grow`() {
        civB.totalCultureForContests = 1000
        civA.culture.addInfluence(civB, 100)
        civA.culture.rememberLastTurn()
        civB.culture.rememberLastTurn()
        civA.culture.addInfluence(civB, 50)
        civB.totalCultureForContests = 1010
        assertEquals(InfluenceTrend.Rising, civA.culture.getInfluenceTrend(civB))
        civB.totalCultureForContests = 2000
        assertEquals(InfluenceTrend.Falling, civA.culture.getInfluenceTrend(civB))
    }

    @Test
    fun `turns to influential count what is missing over what the tourism gains on the culture of the other`() {
        civB.totalCultureForContests = 1000
        civB.stats.statsForNextTurn.culture = 10f
        civA.culture.addInfluence(civB, 200)
        civA.culture.rememberLastTurn()
        civB.culture.rememberLastTurn()
        tenTourismWithOutput(60)
        civA.culture.addInfluence(civB, 60)
        // 800 are missing at the pace of 60 - 10 per turn, rounded up after the 60 of this turn is in: 740 / 50
        assertEquals(InfluenceTrend.Rising, civA.culture.getInfluenceTrend(civB))
        assertEquals(15, civA.culture.getTurnsToInfluential(civB))
    }

    private fun tenTourismWithOutput(output: Int) { galleryWith(output / 2) }

    @Test
    fun `turns to influential is 999 unless the trend is rising`() {
        civB.totalCultureForContests = 1000
        civA.culture.addInfluence(civB, 100)
        assertEquals(CultureManager.NEVER, civA.culture.getTurnsToInfluential(civB))
    }

    @Test
    fun `conquest and trade route bonuses grow with the level`() {
        civB.totalCultureForContests = 1000
        val expected = listOf(0 to 0, 5 to 0, 10 to 0, 30 to 1, 60 to 2, 100 to 3, 200 to 4)
        for ((percent, steps) in expected) {
            civA.culture.influence.clear()
            civA.culture.addInfluence(civB, percent * 10)
            assertEquals("route science at $percent%", steps, civA.culture.getTradeRouteScienceBonus(civB))
            assertEquals("conquest at $percent%", steps * 25, civA.culture.getConquestReductionPercent(civB))
        }
    }
    //endregion

    //region Conquest
    private fun conquerBigCity(influencePercent: Int): City {
        val victim = livingCiv(-6)
        val target = game.addCity(victim, game.tileMap[0, -3], initialPopulation = 8)
        civA.diplomacyFunctions.makeCivilizationsMeet(victim)
        victim.totalCultureForContests = 1000
        civA.culture.addInfluence(victim, influencePercent * 10)
        target.puppetCity(civA)
        return target
    }

    @Test
    fun `without influence a conquered city loses a quarter of its people and is in resistance for as many turns as it has left`() {
        val city = conquerBigCity(0)
        assertEquals(8 - (1 + 8 / 4), city.population.population)
        assertTrue(city.isInResistance())
    }

    @Test
    fun `a civilization that is dominant over the other loses no people in a conquest and meets no resistance`() {
        val city = conquerBigCity(200)
        assertEquals(8, city.population.population)
        assertFalse(city.isInResistance())
    }

    @Test
    fun `a popular civilization loses half as many people and has half the resistance`() {
        val city = conquerBigCity(60)
        assertEquals(8 - 3 / 2, city.population.population)
        assertEquals(7 / 2, city.getFlag(CityFlags.Resistance))
    }
    //endregion

    //region Culture victory
    private val milestoneText = "Be [Influential] over all other civilizations"
    private fun milestone() = Milestone(milestoneText, Victory())

    @Test
    fun `the culture victory needs every other living civilization, a civilization that is not met stops it`() {
        civB.totalCultureForContests = 100
        civA.culture.addInfluence(civB, 100)
        assertTrue(milestone().hasBeenCompletedBy(civA))

        val stranger = livingCiv(-6)
        stranger.totalCultureForContests = 100
        assertFalse("not met", milestone().hasBeenCompletedBy(civA))

        civA.diplomacyFunctions.makeCivilizationsMeet(stranger)
        assertFalse("met but not influenced", milestone().hasBeenCompletedBy(civA))
        civA.culture.addInfluence(stranger, 100)
        assertTrue(milestone().hasBeenCompletedBy(civA))
    }

    @Test
    fun `a civilization that is alone has not won a culture victory`() {
        val alone = TestGame()
        val civ = alone.addCiv()
        assertFalse(Milestone(milestoneText, Victory()).hasBeenCompletedBy(civ))
    }

    @Test
    fun `the number influential over counts the civilizations that are at least influential`() {
        val third = livingCiv(-6)
        civA.diplomacyFunctions.makeCivilizationsMeet(third)
        civB.totalCultureForContests = 100
        third.totalCultureForContests = 100
        civA.culture.addInfluence(civB, 99)
        civA.culture.addInfluence(third, 100)
        assertEquals(1, civA.culture.getNumCivsInfluentialOn())
        assertEquals(2, civA.culture.getOtherMajorCivs().size)
    }
    //endregion

    //region Lifetime culture
    @Test
    fun `all culture that is added counts for the lifetime culture, what is spent does not`() {
        civA.policies.addCulture(30)
        civA.addStat(com.unciv.models.stats.Stat.Culture, 20)
        civA.policies.addCulture(-10)
        assertEquals(50, civA.totalCultureForContests)
        assertEquals(40, civA.policies.storedCulture)
    }
    //endregion

    //region Tourism that does not come from cities
    @Test
    fun `a gain of tourism goes to every civilization that has been met`() {
        val stranger = livingCiv(-6)
        val third = livingCiv(-7)
        civA.diplomacyFunctions.makeCivilizationsMeet(third)
        val unique = game.createPolicy("[250] Tourism towards all civilizations we have met").uniqueObjects.single()
        assertTrue(com.unciv.models.ruleset.unique.UniqueTriggerActivation.triggerUnique(unique, civA))
        assertEquals(250, civA.culture.getInfluence(civB))
        assertEquals(250, civA.culture.getInfluence(third))
        assertEquals(0, civA.culture.getInfluence(stranger))
    }

    /** A Great Musician of A on [tile], which then becomes a tile of [owner] (a unit could not enter it before, which is how it would be on a tour with open borders) */
    private fun musician(owner: City?, tile: com.unciv.logic.map.tile.Tile): MapUnit {
        val base = game.createBaseUnit("Civilian",
            "Perform a concert tour: [10] times our Tourism towards the civilization whose territory we are in, [20]% of that towards each other civilization we have met <by consuming this unit>")
        base.movement = 2
        tile.setOwningCity(null)
        val unit = game.addUnit(base.name, civA, tile)
        if (owner != null) tile.setOwningCity(owner)
        return unit
    }

    private fun tourAction(unit: MapUnit) = UnitActions.getUnitActions(unit, UnitActionType.TriggerUnique).single().action

    @Test
    fun `a concert tour in foreign territory gives the owner ten times our tourism and the others a fifth of it`() {
        galleryWith(10) // 20 tourism
        val third = livingCiv(-6)
        civA.diplomacyFunctions.makeCivilizationsMeet(third)
        val foreign = civB.cities.first().getCenterTile().neighbors.first()
        game.addTileToCity(civB.cities.first(), foreign)
        val unit = musician(civB.cities.first(), foreign)

        tourAction(unit)!!.invoke()

        assertEquals(200, civA.culture.getInfluence(civB))
        assertEquals(40, civA.culture.getInfluence(third))
        assertFalse(unit in civA.units.getCivUnits())
    }

    @Test
    fun `a concert tour is worth at least a hundred`() {
        val foreign = civB.cities.first().getCenterTile().neighbors.first()
        tourAction(musician(civB.cities.first(), foreign))!!.invoke()
        assertEquals(100, civA.culture.getInfluence(civB))
    }

    @Test
    fun `there is no concert tour in own or unowned territory`() {
        val own = cityA.getCenterTile().neighbors.first()
        own.setOwningCity(cityA)
        assertNull(tourAction(musician(null, own)))
        assertNull(tourAction(musician(null, game.tileMap[0, -6])))
    }
    //endregion

    @Test
    fun `influence is saved and loaded`() {
        civA.culture.addInfluence(civB, 77)
        civA.culture.rememberLastTurn()
        civA.culture.addInfluence(civB, 3)
        val text = com.unciv.json.json().toJson(civA.culture)
        val loaded = com.unciv.json.json().fromJson(CultureManager::class.java, text)
        assertEquals(80, loaded.influence[civB.civID])
        assertEquals(77, loaded.lastTurnInfluence[civB.civID])
        assertEquals(80, civA.culture.clone().influence[civB.civID])
    }
}
