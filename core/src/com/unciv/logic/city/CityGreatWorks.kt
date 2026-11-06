package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.ThemingBonus
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stats
import yairm210.purity.annotations.Readonly

/** Where a [GreatWorkInstance] is kept: which slot of which building of which city */
class GreatWorkPlacement : IsPartOfGameInfoSerialization {
    var building = ""
    var slot = 0
    var work = GreatWorkInstance()

    constructor()
    constructor(building: String, slot: Int, work: GreatWorkInstance) {
        this.building = building
        this.slot = slot
        this.work = work
    }
}

/** A slot for a Great Work in a building of [city]. Whether it is taken is up to [CityGreatWorks.getWorkIn] */
class GreatWorkSlot(val city: City, val building: Building, val index: Int) {
    @Readonly
    fun getType(): GreatWorkSlotType = building.greatWorkSlotTypes[index]

    @Readonly
    fun getWork(): GreatWorkInstance? = city.greatWorks.getWorkIn(this)

    @Readonly
    fun isFree() = getWork() == null

    override fun equals(other: Any?) = other is GreatWorkSlot && other.city === city && other.building === building && other.index == index
    override fun hashCode() = city.id.hashCode() * 31 + building.name.hashCode() * 7 + index
}

/**
 * The Great Works kept in the buildings of a city (CvCityBuildings in the original).
 * Which buildings have slots, and of which type, is given by their [GreatWorkSlots][UniqueType.GreatWorkSlots] uniques.
 * A work is only counted while its building is in the city, see [com.unciv.logic.city.GreatWorkFunctions] for what happens when it is not.
 */
class CityGreatWorks : IsPartOfGameInfoSerialization {

    val placements = ArrayList<GreatWorkPlacement>()

    /** Works whose building is gone and that wait for a slot. Empty except while a city changes hands, see [rehome] */
    val homeless = ArrayList<GreatWorkInstance>()

    @Transient
    lateinit var city: City
        private set

    fun clone(): CityGreatWorks {
        val toReturn = CityGreatWorks()
        placements.mapTo(toReturn.placements) { GreatWorkPlacement(it.building, it.slot, it.work.clone()) }
        homeless.mapTo(toReturn.homeless) { it.clone() }
        return toReturn
    }

    fun setTransients(city: City) {
        this.city = city
    }

    //region Slots
    /** All slots of the buildings in the city, in the order of the buildings */
    @Readonly
    fun getSlots(): List<GreatWorkSlot> = city.cityConstructions.getBuiltBuildings()
        .flatMap { building -> building.greatWorkSlotTypes.indices.asSequence().map { GreatWorkSlot(city, building, it) } }
        .toList()

    @Readonly
    fun getSlots(building: Building): List<GreatWorkSlot> =
        building.greatWorkSlotTypes.indices.map { GreatWorkSlot(city, building, it) }

    @Readonly
    fun getWorkIn(slot: GreatWorkSlot): GreatWorkInstance? =
        placements.firstOrNull { it.building == slot.building.name && it.slot == slot.index }?.work

    @Readonly
    fun getFreeSlot(type: GreatWorkSlotType): GreatWorkSlot? = getSlots().firstOrNull { it.getType() == type && it.isFree() }

    @Readonly
    fun getNumFreeSlots(type: GreatWorkSlotType): Int = getSlots().count { it.getType() == type && it.isFree() }

    /** Every work in the city, in a slot of a building that exists */
    @Readonly
    fun getWorks(): List<GreatWorkInstance> = getSlots().mapNotNull { it.getWork() }

    @Readonly
    fun getNumWorks(): Int = getWorks().size

    @Readonly
    fun getNumWorks(type: GreatWorkSlotType): Int = getSlots().count { it.getType() == type && !it.isFree() }

    /** Puts [work] in [slot], replacing what was there. Callers make sure the slot has the right type, see [GreatWorkFunctions.fits] */
    fun place(work: GreatWorkInstance, slot: GreatWorkSlot) {
        placements.removeAll { it.building == slot.building.name && it.slot == slot.index }
        placements.add(GreatWorkPlacement(slot.building.name, slot.index, work))
    }

