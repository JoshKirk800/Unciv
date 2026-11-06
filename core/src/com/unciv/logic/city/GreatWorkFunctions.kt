package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.GreatWork
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkSlotType
import yairm210.purity.annotations.Readonly

/** What Great Works need from more than one city: finding places for them, moving them, creating them */
object GreatWorkFunctions {

    /** The free slot of [type] in the city of [civ] that is closest to [tile] (CvPlayerCulture::GetClosestAvailableGreatWorkSlot) */
    @Readonly
    fun getClosestFreeSlot(civ: Civilization, type: GreatWorkSlotType, tile: Tile): GreatWorkSlot? {
        var best: GreatWorkSlot? = null
        var bestDistance = Int.MAX_VALUE
        for (city in civ.cities) {
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            if (distance >= bestDistance) continue
            val slot = city.greatWorks.getFreeSlot(type) ?: continue
            best = slot
            bestDistance = distance
        }
        return best
    }

    @Readonly
    fun getNumFreeSlots(civ: Civilization, type: GreatWorkSlotType): Int = civ.cities.sumOf { it.greatWorks.getNumFreeSlots(type) }

    @Readonly
    fun getNumWorks(civ: Civilization): Int = civ.cities.sumOf { it.greatWorks.getNumWorks() }

    /** Whether a work fits in the slot, that is whether the types match */
    @Readonly
    fun fits(work: GreatWorkInstance, slot: GreatWorkSlot): Boolean {
        val definition = slot.city.civ.gameInfo.ruleset.greatWorks[work.work] ?: return false
        return definition.getSlotType() == slot.getType()
    }

    /** Makes a new work of [definition], attributed to [civ] and the era it is in now, and puts it in [slot] */
    fun createWork(civ: Civilization, definition: GreatWork, slot: GreatWorkSlot): GreatWorkInstance {
        val instance = GreatWorkInstance(definition.name, civ.civName, civ.getEra().name, civ.gameInfo.turns)
        slot.city.greatWorks.place(instance, slot)
        slot.city.cityStats.update()
        return instance
    }

    /** The work a Great Person unit creates: the one that goes with the unit's name */
    @Readonly
    fun getWorkOfUnit(unit: MapUnit): GreatWork? {
        val name = unit.instanceName ?: return null
        return unit.civ.gameInfo.ruleset.greatWorks.values.firstOrNull { it.creator == name }
    }

    /** CvUnit::canCreateGreatWork: in or next to a city of the civilization, and a free slot of the right type somewhere in it */
    @Readonly
    fun canCreateWork(unit: MapUnit): Boolean {
        val definition = getWorkOfUnit(unit) ?: return false
        val tile = unit.getTile()
        val nextToCity = tile.getCity()?.civ == unit.civ || tile.neighbors.any { it.isCityCenter() && it.getOwner() == unit.civ }
        if (!nextToCity) return false
        return getNumFreeSlots(unit.civ, definition.getSlotType()) > 0
    }

    /** CvUnit::createGreatWork. Returns where the work was put, or null if it could not be created */
    fun createWorkWithUnit(unit: MapUnit): GreatWorkSlot? {
        if (!canCreateWork(unit)) return null
        val definition = getWorkOfUnit(unit)!!
        val slot = getClosestFreeSlot(unit.civ, definition.getSlotType(), unit.getTile()) ?: return null
        createWork(unit.civ, definition, slot)
        unit.civ.addNotification("[${unit.civ.civName}] has created the Great Work [${definition.name}] in [${slot.city.name}]",
            slot.city.location, NotificationCategory.General, "StatIcons/Culture")
        return slot
    }

    /** Moves the work in [from] to [to] and a work that is in [to] to [from], as long as both fit. Works in different cities of one civilization only */
    fun move(from: GreatWorkSlot, to: GreatWorkSlot): Boolean {
        if (from == to || from.city.civ != to.city.civ) return false
        val moved = from.getWork() ?: return false
        if (!fits(moved, to)) return false
        val other = to.getWork()
        if (other != null && !fits(other, from)) return false

        from.city.greatWorks.take(from)
        to.city.greatWorks.take(to)
        to.city.greatWorks.place(moved, to)
        if (other != null) from.city.greatWorks.place(other, from)
        from.city.cityStats.update()
        if (to.city != from.city) to.city.cityStats.update()
        return true
    }
}
