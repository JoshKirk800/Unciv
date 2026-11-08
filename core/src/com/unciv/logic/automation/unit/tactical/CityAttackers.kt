package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The enemy units that threaten to take one of our cities: CvTacticalAI::IdentifyPriorityTargets and IdentifyPriorityTargetsByType, the
 *  part that marks AI_TACTICAL_TARGET_HIGH_PRIORITY_UNIT targets. Of every visible enemy unit the damage it can do to each of our cities next
 *  turn is added up; if the sum for a city is more than the city's hit points, the melee units among them are high priority (a ranged one is
 *  only medium), and so is any unit standing on a citadel in its own territory.
 *
 *  Deviations: "can reach the city next turn" is the hex distance within the unit's base moves on its landmass (a ship needs a coastal
 *  city); the fire support of a defending unit is not subtracted; the damage is [BattleDamage]'s with the mean of the randomness and the
 *  unit's own tile as the tile it attacks from.
 */
object CityAttackers {

    /** The visible units of civilizations at war with [civ] that are high priority targets this turn */
    fun highPriorityUnits(civ: Civilization): Set<MapUnit> {
        val enemies = visibleEnemyDefenders(civ)
        val high = HashSet<MapUnit>()
        if (enemies.isEmpty()) return high
        for (city in civ.cities) {
            val cityCombatant = CityCombatant(city)
            var total = 0
            val attackers = ArrayList<MapUnit>()
            for (enemy in enemies) {
                val damage = expectedDamage(enemy, cityCombatant)
                if (damage > 0) {
                    total += damage
                    attackers.add(enemy)
                }
            }
            if (total > city.health) for (attacker in attackers) if (!isRangedAttacker(attacker)) high.add(attacker)
        }
        // Units defending a citadel in their own territory are always high priority
        for (enemy in enemies) {
            val tile = enemy.getTile()
            if (tile.getOwner() == enemy.civ && tile.getUnpillagedTileImprovement()?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true)
                high.add(enemy)
        }
        return high
    }

    /** The military units of players at war with us on tiles we see that are not cities (CvPlot::getVisibleEnemyDefender) */
    private fun visibleEnemyDefenders(civ: Civilization): List<MapUnit> {
        val result = ArrayList<MapUnit>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) {
                if (!unit.isMilitary() || unit.baseUnit.isAirUnit() || unit.getTile().isCityCenter()) continue
                if (unit.isVisibleTo(civ)) result.add(unit)
            }
        }
        return result
    }

    /** IsCanAttackRanged: the unit shoots */
    private fun isRangedAttacker(unit: MapUnit) = unit.baseUnit.rangedStrength > 0

    /** What one enemy unit does to [city] next turn: shooting it if it is a ranged unit within range, else attacking it if it can get there */
    private fun expectedDamage(enemy: MapUnit, city: CityCombatant): Int {
        val cityTile = city.getTile()
        val distance = enemy.getTile().aerialDistanceTo(cityTile)
        val base = enemy.baseUnit
        if (base.rangedStrength > 0 && base.rangedStrength > base.strength) {
            if (distance > enemy.getRange()) return 0
        } else {
            val moves = base.movement.coerceAtLeast(1)
            if (distance > moves) return 0
            if (base.isLandUnit && enemy.getTile().getContinent() != cityTile.getContinent()) return 0
            if (base.isWaterUnit && !city.city.isCoastal()) return 0
        }
        return BattleDamage.calculateDamageToDefender(MapUnitCombatant(enemy), city, enemy.getTile(), EXPECTED_RANDOMNESS)
    }

    private const val EXPECTED_RANDOMNESS = 0.5f
}
