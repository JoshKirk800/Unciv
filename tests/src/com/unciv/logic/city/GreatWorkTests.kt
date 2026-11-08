package com.unciv.logic.city

import com.unciv.logic.automation.unit.GreatWorkAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWork
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.ThemingBonus
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class GreatWorkTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    private val artSlots = { n: Int -> "Has [$n] [Art/Artifact] Great Work slots" }

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
    }

    private fun addBuilding(vararg uniques: String, to: City = city): Building =
        game.createBuilding(*uniques).also { to.cityConstructions.addBuilding(it) }

    private fun work(type: GreatWorkType = GreatWorkType.Art) = game.createGreatWork(type)

    private fun instance(work: GreatWork, era: String = "Ancient era", by: Civilization = civ) =
        GreatWorkInstance(work.name, by.civName, era, 1)

    private fun put(building: Building, slot: Int, instance: GreatWorkInstance, into: City = city) =
        into.greatWorks.place(instance, GreatWorkSlot(into, building, slot))

    private fun culture(of: City = city): Float {
        of.cityStats.update()
        return of.cityStats.currentCityStats.culture
    }

    //region Slots
    @Test
    fun `slots come from the uniques of a building, each with its type, in order`() {
        val building = addBuilding(artSlots(1), "Has [2] [Music] Great Work slots", "Has [1] [Literature] Great Work slots")
        assertEquals(
            listOf(GreatWorkSlotType.ArtArtifact, GreatWorkSlotType.Music, GreatWorkSlotType.Music, GreatWorkSlotType.Literature),
            building.greatWorkSlotTypes
        )
        assertEquals(4, city.greatWorks.getSlots(building).size)
    }

    @Test
    fun `only the buildings that are built offer slots`() {
        val built = addBuilding(artSlots(2))
        val notBuilt = game.createBuilding(artSlots(3))
        val slotBuildings = city.greatWorks.getSlots().map { it.building }
        assertEquals(listOf(built, built), slotBuildings)
        assertFalse(notBuilt in slotBuildings)
    }

    @Test
    fun `art and artifacts share slots, music and literature do not`() {
        addBuilding(artSlots(1))
        val art = work(GreatWorkType.Art)
        val artifact = work(GreatWorkType.Artifact)
        val music = work(GreatWorkType.Music)
        val slot = city.greatWorks.getFreeSlot(GreatWorkSlotType.ArtArtifact)!!
        assertTrue(GreatWorkFunctions.fits(instance(art), slot))
        assertTrue(GreatWorkFunctions.fits(instance(artifact), slot))
        assertFalse(GreatWorkFunctions.fits(instance(music), slot))
    }
    //endregion

    //region Culture
    @Test
    fun `each work brings two culture, and a policy can add to it`() {
        val building = addBuilding(artSlots(2))
        val before = culture()
        put(building, 0, instance(work()))
        put(building, 1, instance(work()))
        assertEquals(before + 4f, culture(), 0.001f)

        val enriched = game.addCiv("[+1 Culture] from each Great Work")
        val enrichedCity = game.addCity(enriched, game.tileMap[0, 3])
        val gallery = addBuilding(artSlots(2), to = enrichedCity)
        val baseline = culture(enrichedCity)
        put(gallery, 0, instance(work(), by = enriched), enrichedCity)
        put(gallery, 1, instance(work(), by = enriched), enrichedCity)
        assertEquals(baseline + 6f, culture(enrichedCity), 0.001f)
    }

    //endregion

    //region Theming
    private fun themedGallery(rule: ThemingBonus.() -> Unit, bonus: Int = 3, slots: Int = 2): Building {
        val building = addBuilding(artSlots(slots))
        building.themingBonuses.add(ThemingBonus().apply { this.bonus = bonus; rule() })
        return building
    }

    @Test
    fun `a building is themed only when every slot is filled and the works fit`() {
        val building = themedGallery({ sameEra = true })
        put(building, 0, instance(work(), "Ancient era"))
        assertEquals(0, city.greatWorks.getThemingBonus(building))
        put(building, 1, instance(work(), "Classical era"))
        assertEquals("filled, but of different eras", 0, city.greatWorks.getThemingBonus(building))
        put(building, 1, instance(work(), "Ancient era"))
        assertEquals(3, city.greatWorks.getThemingBonus(building))
    }

    @Test
    fun `theming adds its bonus to the culture of the city`() {
        val building = themedGallery({ sameEra = true })
        put(building, 0, instance(work(), "Ancient era"))
        put(building, 1, instance(work(), "Classical era"))
        val unthemed = culture()
        put(building, 1, instance(work(), "Ancient era"))
        assertEquals(unthemed + 3f, culture(), 0.001f)
    }

    @Test
    fun `the first theming bonus that fits is the one that counts`() {
        val building = addBuilding(artSlots(2))
        building.themingBonuses.add(ThemingBonus().apply { bonus = 2; name = "same civilization"; requiresSamePlayer = true })
        building.themingBonuses.add(ThemingBonus().apply { bonus = 1; name = "anything"; })
        val other = game.addCiv()
        put(building, 0, instance(work(), by = civ))
        put(building, 1, instance(work(), by = other))
        assertEquals("anything", city.greatWorks.getThemingBonusEntry(building)!!.name)
        put(building, 1, instance(work(), by = civ))
        assertEquals("same civilization", city.greatWorks.getThemingBonusEntry(building)!!.name)
    }

    @Test
    fun `theming bonus percentages multiply, those for the capital only in the capital`() {
        val empire = game.addCiv("[+100]% Theming bonuses [in all cities]", "[+100]% Theming bonuses [in capital]")
        val capital = game.addCity(empire, game.tileMap[0, 3], replacePalace = true)
        val colony = game.addCity(empire, game.tileMap[0, -3])
        for (target in listOf(capital, colony)) {
            val gallery = addBuilding(artSlots(1), to = target)
            gallery.themingBonuses.add(ThemingBonus().apply { bonus = 3 })
            put(gallery, 0, instance(work(), by = empire), target)
        }
        // doubled by the first, doubled again by the second in the capital, as in the original where they are two multipliers
        assertEquals(12, capital.greatWorks.getTotalThemingBonus())
        assertEquals(6, colony.greatWorks.getTotalThemingBonus())
    }

    private fun satisfied(rule: ThemingBonus.() -> Unit, eras: List<String>, civs: List<Civilization>, types: List<GreatWorkType> = eras.map { GreatWorkType.Art }): Boolean {
        val bonus = ThemingBonus().apply(rule)
        val works = eras.indices.map { GreatWorkInstance("w$it", civs[it].civName, eras[it], 1) }
        return bonus.isSatisfiedBy(works, types, civ.civName)
    }

    @Test
    fun `same era and unique eras are opposites that both hold for a single work`() {
        val one = listOf(civ)
        assertTrue(satisfied({ sameEra = true }, listOf("A"), one))
        assertTrue(satisfied({ uniqueEras = true }, listOf("A"), one))
        val two = listOf(civ, civ)
        assertTrue(satisfied({ sameEra = true }, listOf("A", "A"), two))
        assertFalse(satisfied({ sameEra = true }, listOf("A", "B"), two))
        assertTrue(satisfied({ uniqueEras = true }, listOf("A", "B"), two))
        assertFalse(satisfied({ uniqueEras = true }, listOf("A", "A"), two))
    }

    @Test
    fun `civilization requirements look at who the works are attributed to`() {
        val other = game.addCiv()
        val third = game.addCiv()
        val eras = listOf("A", "A")
        assertTrue(satisfied({ requiresOwner = true }, eras, listOf(civ, civ)))
        assertFalse(satisfied({ requiresOwner = true }, eras, listOf(civ, other)))
        assertTrue(satisfied({ requiresAnyButOwner = true }, eras, listOf(other, third)))
        assertFalse(satisfied({ requiresAnyButOwner = true }, eras, listOf(other, civ)))
        assertTrue(satisfied({ requiresSamePlayer = true }, eras, listOf(other, other)))
        assertFalse(satisfied({ requiresSamePlayer = true }, eras, listOf(other, third)))
        assertTrue(satisfied({ requiresUniquePlayers = true }, eras, listOf(other, third)))
        assertFalse(satisfied({ requiresUniquePlayers = true }, eras, listOf(other, other)))
    }

    @Test
    fun `art and artifact requirements`() {
        val civs = listOf(civ, civ, civ, civ)
        val eras = listOf("A", "B", "C", "D")
        val art = GreatWorkType.Art
        val artifact = GreatWorkType.Artifact
        assertTrue(satisfied({ mustBeArt = true }, eras, civs, listOf(art, art, art, art)))
        assertFalse(satisfied({ mustBeArt = true }, eras, civs, listOf(art, art, art, artifact)))
        assertTrue(satisfied({ mustBeArtifact = true }, eras, civs, listOf(artifact, artifact, artifact, artifact)))
        assertFalse(satisfied({ mustBeArtifact = true }, eras, civs, listOf(artifact, art, artifact, artifact)))
        assertTrue(satisfied({ mustBeEqualArtArtifact = true }, eras, civs, listOf(art, artifact, art, artifact)))
        assertFalse(satisfied({ mustBeEqualArtArtifact = true }, eras, civs, listOf(art, art, art, artifact)))
    }
    //endregion

    //region Creation by a Great Person
    private fun greatPerson(name: String?, at: City = city, tile: Tile = at.getCenterTile()): MapUnit {
        val base = game.createBaseUnit("Civilian", "Create a Great Work <by consuming this unit>")
        base.movement = 2
        val unit = game.addUnit(base.name, civ, tile)
        unit.instanceName = name
        return unit
    }

    private fun createAction(unit: MapUnit): (() -> Unit)? =
        UnitActions.getUnitActions(unit, UnitActionType.TriggerUnique).single().action

    @Test
    fun `a Great Person creates the work that goes with the name, in a free slot, and is used up`() {
        val homer = game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        val library = addBuilding("Has [1] [Literature] Great Work slots")
        val unit = greatPerson("Homer")

        createAction(unit)!!.invoke()

        val created = city.greatWorks.getWorkIn(GreatWorkSlot(city, library, 0))!!
        assertEquals(homer.name, created.work)
        assertEquals(civ.civName, created.creatorCiv)
        assertEquals(civ.getEra().name, created.era)
        assertFalse(unit in civ.units.getCivUnits())
    }

    @Test
    fun `the work is put in the city nearest to the unit that has a free slot of the type`() {
        game.createGreatWork(GreatWorkType.Music, creator = "Bach")
        val far = game.addCity(civ, game.tileMap[0, 4])
        val near = game.addCity(civ, game.tileMap[0, -2])
        addBuilding("Has [1] [Music] Great Work slots", to = far)
        val nearHall = addBuilding("Has [1] [Music] Great Work slots", to = near)
        val unit = greatPerson("Bach", at = city)
        createAction(unit)!!.invoke()
        assertNotNull(near.greatWorks.getWorkIn(GreatWorkSlot(near, nearHall, 0)))
        assertEquals(0, far.greatWorks.getNumWorks())
    }

    @Test
    fun `there is no action without a free slot of the right type`() {
        game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        addBuilding(artSlots(1), "Has [1] [Music] Great Work slots")
        assertNull(createAction(greatPerson("Homer")))
    }

    @Test
    fun `there is no action for a unit without a name that has a work`() {
        game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        addBuilding("Has [1] [Literature] Great Work slots")
        assertNull(createAction(greatPerson(null)))
        assertNull(createAction(greatPerson("Someone else", tile = city.getCenterTile().neighbors.first())))
    }

    @Test
    fun `the unit must be in or next to a city of its civilization`() {
        game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        addBuilding("Has [1] [Literature] Great Work slots")
        val base = game.createBaseUnit("Civilian", "Create a Great Work <by consuming this unit>")
        val adjacent = game.addUnit(base.name, civ, city.getCenterTile().neighbors.first())
        adjacent.instanceName = "Homer"
        assertNotNull(createAction(adjacent))
        val away = game.addUnit(base.name, civ, game.tileMap[0, 4])
        away.instanceName = "Homer"
        assertNull(createAction(away))
    }

    @Test
    fun `a building can come with a work`() {
        val gift = game.createGreatWork(GreatWorkType.Art)
        val temple = game.createBuilding(artSlots(1), "Gain the [${gift.name}] Great Work")
        city.cityConstructions.addBuilding(temple)
        val placed = city.greatWorks.getWorkIn(GreatWorkSlot(city, temple, 0))!!
        assertEquals(gift.name, placed.work)
        assertEquals(civ.civName, placed.creatorCiv)
    }
    //endregion

    //region Automation
    @Test
    fun `the AI has a Great Person create its work when it stands in a city with a free slot, and walks there otherwise`() {
        val homer = game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        val library = addBuilding("Has [1] [Literature] Great Work slots")
        val away = greatPerson("Homer", tile = game.tileMap[0, 4])
        val before = away.currentTile.aerialDistanceTo(city.getCenterTile())
        assertTrue(GreatWorkAutomation.automate(away))
        assertTrue("walked towards the city", away.currentTile.aerialDistanceTo(city.getCenterTile()) < before)
        assertNull(city.greatWorks.getWorkIn(GreatWorkSlot(city, library, 0)))

        away.destroy()
        val inCity = greatPerson("Homer")
        assertTrue(GreatWorkAutomation.automate(inCity))
        assertEquals(homer.name, city.greatWorks.getWorkIn(GreatWorkSlot(city, library, 0))!!.work)
        assertFalse(inCity in civ.units.getCivUnits())
    }

    @Test
    fun `the AI leaves a Great Person with other uses alone when no slot is free`() {
        game.createGreatWork(GreatWorkType.Literature, creator = "Homer")
        assertFalse(GreatWorkAutomation.automate(greatPerson("Homer")))
    }
    //endregion

    //region Moving and losing
    @Test
    fun `works can be moved to a free slot and swapped, only if they fit`() {
        val gallery = addBuilding(artSlots(2), "Has [1] [Music] Great Work slots")
        val picture = instance(work())
        val statue = instance(work(GreatWorkType.Artifact))
        put(gallery, 0, picture)
        val first = GreatWorkSlot(city, gallery, 0)
        val second = GreatWorkSlot(city, gallery, 1)
        val hall = GreatWorkSlot(city, gallery, 2)

        assertFalse("a picture does not fit a music slot", GreatWorkFunctions.move(first, hall))
        assertTrue(GreatWorkFunctions.move(first, second))
        assertNull(first.getWork())
        assertEquals(picture.work, second.getWork()!!.work)

        put(gallery, 0, statue)
        assertTrue(GreatWorkFunctions.move(first, second))
        assertEquals(statue.work, second.getWork()!!.work)
        assertEquals(picture.work, first.getWork()!!.work)
    }

    @Test
    fun `a building that is removed gives its works to the nearest free slot, and a work without one is lost`() {
        val other = game.addCity(civ, game.tileMap[0, 3])
        val room = addBuilding(artSlots(1), to = other)
        val gallery = addBuilding(artSlots(2))
        put(gallery, 0, instance(work()))
        put(gallery, 1, instance(work()))

        city.cityConstructions.removeBuilding(gallery)

        assertEquals("one work found the free slot of the other city", 1, other.greatWorks.getNumWorks())
        assertNotNull(other.greatWorks.getWorkIn(GreatWorkSlot(other, room, 0)))
        assertEquals(0, city.greatWorks.getNumWorks())
        assertTrue(city.greatWorks.homeless.isEmpty())
        assertTrue("the other work was lost with a notification", civ.notifications.any { "was lost" in it.text })
    }

    @Test
    fun `a building with works in it cannot be sold`() {
        val gallery = addBuilding(artSlots(1))
        assertFalse(city.greatWorks.holdsWorks(gallery))
        put(gallery, 0, instance(work()))
        assertTrue(city.greatWorks.holdsWorks(gallery))
    }

    @Test
    fun `the works of a conquered city go to the conqueror`() {
        val conqueror = game.addCiv()
        val home = game.addCity(conqueror, game.tileMap[0, 4], replacePalace = true)
        val homeRoom = addBuilding(artSlots(1), to = home)
        val victim = game.addCiv()
        val captured = game.addCity(victim, game.tileMap[0, -4], replacePalace = true)
        val survivor = game.createWonder(artSlots(1))
        val doomed = game.createBuilding(artSlots(1), "Destroyed when the city is captured")
        captured.cityConstructions.addBuilding(survivor)
        captured.cityConstructions.addBuilding(doomed)
        put(survivor, 0, instance(work(), by = victim), captured)
        put(doomed, 0, instance(work(), by = victim), captured)

        captured.hasJustBeenConquered = true
        captured.cityConstructions.removeBuilding(doomed)
        assertEquals("a conquered city does not give its works to its previous owner", 1, captured.greatWorks.homeless.size)
        captured.moveToCiv(conqueror)

        assertEquals(conqueror, captured.civ)
        assertEquals("the work of the building that stayed", 1, captured.greatWorks.getNumWorks())
        assertEquals("the work of the destroyed building found a free slot of the conqueror", 1, home.greatWorks.getNumWorks())
        assertNotNull(home.greatWorks.getWorkIn(GreatWorkSlot(home, homeRoom, 0)))
        assertTrue(captured.greatWorks.homeless.isEmpty())
    }
    //endregion

    private fun exchangeWith(other: Civilization, ours: GreatWorkSlot, theirs: GreatWorkSlot): TradeLogic =
        TradeLogic(civ, other).apply {
            currentTrade.ourOffers.add(TradeOffer(GreatWorkFunctions.tradeId(ours), TradeOfferType.GreatWork, speed = game.gameInfo.speed))
            currentTrade.theirOffers.add(TradeOffer(GreatWorkFunctions.tradeId(theirs), TradeOfferType.GreatWork, speed = game.gameInfo.speed))
        }

    @Test
    fun `foreign Great Work exchange transfers each original work exactly once with its attribution`() {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val foreignCity = game.addCity(other, game.tileMap[0, 4], replacePalace = true)
        val gallery = addBuilding(artSlots(1))
        val foreignGallery = addBuilding(artSlots(1), to = foreignCity)
        val first = GreatWorkSlot(city, gallery, 0)
        val second = GreatWorkSlot(foreignCity, foreignGallery, 0)
        val ownWork = instance(work(), by = civ)
        val foreignWork = instance(work(), by = other)
        put(gallery, 0, ownWork)
        put(foreignGallery, 0, foreignWork, foreignCity)
        val trade = exchangeWith(other, first, second)

        assertTrue(TradeEvaluation().isTradeValid(trade.currentTrade, civ, other))
        assertTrue(trade.ourAvailableOffers.any { it.name == GreatWorkFunctions.tradeId(first) && it.type == TradeOfferType.GreatWork })
        trade.acceptTrade(applyGifts = false)

        assertTrue(first.getWork() === foreignWork)
        assertTrue(second.getWork() === ownWork)
        assertEquals(listOf(foreignWork), city.greatWorks.getWorks())
        assertEquals(listOf(ownWork), foreignCity.greatWorks.getWorks())
        assertEquals(other.civName, first.getWork()!!.creatorCiv)
        trade.acceptTrade(applyGifts = false)
        assertTrue("a repeated offer cannot reverse or duplicate the exchange", first.getWork() === foreignWork)
        assertEquals(1, city.greatWorks.getNumWorks())
        assertEquals(1, foreignCity.greatWorks.getNumWorks())
    }

    @Test
    fun `one-sided or mismatched art and artifact offers never transfer a work`() {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val foreignCity = game.addCity(other, game.tileMap[0, 4], replacePalace = true)
        val gallery = addBuilding(artSlots(1))
        val foreignGallery = addBuilding(artSlots(1), to = foreignCity)
        val first = GreatWorkSlot(city, gallery, 0)
        val second = GreatWorkSlot(foreignCity, foreignGallery, 0)
        val ownWork = instance(work(GreatWorkType.Art))
        val foreignWork = instance(work(GreatWorkType.Artifact), by = other)
        put(gallery, 0, ownWork)
        put(foreignGallery, 0, foreignWork, foreignCity)
        val trade = exchangeWith(other, first, second)
        assertFalse(TradeEvaluation().isTradeValid(trade.currentTrade, civ, other))
        trade.acceptTrade(applyGifts = false)
        assertTrue(first.getWork() === ownWork)
        assertTrue(second.getWork() === foreignWork)

        trade.currentTrade.theirOffers.clear()
        assertFalse(TradeEvaluation().isTradeValid(trade.currentTrade, civ, other))
        trade.acceptTrade(applyGifts = false)
        assertEquals(1, city.greatWorks.getNumWorks())
        assertEquals(1, foreignCity.greatWorks.getNumWorks())
    }

    @Test
    fun `duplicate artifact names identify the offered slot and stale slots cannot be traded`() {
        val other = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val foreignCity = game.addCity(other, game.tileMap[0, 4], replacePalace = true)
        val gallery = addBuilding(artSlots(2))
        val foreignGallery = addBuilding(artSlots(1), to = foreignCity)
        val first = GreatWorkSlot(city, gallery, 0)
        val untouched = GreatWorkSlot(city, gallery, 1)
        val second = GreatWorkSlot(foreignCity, foreignGallery, 0)
        val sameDefinition = work(GreatWorkType.Artifact)
        val offered = instance(sameDefinition, "Ancient era")
        val duplicate = instance(sameDefinition, "Medieval era")
        val foreign = instance(sameDefinition, "Renaissance era", other)
        put(gallery, 0, offered)
        put(gallery, 1, duplicate)
        put(foreignGallery, 0, foreign, foreignCity)
        val trade = exchangeWith(other, first, second)
        assertTrue(TradeEvaluation().isTradeValid(trade.currentTrade, civ, other))
        trade.acceptTrade(applyGifts = false)
        assertTrue(first.getWork() === foreign)
        assertTrue(untouched.getWork() === duplicate)
        assertTrue(second.getWork() === offered)
        assertFalse("a slot address that no longer holds its offered work must not be reusable",
            TradeEvaluation().isTradeValid(trade.currentTrade, civ, other))
    }

    @Test
    fun `AI accepts an artifact exchange that completes its unique-era unique-creator theme`() {
        val ai = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(ai)
        val aiCity = game.addCity(ai, game.tileMap[0, 4], replacePalace = true)
        val ownBuilding = addBuilding(artSlots(1))
        val themed = addBuilding(artSlots(2), to = aiCity)
        themed.themingBonuses.add(ThemingBonus().apply {
            bonus = 3
            mustBeArtifact = true
            uniqueEras = true
            requiresUniquePlayers = true
        })
        val ownSlot = GreatWorkSlot(city, ownBuilding, 0)
        val aiSlot = GreatWorkSlot(aiCity, themed, 1)
        val ourArtifact = instance(work(GreatWorkType.Artifact), "Medieval era")
        val theirArtifact = instance(work(GreatWorkType.Artifact), "Ancient era", ai)
        put(ownBuilding, 0, ourArtifact)
        put(themed, 0, instance(work(GreatWorkType.Artifact), "Ancient era", ai), aiCity)
        put(themed, 1, theirArtifact, aiCity)
        val trade = exchangeWith(ai, ownSlot, aiSlot)
        assertTrue(TradeEvaluation().isTradeAcceptable(trade.currentTrade.reverse(), ai, civ))

        trade.acceptTrade(applyGifts = false)

        assertEquals(3, aiCity.greatWorks.getThemingBonus(themed))
        assertTrue(ownSlot.getWork() === theirArtifact)
        assertTrue(aiSlot.getWork() === ourArtifact)
    }

    @Test
    fun `works are kept when the city is saved and loaded`() {
        val gallery = addBuilding(artSlots(1))
        put(gallery, 0, GreatWorkInstance("Mona Lisa", civ.civName, "Renaissance era", 42))
        val text = com.unciv.json.json().toJson(city.greatWorks)
        val loaded = com.unciv.json.json().fromJson(CityGreatWorks::class.java, text)
        val placement = loaded.placements.single()
        assertEquals(gallery.name, placement.building)
        assertEquals(0, placement.slot)
        assertEquals("Mona Lisa", placement.work.work)
        assertEquals("Renaissance era", placement.work.era)
        assertEquals(42, placement.work.turnCreated)
        assertEquals(civ.civName, placement.work.creatorCiv)
        assertEquals(city.greatWorks.placements.single().work.id, placement.work.id)
        assertEquals(placement.work.id, city.greatWorks.clone().placements.single().work.id)
        assertEquals(placement.work.work, city.greatWorks.clone().placements.single().work.work)
    }
}
