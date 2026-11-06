package com.unciv.logic.civilization

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager.nextTurn
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The effects of tenets that are not about ideologies themselves: the rules a tenet changes elsewhere */
@RunWith(GdxTestRunner::class)
class TenetEffectTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
    }

    private val enemy by lazy {
        game.addCiv().also { game.addCity(it, game.tileMap[0, 8], replacePalace = true) }
    }

    private fun woundedDamage(civ: Civilization, health: Int): Int {
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = 20
        val attacker = game.addUnit(base.name, civ, game.getTile(0, 0))
        val defender = game.addUnit(base.name, enemy, game.getTile(1, 0))
        attacker.health = health
        val damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), MapUnitCombatant(defender), randomnessFactor = 0.5f)
        attacker.destroy()
        defender.destroy()
        return damage
    }

    @Test
    fun `a wounded unit loses a third of the health it lost, and Elite Forces takes 25 points off that`() {
        val plain = game.addCiv()
        game.addCity(plain, game.tileMap[0, -8], replacePalace = true)
        val elite = game.addCiv("[-25]% wounded unit damage penalty")
        game.addCity(elite, game.tileMap[3, -5], replacePalace = true)
        assertEquals("30 at full health; 60 lost is 19 less", 24, woundedDamage(plain, 40))
        assertEquals("60 lost is 4 less", 28, woundedDamage(elite, 40))
        assertEquals(woundedDamage(plain, 100), woundedDamage(elite, 100))
    }

    @Test
    fun `a penalty cannot become a bonus`() {
        val elite = game.addCiv("[-100]% wounded unit damage penalty")
        game.addCity(elite, game.tileMap[3, -5], replacePalace = true)
        assertEquals(woundedDamage(elite, 100), woundedDamage(elite, 20))
    }

    private fun cityState(): Civilization {
        val cs = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        game.addCity(cs, game.tileMap[0, 6], replacePalace = true)
        return cs
    }

    @Test
    fun `Treaty Organization gives influence every turn with the City-States we have a trade route with`() {
        val major = game.addCiv("[+4] Influence per turn with City-States we have a trade route with", "[+4] Trade Route slots")
        game.addCity(major, game.tileMap[0, 0], replacePalace = true)
        val cs = cityState()
        major.diplomacyFunctions.makeCivilizationsMeet(cs)
        major.getDiplomacyManager(cs)!!.makePeace()
        for (tile in game.tileMap.values) tile.setExplored(major, true)

        val diplomacy = cs.getDiplomacyManager(major)!!
        val before = diplomacy.getInfluence()
        diplomacy.nextTurn()
        assertEquals("without a route nothing changes", before, diplomacy.getInfluence(), 0.001f)

        val caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        val unit = game.addUnit(caravan.name, major, major.cities.first().getCenterTile())
        val route = major.tradeRoutes.establish(unit, cs.cities.first(), TradeRouteKind.International)
        assertTrue("a route to a City-State can be established", route != null)
        diplomacy.nextTurn()
        assertEquals(before + 4f * game.gameInfo.speed.goldGiftModifier, diplomacy.getInfluence(), 0.001f)
    }

    @Test
    fun `Gunboat Diplomacy makes the military rank count more in the tribute willingness`() {
        val gunboats = game.addCiv("[+50]% strength when intimidating City-States")
        val rival = game.addCiv()
        game.addCity(rival, game.tileMap[0, -6], replacePalace = true)
        game.addCity(gunboats, game.tileMap[4, -6], replacePalace = true)
        val cs = cityState()
        val base = game.createBaseUnit("Sword")
        base.movement = 2
        base.strength = 30
        gunboats.diplomacyFunctions.makeCivilizationsMeet(cs)
        rival.diplomacyFunctions.makeCivilizationsMeet(cs)
        for ((x, y) in listOf(3 to -3, 3 to -2, 4 to -4)) game.addUnit(base.name, gunboats, game.getTile(x, y))
        val global = game.ruleset.modOptions.constants.tributeGlobalModifier
        // the strongest of the civilizations: the whole of the global modifier, and half of it more
        assertEquals(global * 3 / 2, cs.cityStateFunctions.getTributeModifiers(gunboats, requireWholeList = true)["Military Rank"]!!)
        assertEquals("the weaker of two civilizations gets half of it, and no policy changes that", global / 2,
            cs.cityStateFunctions.getTributeModifiers(rival, requireWholeList = true)["Military Rank"]!!)
    }
}
