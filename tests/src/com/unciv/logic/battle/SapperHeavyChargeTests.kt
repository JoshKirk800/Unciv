package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Brave New World: Sapper (CvUnit::IsNearSapper, SAPPED_CITY_ATTACK_MODIFIER 50, SAPPER_BONUS_RANGE 2) and Heavy Charge
 *  (CvUnitCombat::GenerateMeleeCombatInfo / ResolveMeleeCombat, CvUnit::CanFallBackFromMelee / DoFallBackFromMelee). */
@RunWith(BaseTestRunner::class)
class SapperHeavyChargeTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
    }

    private fun attackStrength(attacker: MapUnitCombatant, defender: ICombatant) =
        BattleDamage.getAttackingStrength(attacker, defender, defender.getTile())

    // region Sapper

    private val sapperUnique = "[+50]% Strength bonus for [Military] units within [2] tiles attacking a city this unit is adjacent to"

    /** Ranged, so that it does not add a flanking bonus to the melee attacker */
    private fun addSapper(tile: Tile) = game.addDefaultRangedUnitWithUniques(civ, tile, sapperUnique)

    private fun cityWithAttackerAtDistance(attackerDistance: Int): Triple<CityCombatant, Tile, MapUnitCombatant> {
        val city = game.addCity(enemy, game.getTile(0, 0))
        val cityTile = city.getCenterTile()
        val attackerTile = cityTile.getTilesAtDistance(attackerDistance).first()
        val attacker = game.addDefaultMeleeUnitWithUniques(civ, attackerTile)
        return Triple(CityCombatant(city), cityTile, MapUnitCombatant(attacker))
    }

    @Test
    fun `a sapper next to the city gives nearby attackers 50 percent`() {
        val (city, cityTile, attacker) = cityWithAttackerAtDistance(2)
        val without = attackStrength(attacker, city)
        val sapperTile = cityTile.neighbors.first { it in attacker.getTile().neighbors }
        addSapper(sapperTile)
        assertEquals(without * 1.5f, attackStrength(attacker, city), 0.001f)
    }

    @Test
    fun `a sapper not next to the city, out of range, or on the attackers tile gives nothing`() {
        val (city, cityTile, attacker) = cityWithAttackerAtDistance(2)
        val without = attackStrength(attacker, city)
        // two tiles from the city: not adjacent to it
        addSapper(attacker.getTile().neighbors.first { it !in cityTile.neighbors && it != cityTile })
        assertEquals("sapper not adjacent to the city", without, attackStrength(attacker, city), 0.001f)
        // adjacent to the city but 3 tiles from the attacker: the attacker is in range 2 only
        val farSapperTile = cityTile.neighbors.first { it.aerialDistanceTo(attacker.getTile()) == 3 }
        addSapper(farSapperTile)
        assertEquals("sapper out of range", without, attackStrength(attacker, city), 0.001f)
    }

    @Test
    fun `a sapper only helps units attacking a city, not units`() {
        val (_, cityTile, attacker) = cityWithAttackerAtDistance(2)
        val sapperTile = cityTile.neighbors.first { it in attacker.getTile().neighbors }
        addSapper(sapperTile)
        val victimTile = attacker.getTile().neighbors.first { it != sapperTile && it != cityTile && !it.isCityCenter() && it.getFirstUnit() == null }
        val victim = MapUnitCombatant(game.addDefaultMeleeUnitWithUniques(enemy, victimTile))
        val modifiers = BattleDamage.getAttackModifiers(attacker, victim, victim.getTile())
        assertTrue("no Sapper modifier against a unit", "Sapper" !in modifiers)
    }

    // endregion

    // region Heavy Charge

    /** Attacker at (0,0) and defender on a neighbour; returns the defender's neighbours that are behind it (two tiles from the attacker) */
    private fun setupCharge(blockBehind: Boolean): Triple<MapUnitCombatant, MapUnitCombatant, List<Tile>> {
        val attackerTile = game.getTile(0, 0)
        val defenderTile = attackerTile.neighbors.first()
        val behind = defenderTile.neighbors.filter { it.aerialDistanceTo(attackerTile) == 2 }.toList()
        if (blockBehind) for (tile in behind) game.setTileTerrain(tile.position, "Ocean")
        val attacker = game.addDefaultMeleeUnitWithUniques(civ, attackerTile, "Heavy Charge")
        val defender = game.addDefaultMeleeUnitWithUniques(enemy, defenderTile)
        return Triple(MapUnitCombatant(attacker), MapUnitCombatant(defender), behind)
    }

    @Test
    fun `heavy charge adds 50 percent against a unit that cannot fall back`() {
        val (attacker, defender, _) = setupCharge(blockBehind = true)
        val plain = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(-1, 0))
        assertEquals(attackStrength(MapUnitCombatant(plain), defender) * 1.5f, attackStrength(attacker, defender), 0.001f)
    }

    @Test
    fun `heavy charge adds nothing against a unit that can fall back`() {
        val (attacker, defender, behind) = setupCharge(blockBehind = false)
        val blockedTwo = behind.take(2)
        for (tile in blockedTwo) game.setTileTerrain(tile.position, "Ocean")
        val plain = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(-1, 0))
        assertEquals(attackStrength(MapUnitCombatant(plain), defender), attackStrength(attacker, defender), 0.001f)
    }

    @Test
    fun `an attacker that does more damage pushes the defender straight back and advances`() {
        val (attacker, defender, behind) = setupCharge(blockBehind = false)
        val attackerStart = attacker.getTile()
        val defenderStart = defender.getTile()
        attacker.unit.promotions.XP = 0
        // Far stronger than the defender: it survives but takes more damage than it deals
        game.setTileFeatures(defenderStart.position)
        attacker.unit.baseUnit.strength = 20
        defender.unit.baseUnit.strength = 8

        val result = Battle.attack(attacker, defender)

        assertTrue("the attacker did more damage", result.attackerDealt > result.defenderDealt)
        assertTrue("the defender survived", !defender.isDefeated())
        assertNotEquals("the defender fell back", defenderStart, defender.getTile())
        assertTrue("it fell back to a tile behind it", defender.getTile() in behind)
        assertSame("the attacker advanced", defenderStart, attacker.getTile())
        assertNotEquals(attackerStart, attacker.getTile())
    }

    // endregion
}
