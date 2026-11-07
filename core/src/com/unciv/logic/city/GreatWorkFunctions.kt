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

    /** A stable work identity rather than its name or slot: generic artifacts share names, and slots can change contents. */
    @Readonly
    fun tradeId(slot: GreatWorkSlot): String = slot.getWork()?.id ?: ""

    @Readonly
    fun findTradeSlot(civ: Civilization, id: String): GreatWorkSlot? =
        if (id.isEmpty()) null else civ.cities.asSequence().flatMap { it.greatWorks.getSlots().asSequence() }
            .firstOrNull { it.getWork()?.id == id }

    @Readonly
    fun canExchange(first: GreatWorkSlot, second: GreatWorkSlot): Boolean {
        if (first.city.civ == second.city.civ || !first.city.civ.isMajorCiv || !second.city.civ.isMajorCiv) return false
        val firstWork = first.getWork() ?: return false
        val secondWork = second.getWork() ?: return false
        val definitions = first.city.civ.gameInfo.ruleset.greatWorks
        val firstType = definitions[firstWork.work]?.type ?: return false
        return firstType == definitions[secondWork.work]?.type && fits(firstWork, second) && fits(secondWork, first)
    }

    /** An atomic, one-for-one foreign exchange. Both source slots must still hold the offered works. */
    fun exchange(first: GreatWorkSlot, second: GreatWorkSlot): Boolean {
        if (!canExchange(first, second)) return false
        val firstWork = first.getWork()!!
        val secondWork = second.getWork()!!
        first.city.greatWorks.place(secondWork, first)
        second.city.greatWorks.place(firstWork, second)
        first.city.cityStats.update()
        second.city.cityStats.update()
        return true
    }

    /** Net change in a civilization's theming if these two slots exchanged works. */
    @Readonly
    fun themingGain(first: GreatWorkSlot, second: GreatWorkSlot): Int {
        if (first.building.themingBonuses.isEmpty() && second.building.themingBonuses.isEmpty()) return 0
        val firstWork = first.getWork() ?: return 0
        val secondWork = second.getWork()
        if (!fits(firstWork, second) || (secondWork != null && !fits(secondWork, first))) return 0
        val firstWorks = first.city.greatWorks
        val secondWorks = second.city.greatWorks
        val firstBefore = firstWorks.getThemingBonus(first.building)
        val secondBefore = if (first.city === second.city && first.building === second.building) 0
            else secondWorks.getThemingBonus(second.building)
        fun themedAfter(slot: GreatWorkSlot): Int {
            val works = slot.city.greatWorks
            val slots = works.getSlots(slot.building)
            return works.getThemingBonus(slot.building, slots.map {
                when (it) {
                    first -> secondWork ?: return 0
                    second -> firstWork
                    else -> it.getWork() ?: return 0
                }
            })
        }
        return themedAfter(first) + (if (first.city === second.city && first.building === second.building) 0 else themedAfter(second)) -
            firstBefore - secondBefore
    }

    /** Repeatedly make strictly beneficial rearrangements, retaining creator and era on each work. */
    fun optimizeTheming(civ: Civilization) {
        val slots = civ.cities.flatMap { it.greatWorks.getSlots() }
        if (slots.none { it.building.themingBonuses.isNotEmpty() }
            || slots.count { it.getWork() != null } < 2) return
        while (true) {
            var bestGain = 0
            var bestFirst: GreatWorkSlot? = null
            var bestSecond: GreatWorkSlot? = null
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val first = if (slots[i].getWork() != null) slots[i] else slots[j]
                val second = if (first === slots[i]) slots[j] else slots[i]
                val gain = themingGain(first, second)
                if (gain > bestGain) {
                    bestGain = gain
                    bestFirst = first
                    bestSecond = second
                }
            }
            if (bestFirst == null || bestSecond == null || !move(bestFirst, bestSecond)) return
        }
    }

    /** Whether a work fits in the slot, that is whether the types match */
    @Readonly
    fun fits(work: GreatWorkInstance, slot: GreatWorkSlot): Boolean {
        val definition = slot.city.civ.gameInfo.ruleset.greatWorks[work.work] ?: return false
        return definition.getSlotType() == slot.getType()
    }

    /** Makes a new work of [definition], attributed to [creatorCiv] and the [era], by default of [civ] and the era it is in now, and puts it in [slot] */
    fun createWork(civ: Civilization, definition: GreatWork, slot: GreatWorkSlot, creatorCiv: String = civ.civName, era: String = civ.getEra().name): GreatWorkInstance {
        val instance = GreatWorkInstance(definition.name, creatorCiv, era, civ.gameInfo.turns)
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
