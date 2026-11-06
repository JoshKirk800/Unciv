package com.unciv.logic.battle

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitPromotions
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class MysticBladeTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var indonesia: Civilization
    private lateinit var opponent: Civilization

    private val outcomes = setOf("Invulnerability", "Sneak Attack", "Heroism", "Ambition",
        "Restlessness", "Recruitment", "Enemy Blade", "Evil Spirits")

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        indonesia = game.addCiv(game.ruleset.nations["Indonesia"]!!)
        opponent = game.addCiv()
        indonesia.diplomacyFunctions.makeCivilizationsMeet(opponent)
        indonesia.getDiplomacyManager(opponent)!!.declareWar()
    }

    private fun kris(x: Int = 0, y: Int = 0) =
        game.addUnit("Kris Swordsman", indonesia, game.getTile(x, y)).also {
            // TestGame.addUnit bypasses TileMap's normal base-unit promotion grant.
            it.promotions.addPromotion("Mystic Blade", isFree = true)
        }
    private fun warrior(x: Int = 1, y: Int = 0) = game.addUnit("Warrior", opponent, game.getTile(x, y))
    private fun fightAgain(unit: MapUnit) {
        val tile = unit.getTile().neighbors.first { it.militaryUnit == null && it.getOwner() == null }
        val enemy = game.addUnit("Warrior", opponent, tile)
        Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(enemy))
    }


    private fun forceOutcome(unit: MapUnit, name: String) {
        unit.promotions.removePromotion("Mystic Blade")
        unit.promotions.addPromotion(name, isFree = true)
    }

    @Test
    fun `first real combat replaces Mystic Blade once with one of eight equally eligible outcomes`() {
        val unit = kris()
        val enemy = warrior()
        assertTrue("Mystic Blade" in unit.promotions.promotions)

        Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(enemy))

        assertFalse("Mystic Blade" in unit.promotions.promotions)
        val outcome = unit.promotions.promotions.single()
        assertTrue("Invalid outcome: $outcome", outcome in outcomes)
        assertEquals(0, unit.promotions.numberOfPromotions)
        fightAgain(unit)
        assertEquals(setOf(outcome), unit.promotions.promotions)
        assertEquals(0, unit.promotions.numberOfPromotions)
    }

    @Test
    fun `defending and fighting at the barbarian XP cap still reveals the blade`() {
        val unit = kris()
        val barbarian = game.addBarbarianCiv()
        val attacker = game.addUnit("Brute", barbarian, game.getTile(1, 0))
        unit.promotions.XP = game.ruleset.modOptions.constants.maxXPfromBarbarians
        val xpBefore = unit.promotions.XP

        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(unit))

        assertEquals(xpBefore, unit.promotions.XP)
        assertTrue(unit.promotions.promotions.single() in outcomes)
    }

    @Test
    fun `ordinary swordsmen do not roll a Kris promotion`() {
        val ordinary = game.addUnit("Swordsman", indonesia, game.getTile(0, 0))
        Battle.attack(MapUnitCombatant(ordinary), MapUnitCombatant(warrior()))
        assertTrue(ordinary.promotions.promotions.none { it in outcomes || it == "Mystic Blade" })
    }

    @Test
    fun `selected outcome survives serialization and cannot reroll after loading`() {
        val unit = kris()
        val enemy = warrior()
        Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(enemy))
        val outcome = unit.promotions.promotions.single()

        val restored = json().fromJson(UnitPromotions::class.java, json().toJson(unit.promotions))
        restored.setTransients(unit)
        unit.promotions = restored
        unit.updateUniques()
        fightAgain(unit)

        assertEquals(setOf(outcome), unit.promotions.promotions)
        assertEquals(0, unit.promotions.numberOfPromotions)
    }

    @Test
    fun `combat blessings and curses affect strength healing movement and general aura`() {
        val unit = kris()
        val enemy = warrior()
        val combatant = MapUnitCombatant(unit)
        val target = MapUnitCombatant(enemy)
        val originalAttack = BattleDamage.getAttackingStrength(combatant, target, enemy.getTile())
        val originalDefense = BattleDamage.getDefendingStrength(target, combatant, unit.getTile())

        forceOutcome(unit, "Ambition")
        assertTrue(BattleDamage.getAttackingStrength(combatant, target, enemy.getTile()) > originalAttack)
        assertTrue(BattleDamage.getDefendingStrength(target, combatant, unit.getTile()) < originalDefense)
        unit.promotions.removePromotion("Ambition")

        unit.promotions.addPromotion("Evil Spirits", isFree = true)
        assertTrue(BattleDamage.getAttackingStrength(combatant, target, enemy.getTile()) < originalAttack)
        assertTrue(BattleDamage.getDefendingStrength(target, combatant, unit.getTile()) < originalDefense)
        unit.promotions.removePromotion("Evil Spirits")

        unit.health = 40
        val defenseBefore = BattleDamage.getDefendingStrength(target, combatant, unit.getTile())
        unit.promotions.addPromotion("Invulnerability", isFree = true)
        assertTrue(BattleDamage.getDefendingStrength(target, combatant, unit.getTile()) > defenseBefore)
        assertEquals(30, unit.getHealAmountForCurrentTile())
        unit.promotions.removePromotion("Invulnerability")

        val movement = unit.getMaxMovement()
        unit.promotions.addPromotion("Restlessness", isFree = true)
        assertEquals(movement + 1, unit.getMaxMovement())
        unit.promotions.removePromotion("Restlessness")

        val ally = game.addUnit("Warrior", indonesia, game.getTile(0, 1))
        val allyCombatant = MapUnitCombatant(ally)
        assertEquals(0, GreatGeneralImplementation.getGreatGeneralBonus(allyCombatant, target, CombatAction.Attack).second)
        unit.promotions.addPromotion("Heroism", isFree = true)
        assertEquals(15, GreatGeneralImplementation.getGreatGeneralBonus(allyCombatant, target, CombatAction.Attack).second)
    }

    @Test
    fun `Restlessness retains movement after an attack`() {
        val unit = kris()
        forceOutcome(unit, "Restlessness")
        val enemy = warrior()

        Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(enemy))

        assertEquals(1, unit.attacksThisTurn)
        assertTrue(unit.currentMovement > 0f)
    }

    @Test
    fun `Sneak Attack increases the actual flanking modifier`() {
        val unit = kris()
        val enemy = warrior()
        game.addUnit("Warrior", indonesia, game.getTile(1, -1))
        val attacker = MapUnitCombatant(unit)
        val defender = MapUnitCombatant(enemy)
        val ordinaryFlanking = BattleDamage.getAttackModifiers(attacker, defender, unit.getTile())["Flanking"]
        assertTrue(ordinaryFlanking > 0)

        forceOutcome(unit, "Sneak Attack")

        assertTrue(BattleDamage.getAttackModifiers(attacker, defender, unit.getTile())["Flanking"] > ordinaryFlanking)
    }

    @Test
    fun `Recruitment heals only after defeating non-barbarians`() {
        val unit = kris()
        forceOutcome(unit, "Recruitment")
        unit.health = 40
        val enemy = warrior().apply { health = 1 }
        val result = Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(enemy))
        assertTrue(enemy.isDestroyed)
        assertEquals((40 - result.defenderDealt + 50).coerceAtMost(100), unit.health)

        val barbarian = game.addBarbarianCiv()
        val brute = game.addUnit("Brute", barbarian, game.getTile(2, 0)).apply { health = 1 }
        unit.health = 40
        val barbarianResult = Battle.attack(MapUnitCombatant(unit), MapUnitCombatant(brute))
        assertTrue(brute.isDestroyed)
        assertEquals(40 - barbarianResult.defenderDealt, unit.health)
    }

    @Test
    fun `Enemy Blade damages only on hostile territory at turn end`() {
        val unit = kris()
        forceOutcome(unit, "Enemy Blade")
        unit.health = 80
        UnitTurnManager(unit).endTurn()
        assertEquals(90, unit.health) // normal healing outside hostile territory

        val hostileCity = game.addCity(opponent, game.getTile(0, 2))
        game.addTileToCity(hostileCity, unit.getTile())
        unit.getTile().setOwningCity(hostileCity)
        unit.health = 80
        UnitTurnManager(unit).endTurn()
        assertEquals(70, unit.health) // heals 10, then takes 20 attrition
    }
}
