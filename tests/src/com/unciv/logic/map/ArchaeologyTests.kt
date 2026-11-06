package com.unciv.logic.map

import com.unciv.logic.automation.unit.ArchaeologistAutomation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyChoice
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.ArchaeologyRecord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.tile.TileImprovement
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
import kotlin.random.Random

@RunWith(GdxTestRunner::class)
class ArchaeologyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var city: City

    private val site get() = game.ruleset.tileResources["Antiquity site"]!!
    private val hiddenSite get() = game.ruleset.tileResources["Hidden Antiquity site"]!!
    private val dig: TileImprovement get() = game.ruleset.tileImprovements["Archaeological Dig"]!!

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        other = game.addCiv()
        civ = game.addCiv(isPlayer = true)
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        game.addCity(other, game.tileMap[0, 7], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        game.gameInfo.currentPlayerCiv = other
    }

    private fun tileAt(x: Int, y: Int) = game.tileMap[x, y]

    private fun recordOn(tile: Tile, kind: String = ArtifactClasses.MeleeBattle, era: String = "Ancient era", civ1: Civilization = civ, civ2: Civilization? = other, work: String? = null) {
        tile.archaeologyRecord = ArchaeologyRecord(kind, era, civ1.civName, civ2?.civName, work)
    }

    private fun siteTile(x: Int = 0, y: Int = -3, hidden: Boolean = false): Tile {
        val tile = tileAt(x, y)
        tile.tileResource = if (hidden) hiddenSite else site
        tile.resourceAmount = 1
        return tile
    }

    private fun addGallery(slots: String = "Has [4] [Art/Artifact] Great Work slots") =
        city.cityConstructions.addBuilding(game.createBuilding(slots))

    //region What is remembered
    @Test
    fun `a tile remembers the most memorable thing that happened on it`() {
        val tile = tileAt(0, -2)
        tile.addArchaeologyRecord(ArtifactClasses.MeleeBattle, civ, other)
        tile.addArchaeologyRecord(ArtifactClasses.RangedBattle, other, civ)
        assertEquals("a ranged battle is less than a melee one", ArtifactClasses.MeleeBattle, tile.archaeologyRecord!!.kind)
        tile.addArchaeologyRecord(ArtifactClasses.RazedCity, other, civ)
        assertEquals(ArtifactClasses.RazedCity, tile.archaeologyRecord!!.kind)
        assertEquals(other.civName, tile.archaeologyRecord!!.civ1)
        tile.addArchaeologyRecord(ArtifactClasses.AncientRuin, civ)
        assertEquals("a ruin is less than a razed city", ArtifactClasses.RazedCity, tile.archaeologyRecord!!.kind)
    }

    @Test
    fun `nothing is remembered for barbarians, and nothing once the sites have appeared`() {
        val tile = tileAt(0, -2)
        tile.addArchaeologyRecord(ArtifactClasses.MeleeBattle, game.addBarbarianCiv(), civ)
        assertNull(tile.archaeologyRecord)
        game.gameInfo.archaeologyTriggered = true
        tile.addArchaeologyRecord(ArtifactClasses.MeleeBattle, civ, other)
        assertNull(tile.archaeologyRecord)
    }

    @Test
    fun `a fight between units is remembered on the tile of the defender, as melee or ranged, but not one against a city`() {
        val melee = game.addUnit("Warrior", civ, tileAt(5, -3))
        val victim = game.addUnit("Warrior", other, tileAt(4, -3))
        Battle.attack(MapUnitCombatant(melee), MapUnitCombatant(victim))
        assertEquals(ArtifactClasses.MeleeBattle, tileAt(4, -3).archaeologyRecord!!.kind)
        assertEquals(civ.civName, tileAt(4, -3).archaeologyRecord!!.civ1)
        assertEquals(other.civName, tileAt(4, -3).archaeologyRecord!!.civ2)

        val archer = game.addDefaultRangedUnitWithUniques(civ, tileAt(-6, 4))
        val target = game.addUnit("Warrior", other, tileAt(-5, 4))
        Battle.attack(MapUnitCombatant(archer), MapUnitCombatant(target))
        assertEquals(ArtifactClasses.RangedBattle, tileAt(-5, 4).archaeologyRecord!!.kind)
    }

    @Test
    fun `a razed city leaves what its founder made and who razed it`() {
        val colony = game.addCity(civ, tileAt(-4, 2))
        val captured = game.addCity(other, tileAt(4, -3))
        captured.moveToCiv(civ)
        captured.destroyCity(overrideSafeties = true)
        val record = tileAt(4, -3).archaeologyRecord!!
        assertEquals(ArtifactClasses.RazedCity, record.kind)
        assertEquals(other.civName, record.civ1)
        assertEquals(civ.civName, record.civ2)
        assertNull(colony.getCenterTile().archaeologyRecord)
    }
    //endregion

    //region Making the sites
    private fun worldOfSix(): List<Civilization> {
        val all = mutableListOf(civ, other)
        for (i in 0 until 4) {
            val extra = game.addCiv()
            game.addCity(extra, tileAt(-8 + i * 4, -9 + i), replacePalace = true)
            all.add(extra)
        }
        return all
    }

    private fun siteTiles() = game.tileMap.values.filter { it.resource == site.name || it.resource == hiddenSite.name }

    @Test
    fun `five to eight sites appear for every major civilization, once the first civilization knows Archaeology`() {
        val majors = worldOfSix()
        assertTrue(siteTiles().isEmpty())
        civ.tech.addTechnology("Archaeology")
        val sites = siteTiles()
        assertTrue("${sites.size} sites for ${majors.size} civilizations", sites.size in 5 * majors.size..8 * majors.size)
        assertTrue(game.gameInfo.archaeologyTriggered)
        assertTrue("every site has a history", sites.all { it.archaeologyRecord != null })

        val before = sites.map { it.position }.toSet()
        other.tech.addTechnology("Archaeology")
        assertEquals("they appear only once", before, siteTiles().map { it.position }.toSet())
    }

    @Test
    fun `about a third of the sites are hidden, and writings are only found on those`() {
        worldOfSix()
        val sites = ArchaeologyFunctions.spawnSites(game.gameInfo, Random(7))
        val hidden = sites.filter { it.resource == hiddenSite.name }
        assertTrue("${hidden.size} of ${sites.size} are hidden", hidden.size in sites.size / 10..sites.size * 6 / 10)
        val writings = sites.filter { it.archaeologyRecord!!.kind == ArtifactClasses.Writing }
        assertTrue(writings.all { it.resource == hiddenSite.name })
        assertEquals(minOf(15, sites.size * 30 / 100 * 30 / 100), writings.size)
        val works = writings.map { it.archaeologyRecord!!.work }
        assertEquals("each writing is a different work", works.toSet().size, works.size)
        assertTrue(works.all { game.ruleset.greatWorks[it]!!.archaeologyOnly })
    }

    @Test
    fun `the same seed gives the same sites`() {
        worldOfSix()
        val first = ArchaeologyFunctions.spawnSites(game.gameInfo, Random(3)).map { it.position }
        for (tile in game.tileMap.values) { tile.tileResource = null; tile.archaeologyRecord = null }
        val second = ArchaeologyFunctions.spawnSites(game.gameInfo, Random(3)).map { it.position }
        assertEquals(first, second)
    }

    @Test
    fun `no site is on water, in the mountains, at a resource or on a natural wonder`() {
        worldOfSix()
        val water = game.setTileTerrain(tileAt(5, 5).position, "Ocean")
        val mountain = game.setTileTerrain(tileAt(-5, 5).position, "Mountain")
        val withResource = tileAt(5, -5).also { it.tileResource = game.ruleset.tileResources["Cattle"] }
        val sites = ArchaeologyFunctions.spawnSites(game.gameInfo, Random(1))
        assertTrue(sites.none { it == water || it == mountain || it == withResource })
        assertEquals(0, ArchaeologyFunctions.getSiteWeight(water, emptySet(), 5))
        assertEquals(0, ArchaeologyFunctions.getSiteWeight(mountain, emptySet(), 5))
        assertEquals(0, ArchaeologyFunctions.getSiteWeight(withResource, emptySet(), 5))
    }

    @Test
    fun `deserts are preferred to other land by three to two, and a place with history to one without`() {
        val desert = game.setTileTerrain(tileAt(2, -2).position, "Desert")
        val grass = game.setTileTerrain(tileAt(-2, 2).position, "Grassland")
        assertEquals(ArchaeologyFunctions.getSiteWeight(grass, emptySet(), 0) * 3, ArchaeologyFunctions.getSiteWeight(desert, emptySet(), 0) * 2)
        recordOn(desert, ArtifactClasses.RazedCity)
        val withHistory = ArchaeologyFunctions.getSiteWeight(desert, emptySet(), 0)
        desert.archaeologyRecord = null
        assertTrue(withHistory > 3 * ArchaeologyFunctions.getSiteWeight(desert, emptySet(), 0))
    }

    @Test
    fun `a site close to another one is worth less`() {
        val tile = tileAt(2, -2)
        val alone = ArchaeologyFunctions.getSiteWeight(tile, emptySet(), 0)
        val crowded = ArchaeologyFunctions.getSiteWeight(tile, setOf(tileAt(2, -3), tileAt(2, -4)), 0)
        assertEquals("one site next to it and one two tiles away: divisor 1 + 2 + 2 + 1", alone / 6, crowded)
    }
    //endregion

    //region Seeing sites
    @Test
    fun `a site is seen with the technology, a hidden site with the policy that reveals it`() {
        assertFalse(civ.canSeeResource(site))
        assertFalse(civ.canSeeResource(hiddenSite))
        civ.tech.addTechnology("Archaeology")
        assertTrue(civ.canSeeResource(site))
        assertFalse("the technology does not show the hidden ones", civ.canSeeResource(hiddenSite))
        civ.policies.adopt(game.ruleset.policies["Exploration Complete"]!!, true)
        assertTrue(civ.canSeeResource(hiddenSite))
    }
    //endregion

    //region Digging and what to do with the finds
    private fun archaeologist(tile: Tile): MapUnit {
        game.gameInfo.archaeologyTriggered = true // or learning Archaeology would make sites
        civ.tech.addTechnology("Archaeology")
        return game.addUnit("Archaeologist", civ, tile)
    }

    private fun completeDig(unit: MapUnit, tile: Tile) {
        tile.startWorkingOnImprovement(dig, civ, unit)
        repeat(20) { if (tile.improvementInProgress != null) tile.doWorkerTurn(unit) }
    }

    @Test
    fun `a completed dig takes the site away, leaves the history and asks the player what to do`() {
        val tile = siteTile()
        recordOn(tile)
        val unit = archaeologist(tile)
        completeDig(unit, tile)

        assertNull(tile.resource)
        assertEquals(dig.name, tile.improvement)
        assertNotNull(tile.archaeologyRecord)
        val alert = civ.popupAlerts.single { it.type == AlertType.ArchaeologyChoice }
        assertEquals("${tile.position.x},${tile.position.y}", alert.value)
        assertTrue("the archaeologist waits for the choice", unit in civ.units.getCivUnits())
        assertTrue(ArchaeologyFunctions.hasCompletedDig(tile))
    }

    @Test
    fun `a player who has not chosen is asked again, once`() {
        val tile = siteTile()
        recordOn(tile)
        val unit = archaeologist(tile)
        completeDig(unit, tile)
        civ.popupAlerts.clear() // as after loading a game, or the popup having been closed
        ArchaeologyFunctions.resolvePendingDigs(civ)
        assertEquals(1, civ.popupAlerts.count { it.type == AlertType.ArchaeologyChoice })
        ArchaeologyFunctions.resolvePendingDigs(civ)
        assertEquals("not asked twice", 1, civ.popupAlerts.count { it.type == AlertType.ArchaeologyChoice })
    }

    @Test
    fun `the AI chooses for a dig that was left complete`() {
        val tile = siteTile()
        recordOn(tile)
        val unit = archaeologist(tile)
        completeDig(unit, tile)
        ArchaeologyFunctions.resolvePendingDigs(civ, aiControlled = true)
        assertFalse(unit in civ.units.getCivUnits())
        assertEquals("with no room for artifacts, a Landmark", "Landmark", tile.improvement)
    }

    @Test
    fun `an artifact goes to a free slot of the closest city, is attributed as chosen, and dated to the find`() {
        addGallery()
        val tile = siteTile()
        recordOn(tile, ArtifactClasses.MeleeBattle, "Ancient era", civ1 = other, civ2 = civ)
        val unit = archaeologist(tile)
        completeDig(unit, tile)

        assertTrue(ArchaeologyChoice.ArtifactOfFirst in ArchaeologyFunctions.getChoices(civ, tile))
        assertTrue(ArchaeologyChoice.ArtifactOfSecond in ArchaeologyFunctions.getChoices(civ, tile))
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.ArtifactOfSecond, unit))

        val work = city.greatWorks.getWorks().single()
        assertEquals(civ.civName, work.creatorCiv)
        assertEquals("Ancient era", work.era)
        val definition = game.ruleset.greatWorks[work.work]!!
        assertEquals(GreatWorkType.Artifact, definition.type)
        assertEquals(ArtifactClasses.MeleeBattle, definition.artifactClass)
        assertNull("the dig is gone", tile.improvement)
        assertFalse(unit in civ.units.getCivUnits())
    }

    @Test
    fun `artifacts of the era of the find come before those that fit any era`() {
        addGallery()
        val ancient = siteTile(0, -3)
        recordOn(ancient, ArtifactClasses.MeleeBattle, "Ancient era")
        val modern = siteTile(1, -3)
        recordOn(modern, ArtifactClasses.MeleeBattle, "Modern era")
        for (seed in 0 until 12) {
            assertEquals("a find of the Ancient era is an Ancient artifact", "Ancient era", ArchaeologyFunctions.getArtifact(ancient, Random(seed))!!.era)
            assertNull("there are none specific to the Modern era", ArchaeologyFunctions.getArtifact(modern, Random(seed))!!.era)
        }
    }

    @Test
    fun `there is no artifact without a free slot, only the Landmark`() {
        val tile = siteTile()
        recordOn(tile)
        val unit = archaeologist(tile)
        completeDig(unit, tile)
        assertEquals(listOf(ArchaeologyChoice.Landmark), ArchaeologyFunctions.getChoices(civ, tile))
        assertFalse(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.ArtifactOfFirst, unit))
        assertTrue("nothing was used up", unit in civ.units.getCivUnits())
    }

    @Test
    fun `a writing takes a literature slot or becomes culture`() {
        val tile = siteTile(hidden = true)
        recordOn(tile, ArtifactClasses.Writing, "Classical era", work = "Rosetta Stone")
        val unit = archaeologist(tile)
        completeDig(unit, tile)
        assertEquals(listOf(ArchaeologyChoice.Landmark, ArchaeologyChoice.CultureBoost), ArchaeologyFunctions.getChoices(civ, tile))

        addGallery("Has [1] [Literature] Great Work slots")
        assertEquals(listOf(ArchaeologyChoice.Landmark, ArchaeologyChoice.Writing, ArchaeologyChoice.CultureBoost), ArchaeologyFunctions.getChoices(civ, tile))
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Writing, unit))
        assertEquals("Rosetta Stone", city.greatWorks.getWorks().single().work)
        assertEquals("Classical era", city.greatWorks.getWorks().single().era)
    }

    @Test
    fun `culture from a writing is what a Great Writer would give`() {
        val tile = siteTile(hidden = true)
        recordOn(tile, ArtifactClasses.Writing, "Classical era", work = "Rosetta Stone")
        val unit = archaeologist(tile)
        completeDig(unit, tile)
        for (turn in 0 until 8) { game.gameInfo.turns = turn; civ.policies.endTurn(5) }
        val expected = civ.policies.getCultureFromGreatWriter()
        val before = civ.policies.storedCulture
        assertTrue(expected > 0)
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.CultureBoost, unit))
        assertEquals(before + expected, civ.policies.storedCulture)
        assertNull(tile.improvement)
    }

    @Test
    fun `a Landmark remembers the era of the find, and makes a culture for every era since`() {
        val tile = tileAt(0, -2)
        recordOn(tile, ArtifactClasses.AncientRuin, "Medieval era")
        val unit = archaeologist(tile)  // which brings the civilization to the era of Archaeology
        val erasSince = civ.getEraNumber() - game.ruleset.eras["Medieval era"]!!.eraNumber
        assertTrue(erasSince > 0)
        tile.setImprovement(dig.name)

        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, unit))
        assertEquals("Landmark", tile.improvement)
        assertEquals(erasSince.toFloat(), tile.stats.getTileStats(null, civ).culture, 0.001f)
    }

    @Test
    fun `a Landmark of the era the civilization is in gives nothing`() {
        val tile = tileAt(0, -2)
        recordOn(tile, ArtifactClasses.AncientRuin, civ.getEra().name)
        tile.setImprovement("Landmark")
        val culture = tile.stats.getTileStats(null, civ).culture
        assertEquals(0f, culture, 0.001f)
    }

    @Test
    fun `a Landmark on the land of a city-state gains its friendship`() {
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val stateCity = game.addCity(cityState, tileAt(-6, 2))
        civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        val tile = tileAt(-5, 2)
        game.addTileToCity(stateCity, tile)
        tile.setOwningCity(stateCity)
        recordOn(tile)
        tile.setImprovement(dig.name)
        val before = cityState.getDiplomacyManager(civ)!!.getInfluence()
        assertTrue(ArchaeologyFunctions.execute(civ, tile, ArchaeologyChoice.Landmark, null))
        assertEquals(before + 50f, cityState.getDiplomacyManager(civ)!!.getInfluence(), 0.001f)
    }

    @Test
    fun `the AI takes a writing if it has a slot, culture if not, and a Landmark when it has no room for artifacts`() {
        val writing = siteTile(hidden = true)
        recordOn(writing, ArtifactClasses.Writing, "Classical era", work = "Rosetta Stone")
        assertEquals(ArchaeologyChoice.CultureBoost, ArchaeologyFunctions.chooseForAi(civ, writing, 0, 0))
        addGallery("Has [1] [Literature] Great Work slots")
        assertEquals(ArchaeologyChoice.Writing, ArchaeologyFunctions.chooseForAi(civ, writing, 0, 0))

        val find = siteTile(1, -3)
        recordOn(find)
        assertEquals("no room for artifacts", ArchaeologyChoice.Landmark, ArchaeologyFunctions.chooseForAi(civ, find, 0, 0))
    }

    @Test
    fun `the AI takes an artifact from land that is not its own, and one that is its own by preference`() {
        addGallery()
        val find = siteTile(1, -3)
        recordOn(find, civ1 = other, civ2 = civ)
        assertEquals(ArchaeologyChoice.ArtifactOfSecond, ArchaeologyFunctions.chooseForAi(civ, find, 0, 0))
        recordOn(find, civ1 = civ, civ2 = other)
        assertEquals(ArchaeologyChoice.ArtifactOfFirst, ArchaeologyFunctions.chooseForAi(civ, find, 0, 0))
    }
    //endregion

    //region Archaeologists
    @Test
    fun `the AI sends an archaeologist to the nearest site it can see and digs there`() {
        val target = siteTile(0, -4)
        recordOn(target)
        game.gameInfo.archaeologyTriggered = true
        civ.tech.addTechnology("Archaeology")
        val unit = game.addUnit("Archaeologist", civ, tileAt(0, -1))
        repeat(5) {
            if (target.improvementInProgress == null) {
                unit.currentMovement = unit.getMaxMovement().toFloat()
                assertTrue(ArchaeologistAutomation.automate(unit))
            }
        }
        assertEquals(target, unit.currentTile)
        assertEquals(dig.name, target.improvementInProgress)
    }

    @Test
    fun `an archaeologist needs a University in the city, and the technology`() {
        val archaeologist = game.ruleset.units["Archaeologist"]!!
        city.cityConstructions.addBuilding("Library")
        civ.tech.addTechnology("Archaeology")
        assertFalse(archaeologist.isBuildable(city.cityConstructions))
        city.cityConstructions.addBuilding("University")
        assertTrue(archaeologist.isBuildable(city.cityConstructions))
    }
    //endregion

    @Test
    fun `history is saved and loaded`() {
        val tile = tileAt(0, -2)
        recordOn(tile, ArtifactClasses.Writing, "Medieval era", work = "Book of Kells")
        val text = com.unciv.json.json().toJson(tile.archaeologyRecord)
        val loaded = com.unciv.json.json().fromJson(ArchaeologyRecord::class.java, text)
        assertEquals(ArtifactClasses.Writing, loaded.kind)
        assertEquals("Medieval era", loaded.era)
        assertEquals("Book of Kells", loaded.work)
        assertEquals(civ.civName, loaded.civ1)
        assertEquals(other.civName, loaded.civ2)
        assertEquals("Medieval era", tile.clone().archaeologyRecord!!.era)
    }
}
