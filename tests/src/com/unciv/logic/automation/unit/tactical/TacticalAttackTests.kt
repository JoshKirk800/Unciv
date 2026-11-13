package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The tactical targets and attacks (CvTacticalAI::FindTacticalTargets, PlotCaptureCityMoves, PlotDamageCityMoves, PlotDestroyUnitMoves,
 *  ExecuteAttack, safe and protected bombards, flank attacks) with Brave New World data. Expected damage is read from the code's own
 *  [TacticalAttacks.computeTotalExpectedDamage] (BattleDamage at the mean of the randomness), the thresholds of the original are then
 *  tested around it.
 */
@RunWith(BaseTestRunner::class)
class TacticalAttackTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        // supplied, so that the enemy's Swordsmen and Horsemen are not weakened by missing resources (CvUnit::GetStrategicResourceCombatPenalty)
        enemy = game.addCiv("Provides [100] [Iron]", "Provides [100] [Horses]")
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList().ifEmpty { civ.units.getCivUnits().toList() }, TacticalTargets.find(civ, map, danger))
    }

    private fun target(ctx: TacticalContext, tile: Tile) = ctx.targets.all.first { it.tile == tile }

    private fun targetType(unit: MapUnit): TacticalTargetType {
        val ctx = context()
        return ctx.targets.all.first { it.unit == unit }.type
    }

    private fun neighbors(tile: Tile, count: Int) = tile.neighbors.take(count).toList()

    private fun expected(ctx: TacticalContext, target: TacticalTarget, turns: Int = 1, includeBlocked: Boolean = true): Int =
        TacticalAttacks.computeTotalExpectedDamage(target, TacticalAttacks.findUnits(ctx, target.tile, turns, includeBlocked = includeBlocked))

    // region Targets

    @Test
    fun `units that can take one of our cities together are high priority targets, the ranged ones medium, units that can only hurt it medium, the rest low`() {
        val city = game.addCity(civ, tile(0))
        city.health = 30
        val horsemen = listOf(2, 3).map { game.addUnit("Horseman", enemy, tile(it)) }
        val archer = game.addUnit("Archer", enemy, tile(-2))
        val far = game.addUnit("Warrior", enemy, tile(12))
        for (horseman in horsemen) assertEquals(TacticalTargetType.HighPriorityUnit, targetType(horseman))
        assertEquals(TacticalTargetType.MediumPriorityUnit, targetType(archer))
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(far))

        city.health = 200 // now they hurt it but cannot take it
        for (horseman in horsemen) assertEquals(TacticalTargetType.MediumPriorityUnit, targetType(horseman))
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(far))
    }

    @Test
    fun `a unit on a citadel in its own land is a high priority target`() {
        val enemyCity = game.addCity(enemy, tile(8))
        tile(6).setOwningCity(enemyCity)
        val unit = game.addUnit("Warrior", enemy, tile(6))
        assertEquals(enemy, unit.getTile().getOwner())
        assertEquals(TacticalTargetType.LowPriorityUnit, targetType(unit))
        unit.getTile().setImprovement("Citadel")
        assertEquals(TacticalTargetType.HighPriorityUnit, targetType(unit))
    }

    @Test
    fun `civilians are sorted by what they are worth to us`() {
        val general = game.addUnit("Great General", enemy, tile(3))
        val settler = game.addUnit("Settler", enemy, tile(5))
        val worker = game.addUnit("Worker", enemy, tile(7))
        assertEquals(TacticalTargetType.VeryHighPriorityCivilian, targetType(general))
        assertEquals("settlers are high priority in the early game", TacticalTargetType.HighPriorityCivilian, targetType(settler))
        assertEquals("so are workers medium", TacticalTargetType.MediumPriorityCivilian, targetType(worker))

        game.gameInfo.turns = 400 // late in the game
        for (i in 1..5) game.addCity(enemy, tile(-3 * i)) // the settler's owner is no longer small
        assertEquals("a late settler of a big civ is only medium", TacticalTargetType.MediumPriorityCivilian, targetType(settler))
        assertEquals("a late worker is low", TacticalTargetType.LowPriorityCivilian, targetType(worker))
    }

    @Test
    fun `the targets of a dominance zone are those on its tiles and those within 2 tiles of its city`() {
        val city = enemyCity(0)
        val inside = game.addUnit("Warrior", enemy, tile(1)) // on the city's land
        val near = game.addUnit("Warrior", enemy, tile(2)) // unowned land, 2 from the city
        val far = game.addUnit("Warrior", enemy, tile(4)) // 4 away
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        assertEquals(TacticalTerritory.Enemy, zone.territory)
        val units = ctx.targets.forZone(zone).mapNotNull { it.unit }
        assertTrue(inside in units)
        assertTrue("2 tiles from the city is near enough", near in units)
        assertFalse(far in units)
        assertTrue("all targets for no zone", ctx.targets.forZone(null).mapNotNull { it.unit }.containsAll(listOf(inside, near, far)))
    }

    @Test
    fun `targets are sorted by damage, the most damaged unit first`() {
        val healthy = game.addUnit("Warrior", enemy, tile(5))
        val hurt = game.addUnit("Warrior", enemy, tile(7)).also { it.health = 40 }
        val units = context().targets.all.filter { it.isUnitTarget }.map { it.unit }
        assertEquals(listOf(hurt, healthy), units)
    }

    // endregion
    // region Cities

    private fun enemyCity(position: Int): City = game.addCity(enemy, tile(position))

    private fun melee(count: Int, around: Tile, name: String = "Swordsman") =
        around.neighbors.filter { it.militaryUnit == null && !it.isCityCenter() }.take(count).map { game.addUnit(name, civ, it) }.toList()

    @Test
    fun `a city is attacked to capture it when what our units do is at least half its hit points, and not below`() {
        val city = enemyCity(0)
        melee(3, city.getCenterTile(), "Swordsman")
        val ctx = context()
        val target = target(ctx, city.getCenterTile())
        val total = expected(ctx, target)
        assertTrue("the scenario has units that hurt the city", total > 0)

        city.health = 2 * total + 2 // total < health / 2
        TacticalMoves.plotCaptureCityMoves(ctx)
        assertEquals("below half, the capture move does nothing", 2 * total + 2, city.health)
        assertTrue(ctx.units.all { it.attacksThisTurn == 0 })

        city.health = 2 * total // total >= health / 2
        TacticalMoves.plotCaptureCityMoves(ctx)
        assertTrue("at half, the units attack", city.health < 2 * total || city.civ == civ)
        assertTrue(ctx.units.size < 3)
    }

    @Test
    fun `a city is damaged when our units do more than an eighth of its hit points, and not at an eighth`() {
        val city = enemyCity(0)
        melee(3, city.getCenterTile(), "Swordsman")
        val ctx = context()
        val target = target(ctx, city.getCenterTile())
        val total = expected(ctx, target)
        assertTrue(total > 0)

        city.health = 8 * total // total is not more than health / 8
        TacticalMoves.plotDamageCityMoves(ctx)
        assertEquals(8 * total, city.health)
        assertTrue(ctx.units.all { it.attacksThisTurn == 0 })

        city.health = 8 * total - 8 // total > health / 8
        val before = city.health
        TacticalMoves.plotDamageCityMoves(ctx)
        assertTrue(city.health < before)
    }

    @Test
    fun `units that would not survive attacking a city to damage it stay out, to capture it they go`() {
        val city = enemyCity(0)
        val warrior = melee(1, city.getCenterTile(), "Warrior").single()
        warrior.health = 3
        val ctx = context()
        val target = target(ctx, city.getCenterTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1, includeBlocked = true)
        TacticalAttacks.computeTotalExpectedDamage(target, strikers)
        assertTrue("it takes more than it has", strikers.single().expectedSelfDamage >= warrior.health)
        target.aux = city.health

        assertTrue(TacticalAttacks.planAttack(ctx, target, strikers, emptyList(), mustSurviveAttack = true).isEmpty())
        assertEquals(warrior, TacticalAttacks.planAttack(ctx, target, strikers, emptyList(), mustSurviveAttack = false).attacks.single().unit)
    }

    @Test
    fun `a city at minimum health is taken by a unit that would otherwise die, a healthy one is not thrown at it`() {
        val city = enemyCity(0)
        city.health = 200
        val wounded = melee(1, city.getCenterTile(), "Swordsman").single().also { it.health = 5 }
        var ctx = context()
        TacticalMoves.plotCaptureCityMoves(ctx)
        TacticalMoves.plotDamageCityMoves(ctx)
        assertFalse(wounded.isDestroyed)
        assertEquals("not attacked", 0, wounded.attacksThisTurn)

        city.health = 1
        ctx = context()
        TacticalMoves.plotCaptureCityMoves(ctx)
        assertEquals(civ, city.civ)
    }

    // endregion
    // region Units: destroy and attrition

    private fun defender(position: Int, name: String = "Swordsman"): MapUnit = game.addUnit(name, enemy, tile(position))

    @Test
    fun `a unit is destroyed only when our units together do more than its hit points`() {
        val victim = defender(0)
        melee(3, victim.getTile(), "Warrior")
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val total = expected(ctx, target, includeBlocked = false)
        assertTrue(total in 1..99)

        victim.health = total // does not exceed
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, mustBeAbleToKill = true)
        assertEquals(total, victim.health)
        assertTrue(ctx.units.all { it.attacksThisTurn == 0 })

        victim.health = total - 1
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, mustBeAbleToKill = true)
        assertTrue("it was attacked", victim.isDestroyed || victim.health < total - 1)
    }

    @Test
    fun `units that would be destroyed by attacking a low priority target stay out, but not for a high priority one`() {
        val victim = defender(0, "Swordsman")
        val warrior = melee(1, victim.getTile(), "Warrior").single()
        warrior.health = 5
        victim.health = 5
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
        TacticalAttacks.computeTotalExpectedDamage(target, strikers)
        assertTrue(strikers.single().expectedSelfDamage >= warrior.health)
        assertTrue(strikers.single().expectedTargetDamage >= victim.health)
        target.aux = victim.health

        assertTrue(TacticalAttacks.planAttack(ctx, target, strikers, emptyList(), mustSurviveAttack = true).isEmpty())
        assertEquals(1, TacticalAttacks.planAttack(ctx, target, strikers, emptyList(), mustSurviveAttack = false).attacks.size)
    }

    @Test
    fun `attrition attacks only with units that inflict at least what they take, unless together they take the target below 40 percent`() {
        val victim = defender(0, "Swordsman")
        val warriors = melee(1, victim.getTile(), "Warrior")
        victim.health = 100
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
        val total = TacticalAttacks.computeTotalExpectedDamage(target, strikers)
        assertTrue("the scenario has warriors that take more than they give", strikers.all { it.expectedSelfDamage > it.expectedTargetDamage })
        assertTrue("and does not take the swordsman to 40%", total < 40)

        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, mustBeAbleToKill = false)
        assertEquals("each would lose more than it gives: nobody attacks", 100, victim.health)
        assertTrue(warriors.all { it.attacksThisTurn == 0 })

        // The same warriors, now that together they take it to 40% of its strength or less
        victim.health = 40
        val strikers2 = TacticalAttacks.findUnits(ctx, target.tile, 1)
        assertTrue(TacticalAttacks.computeTotalExpectedDamage(target, strikers2) >= 40 * 40 / 100)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, mustBeAbleToKill = false)
        assertTrue("they go in", warriors.any { it.attacksThisTurn > 0 })
    }

    // endregion
    // region ExecuteAttack

    @Test
    fun `the attack stops adding units when their expected damage covers the target's hit points plus the overkill percent`() {
        val victim = defender(0, "Swordsman")
        victim.health = 60
        melee(6, victim.getTile(), "Swordsman")
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
        TacticalAttacks.computeTotalExpectedDamage(target, strikers)
        val perUnit = strikers.map { it.expectedTargetDamage }
        target.aux = victim.health
        val budget = victim.health * (100 + ctx.rules.define(AiDefine.AI_TACTICAL_OVERKILL_PERCENT)) / 100
        var sum = 0
        var needed = 0
        for (damage in perUnit) {
            if (sum >= budget) break
            sum += damage
            needed++
        }
        assertTrue("the scenario needs some but not all of the units", needed in 2 until perUnit.size)

        val series = TacticalAttacks.planAttack(ctx, target, strikers, emptyList())
        assertEquals(needed, series.attacks.size)
    }

    @Test
    fun `our cities shoot first and what they do counts against the budget`() {
        val home = game.addCity(civ, tile(-2)) // a bombard range of 2 reaches the target
        val victim = defender(0, "Swordsman")
        victim.health = 50
        melee(6, victim.getTile(), "Swordsman")
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
        val withoutCity = TacticalAttacks.planAttack(ctx, target.also { it.aux = victim.health }, strikers.also { TacticalAttacks.computeTotalExpectedDamage(target, it) }, emptyList())

        val cities = TacticalAttacks.findCities(ctx, victim.getTile())
        assertEquals(listOf(home), cities.map { it.city })
        TacticalAttacks.computeTotalExpectedBombardDamage(Battle.getMapCombatantOfTile(victim.getTile())!!, cities)
        assertTrue(cities.single().expectedTargetDamage > 0)
        val series = TacticalAttacks.planAttack(ctx, target, strikers, cities)
        assertEquals("the city is the first attack", home, series.attacks.first().city)
        assertTrue("the units after it are fewer", series.attacks.size - 1 < withoutCity.attacks.size)
        assertTrue(series.attacks.drop(1).all { it.unit != null })
    }

    @Test
    fun `a series stops when the target is dead and the rest of the units stay free`() {
        val victim = defender(0, "Warrior")
        victim.health = 3
        val attackers = melee(3, victim.getTile(), "Swordsman")
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
        TacticalAttacks.computeTotalExpectedDamage(target, strikers)
        target.aux = 1000 // wants all of them
        val series = TacticalAttacks.planAttack(ctx, target, strikers, emptyList())
        assertEquals(3, series.attacks.size)
        assertTrue(TacticalAttacks.launch(ctx, series))
        assertTrue(victim.isDestroyed)
        assertEquals("one attacked, the others were released", 1, attackers.count { it.attacksThisTurn > 0 })
        assertEquals(2, ctx.units.size)
    }

    // endregion
    // region Bombards

    @Test
    fun `a ranged unit already on a tile the enemy cannot attack bombards from there`() {
        val victim = defender(0, "Swordsman")
        val artillery = game.addUnit("Artillery", civ, tile(-3)) // range 3, the swordsman moves 2
        val ctx = context()
        val map = ctx.map
        assertTrue("the enemy reaches its neighbors", map.isSubjectToAttack(tile(-1)) && map.isSubjectToAttack(tile(-2)))
        assertFalse("but not what is 3 tiles from it", map.isSubjectToAttack(tile(-3)))

        assertTrue(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals(tile(-3), artillery.getTile())
        assertEquals(1, artillery.attacksThisTurn)
        assertTrue(victim.health < 100)
        assertTrue(artillery in ctx.cache.processedUnits)
    }

    @Test
    fun `a ranged unit moves to a tile the enemy cannot attack to bombard`() {
        // A ship in a small bay: the land around it is out of its reach
        val lake = tile(0).getTilesInDistance(1).toList()
        for (tile in lake) game.setTileTerrain(tile.position, "Coast")
        val victim = game.addUnit("Trireme", enemy, tile(0))
        val archer = game.addUnit("Archer", civ, tile(-3))
        val ctx = context(archer)
        val shore = victim.getTile().getTilesAtDistance(2).toList()
        assertTrue(shore.none { ctx.map.isSubjectToAttack(it) })

        assertTrue(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals("it moved to the shore", 2, archer.getTile().aerialDistanceTo(victim.getTile()))
        assertEquals(1, archer.attacksThisTurn)
        assertTrue(victim.health < 100)
    }

    @Test
    fun `a ranged unit that cannot get out of the enemy's reach does not make a safe bombard`() {
        val victim = defender(0, "Swordsman")
        val archer = game.addUnit("Archer", civ, tile(-2)) // range 2: every tile it shoots from can be attacked
        val ctx = context()
        assertTrue(ctx.map.isSubjectToAttack(archer.getTile()))
        assertFalse(TacticalBombards.safeBombardTarget(ctx, target(ctx, victim.getTile())))
        assertEquals(0, archer.attacksThisTurn)
        assertEquals(tile(-2), archer.getTile())
    }

    @Test
    fun `a ranged unit is used on a tile with a defender but not on one with only a civilian (IsExpectedToDamageWithRangedAttack)`() {
        val archer = game.addUnit("Archer", civ, tile(-1))
        game.addUnit("Worker", enemy, tile(0))
        game.addUnit("Swordsman", enemy, tile(1))
        val ctx = context()
        assertEquals(listOf(archer), TacticalAttacks.findUnits(ctx, tile(1), 1).map { it.unit })
        assertTrue("nobody to damage on the worker's tile", TacticalAttacks.findUnits(ctx, tile(0), 1).none { it.unit == archer })
    }

    /** A tile 2 from the target that two of the tiles next to the target touch, and the tile behind it for the archer */
    private fun coveredCell(target: Tile): Pair<Tile, Tile> {
        val cell = target.getTilesAtDistance(2).first { c -> c.neighbors.count { it.aerialDistanceTo(target) == 1 } == 2 }
        val behind = cell.neighbors.first { it.aerialDistanceTo(target) == 3 }
        return cell to behind
    }

    /** Tiles 2 from the target, next to the tiles that are between it and the covered cell: melee units there can take those this turn */
    private fun spearTiles(cell: Tile, target: Tile) =
        target.getTilesAtDistance(2).filter { it != cell && it.militaryUnit == null }.sortedBy { it.aerialDistanceTo(cell) }.toList()

    @Test
    fun `a protected bombard needs enough melee units to take the tiles the enemy would come through`() {
        val victim = defender(0, "Swordsman")
        val (cell, behind) = coveredCell(victim.getTile())
        val archer = game.addUnit("Archer", civ, behind)
        val ctx = context()
        val needed = cell.neighbors.count { !it.isWater && ctx.map.enemyCanMovePast(it) }
        assertEquals("the enemy comes through the two tiles between it and the cell", 2, needed)

        assertNull("no melee unit to cover", TacticalBombards.canCoverFromEnemy(ctx, cell, archer))
        val spearmen = (0..2).map { game.addUnit("Spearman", civ, spearTiles(cell, victim.getTile())[it]) }
        val ctx2 = context()
        val cover = TacticalBombards.canCoverFromEnemy(ctx2, cell, archer)!!
        assertEquals(needed, cover.numRequired)
        assertEquals("each covered tile gets another unit", needed, cover.blocks.map { it.unit }.toSet().size)
        assertEquals(needed, cover.blocks.map { it.tile }.toSet().size)
        assertTrue(cover.blocks.all { it.unit in spearmen })
    }

    @Test
    fun `a protected bombard covers the ranged unit with melee units and shoots`() {
        val victim = defender(0, "Swordsman")
        val (cell, behind) = coveredCell(victim.getTile())
        val archer = game.addUnit("Archer", civ, behind)
        val spearmen = (0..2).map { game.addUnit("Spearman", civ, spearTiles(cell, victim.getTile())[it]) }
        val ctx = context(archer, *spearmen.toTypedArray())
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(victim.getTile(), ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        assertTrue(TacticalBombards.executeOneProtectedBombard(ctx, target(ctx, victim.getTile())))
        assertEquals(1, archer.attacksThisTurn)
        assertEquals("it shot from a tile 2 from the target", 2, archer.getTile().aerialDistanceTo(victim.getTile()))
        assertTrue(victim.health < 100)
        val open = archer.getTile().neighbors.filter { !it.isWater && ctx.map.enemyCanMovePast(it) }.toList()
        assertTrue(open.isNotEmpty())
        assertTrue("every tile the enemy could come through is held by a unit that was sent there", open.all { tile ->
            tile.militaryUnit in spearmen && tile.militaryUnit in ctx.cache.processedUnits
        })
    }

    @Test
    fun `a mountain between hides the target from one side only, also when asked again`() {
        game.setTileTerrain(tile(1).position, Constants.mountain)
        val ctx = context()
        assertFalse(ctx.hasLineOfSight(tile(0), tile(2)))
        assertTrue("as far away on the other side, nothing between", ctx.hasLineOfSight(tile(4), tile(2)))
        assertFalse(ctx.hasLineOfSight(tile(0), tile(2)))
    }

    @Test
    fun `cover is assigned so that no unit is used twice even if the nearest unit fits two tiles`() {
        val first = tile(0)
        val second = tile(1)
        val a = game.addUnit("Spearman", civ, tile(5))
        val b = game.addUnit("Spearman", civ, tile(6))
        val choices = linkedMapOf(
            first to listOf(Candidate(a, 1, 1), Candidate(b, 1, 2)),
            second to listOf(Candidate(a, 1, 1)),
        )
        val blocks = TacticalBombards.assign(choices, canLeaveOpenings = false)!!
        assertEquals(setOf(a, b), blocks.map { it.unit }.toSet())
        assertEquals(b, blocks.first { it.tile == first }.unit)
        assertEquals(a, blocks.first { it.tile == second }.unit)
        assertNull("one tile has nobody left", TacticalBombards.assign(linkedMapOf(first to listOf(Candidate(a, 1, 1)), second to listOf(Candidate(a, 1, 1))), false))
    }

    // endregion
    // region Flanks

    @Test
    fun `flank tiles are filled when one unit can fill three of them or two units can attack at once`() {
        val victim = defender(0, "Swordsman")
        val units = victim.getTile().getTilesAtDistance(2).take(4).map { game.addUnit("Swordsman", civ, it) }.toList()
        val ctx = context()
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetFlankBonusCells(victim.getTile())
        assertEquals("every free tile around a land unit gives the bonus", 6, victim.getTile().neighbors.count { ctx.map.helpsProvideFlankBonus(it) })

        TacticalBombards.executeFlankAttack(ctx, target(ctx, victim.getTile()))
        val around = units.count { it.getTile().aerialDistanceTo(victim.getTile()) == 1 || it.attacksThisTurn > 0 }
        assertTrue("at least two units went in", around >= 2)
        assertTrue("and attacked", units.any { it.attacksThisTurn > 0 })
    }

    @Test
    fun `a single melee unit is not enough for a flank attack`() {
        val victim = defender(0, "Swordsman")
        val unit = game.addUnit("Swordsman", civ, tile(-3))
        val ctx = context()
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetFlankBonusCells(victim.getTile())
        TacticalBombards.executeFlankAttack(ctx, target(ctx, victim.getTile()))
        assertEquals(tile(-3), unit.getTile())
        assertEquals(0, unit.attacksThisTurn)
    }

    // endregion
    // region Other moves

    @Test
    fun `an undefended settler is captured by the unit that can reach it`() {
        val settler = game.addUnit("Settler", enemy, tile(3))
        val unit = game.addUnit("Horseman", civ, tile(0))
        val ctx = context()
        TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.HighPriorityCivilian)
        assertEquals(tile(3), unit.getTile())
        assertEquals(civ, tile(3).civilianUnit?.civ)
        assertTrue(unit in ctx.cache.processedUnits)
        assertNotEquals(enemy, settler.civ)
    }

    @Test
    fun `a unit in range pillages an enemy improvement and one that is hurt is preferred`() {
        val city = game.addCity(enemy, tile(6))
        val farm = tile(5)
        farm.setOwningCity(city)
        farm.setImprovement("Farm")
        // not next to the city: its zone of control would use up their movement
        val beside = farm.neighbors.filter { it.aerialDistanceTo(city.getCenterTile()) >= 2 }.toList()
        val healthy = game.addUnit("Warrior", civ, beside[0])
        val hurt = game.addUnit("Warrior", civ, beside[1]).also { it.health = 40 }
        val ctx = context()
        assertEquals(TacticalTargetType.Improvement, target(ctx, farm).type)
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
        assertTrue(farm.improvementIsPillaged)
        assertEquals(farm, hurt.getTile())
        assertTrue(hurt in ctx.cache.processedUnits)
        assertFalse(healthy in ctx.cache.processedUnits)
    }

    @Test
    fun `garrisons go to the most threatened city, ranged units first, and a city is garrisoned once a turn`() {
        val city = game.addCity(civ, tile(0))
        val warrior = game.addUnit("Warrior", civ, tile(2))
        val archer = game.addUnit("Archer", civ, tile(1))
        val second = game.addUnit("Warrior", civ, tile(-2))
        val ctx = context()
        TacticalMoves.plotGarrisonMoves(ctx, 1)
        assertEquals("the ranged unit goes in", city.getCenterTile(), archer.getTile())
        assertEquals(civ.gameInfo.turns, civ.ai.tactical.lastTurnGarrisonAssigned(city))
        assertNotEquals(city.getCenterTile(), warrior.getTile())
        assertNotEquals(city.getCenterTile(), second.getTile())
    }

    @Test
    fun `units with a strength bonus from their promotions are not garrisoned, a unit already in the city fortifies`() {
        val city = game.addCity(civ, tile(0))
        val veteran = game.addUnit("Spearman", civ, tile(2))
        val plain = game.addUnit("Spearman", civ, tile(-2))
        veteran.promotions.addPromotion(game.createUnitPromotion("[+25]% Strength").name, true)
        val ctx = context()
        val (high, regular) = TacticalMoves.findUnitsForThisMove(ctx, TacticalMoves.MoveKind.Garrison, city.getCenterTile(), 1)
        assertTrue(high.isEmpty())
        assertEquals("its bonus would be wasted in a city", listOf(plain), regular.map { it.unit })

        val resident = game.addUnit("Spearman", civ, city.getCenterTile())
        val ctx2 = context()
        TacticalMoves.plotGarrisonMoves(ctx2, 0)
        assertTrue(resident.isFortified())
        assertTrue(resident in ctx2.cache.processedUnits)
    }

    @Test
    fun `free units go to the nearest enemy target within ten tiles and not beyond`() {
        val near = defender(8, "Warrior")
        val unit = game.addUnit("Swordsman", civ, tile(0))
        val farUnit = game.addUnit("Swordsman", civ, tile(-12)) // 20 tiles from the enemy
        val ctx = context()
        TacticalMoves.plotRepositionMoves(ctx)
        assertTrue(unit.getTile().aerialDistanceTo(near.getTile()) < 8)
        assertEquals(tile(-12), farUnit.getTile())
        assertTrue(unit in ctx.cache.processedUnits)
    }

    // endregion
    // region Aircraft

    /** Our city at -6 holds the aircraft: a Bomber has range 10, so the enemy at 4 is in range and the one at 5 is not */
    private fun airBase() = game.addCity(civ, tile(-6)).getCenterTile()

    @Test
    fun `an aircraft with more than half its health strikes a defender in its range, and nothing else`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val near = defender(4)
        val far = defender(5)
        val worker = game.addUnit("Worker", enemy, tile(3))
        val nuke = game.addUnit("Atomic Bomb", civ, base)
        var ctx = context()
        assertEquals(listOf(bomber), TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })
        assertTrue("out of range", TacticalAttacks.findUnits(ctx, far.getTile(), 1).isEmpty())
        assertTrue("no defender to damage", TacticalAttacks.findUnits(ctx, worker.getTile(), 1).isEmpty())
        assertFalse("nuclear weapons have no attack of their own", nuke in TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })

        bomber.health = 51
        ctx = context()
        assertEquals("more than half is enough", listOf(bomber), TacticalAttacks.findUnits(ctx, near.getTile(), 1).map { it.unit })
        bomber.health = 50
        ctx = context()
        assertTrue("half is not", TacticalAttacks.findUnits(ctx, near.getTile(), 1).isEmpty())
    }

    @Test
    fun `aircraft are not asked to move onto a tile, pillage or take part when ranged units are left out`() {
        val base = airBase()
        game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        val ctx = context()
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, noRanged = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, willPillage = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, victim.getTile(), 1, targetUndefended = true).isEmpty())
        assertTrue(TacticalAttacks.findUnits(ctx, tile(2), 1, mustMoveThrough = true).isEmpty())
    }

    @Test
    fun `an aircraft is expected to take no damage from its strike`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val striker = Striker(bomber, 100, 100, base)
        val defenderCombatant = Battle.getMapCombatantOfTile(victim.getTile())!!
        assertTrue("Unciv's air strike would cost it hit points",
            BattleDamage.calculateDamageToAttacker(MapUnitCombatant(bomber), defenderCombatant, base, 0.5f) > 0)
        TacticalAttacks.computeTotalExpectedDamage(target, listOf(striker))
        assertTrue(striker.expectedTargetDamage > 0)
        assertEquals(0, striker.expectedSelfDamage)
    }

    @Test
    fun `an aircraft destroys a unit in its range as part of the tactical turn and is done for the turn`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val victim = defender(4)
        victim.health = 10
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val cache = AutomationCache()
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
        assertTrue(victim.isDestroyed)
        assertEquals(1, bomber.attacksThisTurn)
        assertTrue(bomber in cache.processedUnits)
        assertEquals(base, bomber.getTile())
    }

    @Test
    fun `an aircraft is no candidate to be moved to a bombard tile, it has no combat strength of its own`() {
        val base = airBase()
        val bomber = game.addUnit("Bomber", civ, base)
        val ctx = context()
        assertTrue(TacticalBombards.findClosestUnits(ctx, base, 1, mustBeRanged = true, rangeRequired = 2).none { it.unit == bomber })
    }

    // endregion
    // region The tactical turn

    @Test
    fun `the tactical turn takes an almost defeated city with the units next to it and does not touch the units that were not needed`() {
        val city = enemyCity(0)
        city.health = 2
        val attackers = melee(4, city.getCenterTile(), "Swordsman")
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val cache = AutomationCache()
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
        assertEquals(civ, city.civ)
        assertTrue("the units that attacked are done", attackers.any { it.attacksThisTurn > 0 && it in cache.processedUnits })
    }

    // endregion

    @Test
    fun `expected damage is the damage at the mean of the randomness`() {
        val victim = defender(0, "Swordsman")
        val attacker = melee(1, victim.getTile(), "Swordsman").single()
        val ctx = context()
        val target = target(ctx, victim.getTile())
        val striker = TacticalAttacks.findUnits(ctx, target.tile, 1).single()
        TacticalAttacks.computeTotalExpectedDamage(target, listOf(striker))
        val defenderCombatant = Battle.getMapCombatantOfTile(victim.getTile())!!
        assertEquals(BattleDamage.calculateDamageToDefender(MapUnitCombatant(attacker), defenderCombatant, striker.attackFrom!!, 0.5f), striker.expectedTargetDamage)
        assertEquals(BattleDamage.calculateDamageToAttacker(MapUnitCombatant(attacker), defenderCombatant, striker.attackFrom!!, 0.5f), striker.expectedSelfDamage)
    }
}
