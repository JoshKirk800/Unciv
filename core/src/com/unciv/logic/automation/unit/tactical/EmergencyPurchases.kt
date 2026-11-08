package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Building

/**
 *  CvTacticalAI::PlotEmergencyPurchases: in the zone around one of our cities, on our own land, where enemy units are and we do not
 *  dominate, buy defenses with gold (or faith). Not for city-states. Unless it is our capital, a city the enemy can take next turn is
 *  given up: the money is saved for elsewhere (a high priority target - [CityAttackers] - in the zone, or within 2 tiles of the city).
 *
 *  Then the city's defensive buildings ([UseGoldAutomation.buyEmergencyBuilding]), and, with fewer than two defenders there, a city bombard
 *  (else ranged) unit if it has no defender or a ship for one, a ship (assault) if it is coastal and has none at sea, and an
 *  attack air unit (else defense air) always.
 */
object EmergencyPurchases {

    /** What one call bought */
    class Result(val buildings: List<Building>, val units: List<MapUnit>)

    /**
     *  @param highPriorityUnits the enemy units that threaten our cities ([CityAttackers.highPriorityUnits]), asked for only if needed
     */
    fun plot(civ: Civilization, zone: DominanceZone, map: TacticalMap, highPriorityUnits: () -> Set<MapUnit>): Result? {
        if (civ.isCityState) return null
        val city = zone.closestCity ?: return null
        if (city.civ != civ || zone.territory != TacticalTerritory.Friendly || zone.enemyUnitCount <= 0) return null

        // Make sure the city isn't about to fall: abandon hope for it and save the money
        if (!city.isCapital()) {
            val cityTile = city.getCenterTile()
            for (unit in highPriorityUnits()) {
                val tile = unit.getTile()
                if (map.zoneOf(tile)?.id == zone.id || tile.aerialDistanceTo(cityTile) <= TARGETS_NEAR_CITY) return null
            }
        }

        val buildings = UseGoldAutomation.buyEmergencyBuilding(civ, city)
        val units = ArrayList<MapUnit>()
        val tile = city.getCenterTile()
        // Two defenders: assume the city has land and sea ones; one military unit per tile in Unciv, so this is always the case below
        val defender = tile.militaryUnit?.takeIf { it.civ == civ }
        var buyNaval = false
        var buyLand = false
        if (defender == null) {
            buyLand = true
            buyNaval = city.isCoastal()
        } else if (defender.baseUnit.isLandUnit) buyNaval = city.isCoastal()
        else buyLand = true

        if (buyLand) {
            val unit = UseGoldAutomation.buyEmergencyUnit(civ, UnitAiType.CITY_BOMBARD, city)
                ?: UseGoldAutomation.buyEmergencyUnit(civ, UnitAiType.RANGED, city)
            if (unit != null) units.add(unit)
        }
        if (buyNaval) UseGoldAutomation.buyEmergencyUnit(civ, UnitAiType.ASSAULT_SEA, city)?.let { units.add(it) }
        // Always can try to buy air units
        val air = UseGoldAutomation.buyEmergencyUnit(civ, UnitAiType.ATTACK_AIR, city)
            ?: UseGoldAutomation.buyEmergencyUnit(civ, UnitAiType.DEFENSE_AIR, city)
        if (air != null) units.add(air)
        return Result(buildings, units)
    }

    /** ExtractTargetsForZone: targets not in the zone count if they are within 2 of the zone's city */
    private const val TARGETS_NEAR_CITY = 2
}