    /** Empties [slot] and returns what was in it */
    fun take(slot: GreatWorkSlot): GreatWorkInstance? {
        val placement = placements.firstOrNull { it.building == slot.building.name && it.slot == slot.index } ?: return null
        placements.remove(placement)
        return placement.work
    }

    /** Removes and returns the works of [building], which is about to be removed from the city */
    fun takeAll(building: Building): List<GreatWorkInstance> {
        val taken = placements.filter { it.building == building.name }
        placements.removeAll(taken)
        return taken.map { it.work }
    }

    @Readonly
    fun holdsWorks(building: Building) = placements.any { it.building == building.name }

    /** [building] is gone: its works go to a free slot in this city or the nearest city that has one, and are lost if there is none (CvPlayer::acquireCity does this for a captured city) */
    fun onBuildingRemoved(building: Building) {
        homeless.addAll(takeAll(building))
        // A city that is being conquered passes its works on to the conqueror, who is not known yet
        if (homeless.isNotEmpty() && !city.hasJustBeenConquered) rehome()
    }

    fun rehome() {
        if (homeless.isEmpty()) return
        val ruleset = city.civ.gameInfo.ruleset
        for (work in homeless.toList()) {
            val type = ruleset.greatWorks[work.work]?.getSlotType()
            val slot = type?.let { GreatWorkFunctions.getClosestFreeSlot(city.civ, it, city.getCenterTile()) }
            if (slot != null) {
                slot.city.greatWorks.place(work, slot)
                slot.city.cityStats.update()
            } else city.civ.addNotification("The Great Work [${work.work}] was lost as there is no room for it",
                city.location, NotificationCategory.General, "StatIcons/Culture")
        }
        homeless.clear()
    }
    //endregion

    //region Culture and theming
    /** The bonus of the first of the building's theming bonuses that fits its works, if all slots are filled */
    @Readonly
    fun getThemingBonusEntry(building: Building): ThemingBonus? {
        if (building.themingBonuses.isEmpty()) return null
        val slots = getSlots(building)
        if (slots.isEmpty()) return null
        val works = slots.map { it.getWork() ?: return null }
        val ruleset = city.civ.gameInfo.ruleset
        val types = works.map { ruleset.greatWorks[it.work]?.type ?: return null }
        return building.themingBonuses.firstOrNull { it.isSatisfiedBy(works, types, city.civ.civName) }
    }

    /** What the theming of [building] gives, with the multipliers of policies and so on (CvCityCulture::GetThemingBonus) */
    @Readonly
    fun getThemingBonus(building: Building): Int {
        val entry = getThemingBonusEntry(building) ?: return 0
        var bonus = entry.bonus
        for (unique in city.getMatchingUniques(UniqueType.ThemingBonusPercent)) {
            if (!city.matchesFilter(unique.params[1])) continue
            bonus = bonus * (100 + unique.params[0].toInt()) / 100
        }
        return bonus
    }

    @Readonly
    fun getTotalThemingBonus(): Int = city.cityConstructions.getBuiltBuildings().sumOf { getThemingBonus(it) }

    /** What each work brings, in the stats of the city, without theming */
    @Readonly
    fun getStatsPerWork(): Stats {
        val stats = Stats(culture = city.civ.gameInfo.ruleset.modOptions.constants.greatWorkBaseCulture.toFloat())
        for (unique in city.civ.getMatchingUniques(UniqueType.StatsFromGreatWorks, city.state))
            stats.add(unique.stats)
        return stats
    }

    /** The stats the city gets from its works and their theming (CvCityBuildings::GetCultureFromGreatWorks) */
    fun getStats(): Stats {
        val numWorks = getNumWorks()
        if (numWorks == 0) return Stats()
        return getStatsPerWork() * numWorks + Stats(culture = getTotalThemingBonus().toFloat())
    }

    /** The types of the works in the city, for counting by kind */
    @Readonly
    fun getWorkTypes(): List<GreatWorkType> {
        val ruleset = city.civ.gameInfo.ruleset
        return getWorks().mapNotNull { ruleset.greatWorks[it.work]?.type }
    }
    //endregion
}
