package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** The effect of [UniqueType.ConvertAdjacentBarbarians] (the belief Heathen Conversion),
 *  as in CvUnit::setXY (CvUnit.cpp:13136) and CvBeliefHelpers::ConvertBarbarianUnit (CvBeliefClasses.cpp:1738). */
object BarbarianConversion {

    /** Whether [unit] belongs to a civilization whose religion converts Barbarians next to units like it */
    @Readonly
    private fun convertsBarbarians(unit: MapUnit): Boolean {
        val religion = unit.civ.religionManager.religion ?: return false
        if (!religion.isMajorReligion()) return false
        return religion.founderBeliefUniqueMap.getMatchingUniques(UniqueType.ConvertAdjacentBarbarians, unit.cache.state)
            .any { unit.matchesFilter(it.params[0]) }
    }

    /** The unit that defends a tile best, if it is a Barbarian: military before civilian */
    @Readonly
    private fun getBarbarianDefender(tile: Tile): MapUnit? =
        (tile.militaryUnit ?: tile.civilianUnit)?.takeIf { it.civ.isBarbarian }

    /** To be called when [unit] has entered its tile. A unit that converts Barbarians takes over the one on each adjacent tile;
     *  a Barbarian unit that comes next to such a unit is taken over by it (once). */
    fun convertAdjacentBarbarians(unit: MapUnit) {
        if (unit.isDestroyed) return
        if (unit.civ.isBarbarian) {
            for (neighbor in unit.currentTile.neighbors) {
                val converter = neighbor.getUnits().firstOrNull { !it.civ.isBarbarian && convertsBarbarians(it) } ?: continue
                convert(unit, converter.civ)
                return
            }
        } else if (convertsBarbarians(unit)) {
            for (neighbor in unit.currentTile.neighbors) {
                val barbarian = getBarbarianDefender(neighbor) ?: continue
                convert(barbarian, unit.civ)
            }
        }
    }

    /** CvBeliefHelpers::ConvertBarbarianUnit: [barbarian] joins [civ] with no movement left; a Barbarian camp where it stands is cleared, with no reward */
    private fun convert(barbarian: MapUnit, civ: Civilization) {
        val tile = barbarian.currentTile
        barbarian.capturedBy(civ)
        barbarian.action = null // the orders of a Barbarian are not ours (ClearMissionQueue)
        if (tile.isBarbarianEncampment()) barbarian.clearEncampment(tile, withReward = false)
        civ.addNotification("An enemy [${barbarian.name}] has joined us!", MapUnitAction(barbarian), NotificationCategory.War, barbarian.name)
    }
}
