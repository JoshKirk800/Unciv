package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.TurnManager
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class BattleDamageTest {
    private lateinit var attackerCiv: Civilization
    private lateinit var defenderCiv: Civilization

    private lateinit var defaultAttackerTile: Tile
    private lateinit var defaultDefenderTile: Tile

    private lateinit var defaultAttackerUnit: MapUnit
    private lateinit var defaultDefenderUnit: MapUnit

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(4)
        attackerCiv = testGame.addCiv()
        defenderCiv = testGame.addCiv()

        defaultAttackerTile = testGame.getTile(1, 1)
        defaultAttackerUnit = testGame.addUnit("Warrior", attackerCiv, defaultAttackerTile)
        defaultDefenderTile = testGame.getTile(0, 1)
        defaultDefenderUnit = testGame.addUnit("Warrior", defenderCiv, defaultDefenderTile)
    }

    @Test
    fun `should retrieve modifiers from policies`() {
        // given
        val policy = testGame.createPolicy("[+25]% Strength <when attacking> <for [Military] units>")
        attackerCiv.policies.adopt(policy, true)

        // when
        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        // then
        assertEquals(1, attackModifiers.size)
        assertEquals(25, attackModifiers.sumValues())
    }

    @Test
    fun `should keep promotion modifiers in ruleset order`() {
        // Promotion names are chosen so HashSet iteration reverses the ruleset insertion order.
        val alphaPromotion = testGame.createUnitPromotion("[+10]% Strength <when attacking>")
        testGame.ruleset.unitPromotions.remove(alphaPromotion.name)
        alphaPromotion.name = "Alpha"
        testGame.ruleset.unitPromotions[alphaPromotion.name] = alphaPromotion

        val zetaPromotion = testGame.createUnitPromotion("[+20]% Strength <when attacking>")
        testGame.ruleset.unitPromotions.remove(zetaPromotion.name)
        zetaPromotion.name = "Zeta"
        testGame.ruleset.unitPromotions[zetaPromotion.name] = zetaPromotion

        defaultAttackerUnit.promotions.promotions.add(alphaPromotion.name)
        defaultAttackerUnit.promotions.promotions.add(zetaPromotion.name)
        defaultAttackerUnit.updateUniques()

        val attackModifiers = BattleDamage.getAttackModifiers(
            MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile
        )

        assertEquals(listOf("Alpha", "Zeta"), attackModifiers.keys.map { key ->
            when {
                "Alpha" in key -> "Alpha"
                "Zeta" in key -> "Zeta"
                else -> key
            }
        })
    }

    @Test
    fun `should retrieve modifiers from buldings`() {
        // given
        val building = testGame.createBuilding("[+15]% Strength <for [Military] units>")
        val attackerCity = testGame.addCity(attackerCiv, testGame.getTile(HexCoord.Zero))
        attackerCity.cityConstructions.addBuilding(building.name)

        // when
        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        // then
        assertEquals(1, attackModifiers.size)
        assertEquals(15, attackModifiers.sumValues())
    }

    @Test
    fun `should retrieve modifiers from national abilities`() {
        // given
        val civ = testGame.addCiv("[+10]% Strength <for [All] units> <during a Golden Age>") // i.e., Persia national ability
        civ.goldenAges.enterGoldenAge(2)
        val attackerTile = testGame.getTile(HexCoord.Zero)
        val attackerUnit = testGame.addUnit("Warrior", civ, attackerTile)

        // when
        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(attackerUnit), MapUnitCombatant(defaultDefenderUnit), attackerTile)

        // then
        assertEquals(1, attackModifiers.size)
        assertEquals(10, attackModifiers.sumValues())
    }

    @Test
    fun `should retrieve modifiers from lack of strategic resource`() {
        // given
        defaultAttackerTile.militaryUnit = null // otherwise we'll also get a flanking bonus
        val attackerTile = testGame.getTile(HexCoord.Zero)
        val attackerUnit = testGame.addUnit("Horseman", attackerCiv, attackerTile)

        // when
        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(attackerUnit), MapUnitCombatant(defaultDefenderUnit), attackerTile)

        // then
        assertEquals(1, attackModifiers.size)
        assertEquals(BattleConstants.MISSING_RESOURCES_MALUS, attackModifiers.sumValues())
    }

    private fun makeWar() {
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
    }

    @Test
    fun `should retrieve attacking flank bonus modifiers`() {
        // given
        makeWar()
        val flankingAttackerTile = testGame.getTile(HexCoord.Zero)
        testGame.addUnit("Warrior", attackerCiv, flankingAttackerTile)

        // when
        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        // then
        assertEquals(1, attackModifiers.size)
        assertEquals(BattleConstants.BASE_FLANKING_BONUS.toInt(), attackModifiers.sumValues())
    }

    @Test
    fun `should retrieve defence fortification modifiers`() {
        // given
        defaultDefenderUnit.currentMovement = 2f // base warrior max movement points
        defaultDefenderUnit.fortify()
        TurnManager(defenderCiv).endTurn()

        // when
        val defenceModifiers = BattleDamage.getDefenceModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        // then
        assertEquals(1, defenceModifiers.size)
        assertEquals(BattleConstants.FORTIFICATION_BONUS, defenceModifiers.sumValues())
    }

    @Test
    fun `should retrieve defence terrain modifiers`() {
        // given
        testGame.setTileFeatures(defaultDefenderTile.position, Constants.hill)

        // when
        val defenceModifiers = BattleDamage.getDefenceModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        // then
        assertEquals(1, defenceModifiers.size)
        assertEquals(25, defenceModifiers.sumValues())
    }

    @Test
    fun `should not retrieve defence terrain modifiers when unit doesn't get them`() {
        // given
        val defenderTile = testGame.getTile(HexCoord.Zero)
        testGame.setTileFeatures(defenderTile.position, Constants.hill)
        val defenderUnit = testGame.addDefaultMeleeUnitWithUniques(defenderCiv, defenderTile, "No defensive terrain bonus")

        // when
        val defenceModifiers = BattleDamage.getDefenceModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defenderUnit), defaultAttackerTile)

        // then
        assertTrue(defenceModifiers.isEmpty())
        assertEquals(0, defenceModifiers.sumValues())
    }

    /** CvUnit::GetStrategicResourceCombatPenalty: floor(missing / used x -50) per missing resource, at most -50 */
    @Test
    fun `the penalty for missing strategic resources grows with the shortage and stops at minus 50`() {
        defaultAttackerTile.militaryUnit = null
        testGame.addCity(attackerCiv, testGame.getTile(-3, 0)).cityConstructions.addBuilding(testGame.createBuilding("Provides [1] [Horses]"))
        val horsemen = listOf(testGame.getTile(0, 0), testGame.getTile(1, 1), testGame.getTile(2, 0)).map { testGame.addUnit("Horseman", attackerCiv, it) }
        fun penalty(): Int {
            attackerCiv.cache.updateCivResources()
            val tile = horsemen[0].getTile()
            return BattleDamage.getAttackModifiers(MapUnitCombatant(horsemen[0]), MapUnitCombatant(defaultDefenderUnit), tile)["Missing resource"]
        }
        // 3 horsemen use 3 Horses, 1 is supplied: missing 2 of 3 used = floor(-33.3) = -34
        assertEquals(-34, penalty())
        horsemen[2].destroy()
        // 2 used, 1 supplied: -25 (half), and no more penalty than that for a unit that is not short of its own
        assertEquals(-25, penalty())
        horsemen[1].destroy()
        assertEquals("1 used, 1 supplied", 0, penalty())
    /** CvCity::rangeCombatDamage and GetAirStrikeDefenseDamage: at least MIN_CITY_STRIKE_DAMAGE (10), where other attacks deal at least 1 */
    @Test
    fun `a city deals at least 10 damage however strong its target is`() {
        val city = testGame.addCity(attackerCiv, testGame.getTile(-3, 0))
        val megaWarrior = testGame.createBaseUnit("Sword").apply { strength = 1_000_000 }
        val target = testGame.addUnit(megaWarrior.name, defenderCiv, testGame.getTile(2, 0))

        val damage = BattleDamage.calculateDamageToDefender(CityCombatant(city), MapUnitCombatant(target), target.getTile(), 0f)

        assertEquals(10, damage)
    }

    @Test
    fun `a unit that is not a city still deals only 1 damage against a much stronger target`() {
        val megaWarrior = testGame.createBaseUnit("Sword").apply { strength = 1_000_000 }
        val target = testGame.addUnit(megaWarrior.name, defenderCiv, testGame.getTile(-2, 0))

        val damage = BattleDamage.calculateDamageToDefender(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(target), target.getTile(), 0f)

        assertEquals(1, damage)
    /** CvUnit::GetGenericMaxStrengthModifier: the defender is flanked as well, by the attacker's side's units next to it */
    @Test
    fun `a defender gets the flanking bonus from its own units next to the melee attacker`() {
        makeWar()
        val behindAttacker = defaultAttackerTile.neighbors.first { it != defaultDefenderTile && it.militaryUnit == null }
        testGame.addUnit("Warrior", defenderCiv, behindAttacker)

        val defenceModifiers = BattleDamage.getDefenceModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        assertEquals(BattleConstants.BASE_FLANKING_BONUS.toInt(), defenceModifiers["Flanking"])
    }

    @Test
    fun `a ranged attack gives the defender no flanking bonus`() {
        makeWar()
        val archer = testGame.addUnit("Archer", attackerCiv, defaultAttackerTile.neighbors.first { it != defaultDefenderTile && it.militaryUnit == null && it.getUnits().none() })
        val behindArcher = archer.getTile().neighbors.first { it != defaultDefenderTile && it.militaryUnit == null && it.getUnits().none() }
        testGame.addUnit("Warrior", defenderCiv, behindArcher)

        val defenceModifiers = BattleDamage.getDefenceModifiers(MapUnitCombatant(archer), MapUnitCombatant(defaultDefenderUnit), archer.getTile())

        assertEquals(0, defenceModifiers["Flanking"])
    }

    @Test
    fun `a ranged unit next to the defender counts for the attacker's flanking bonus`() {
        makeWar()
        val archerTile = defaultDefenderTile.neighbors.first { it != defaultAttackerTile && it.militaryUnit == null }
        testGame.addUnit("Archer", attackerCiv, archerTile)

        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        assertEquals(BattleConstants.BASE_FLANKING_BONUS.toInt(), attackModifiers["Flanking"])
    }

    @Test
    fun `a unit of a civilization not at war with the defender does not flank`() {
        makeWar()
        val thirdCiv = testGame.addCiv()
        testGame.addUnit("Warrior", thirdCiv, defaultDefenderTile.neighbors.first { it != defaultAttackerTile && it.militaryUnit == null })

        val attackModifiers = BattleDamage.getAttackModifiers(MapUnitCombatant(defaultAttackerUnit), MapUnitCombatant(defaultDefenderUnit), defaultAttackerTile)

        assertEquals(0, attackModifiers["Flanking"])
    }
}
