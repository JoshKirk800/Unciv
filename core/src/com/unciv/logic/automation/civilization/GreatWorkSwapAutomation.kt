package com.unciv.logic.automation.civilization

import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.GreatWorkType
import com.unciv.models.ruleset.ThemingBonus

/**
 * How the AI arranges its Great Works each turn (CvPlayerCulture::DoSwapGreatWorks and what it calls, CvCultureClasses.cpp:941-1749):
 * per kind of slot (writing, art and artifacts, music), the buildings in the order of the AI priority of their first theming bonus are
 * themed with whole matching sets of works, undamaged cities first; then those still one work short try to swap with another civilization;
 * then the first work left of each kind is marked as the one to swap; then the leftovers fill the unthemed buildings.
 *
 * Swaps between civilizations (CvGameCulture::SwapGreatWorks): both works must be the ones their owners marked as swappable. As in the DLL
 * the work wanted is looked up among the works of its creator, so only works still owned by the civilization that made them can be taken.
 * Humans have no way to mark a work in Unciv: where the DLL would take a human's marked work, the AI proposes the same one-for-one exchange
 * as a trade request instead, which the human accepting stands in for the marking.
 */
object GreatWorkSwapAutomation {

    /** CvGreatWorkBuildingInMyEmpire */
    private class EmpireBuilding(val city: City, val building: Building, val endangered: Boolean) {
        var themed = false
        val slots get() = building.greatWorkSlotTypes.size
    }

    /** What one call works with: the civilization, and the cities whose works were moved, whose stats are updated once at the end */
    private class Context(val civ: Civilization) {
        val ruleset = civ.gameInfo.ruleset
        val touchedCities = LinkedHashSet<City>()
        fun typeOf(work: GreatWorkInstance): GreatWorkType? = ruleset.greatWorks[work.work]?.type
    }

    /** CvPlayerCulture::DoSwapGreatWorks (CvEconomicAI::DoTurn, AI players only) */
    fun doSwapGreatWorks(civ: Civilization) {
        val context = Context(civ)
        val buildingOrder = HashMap<String, Int>()
        for ((index, name) in context.ruleset.buildings.keys.withIndex()) buildingOrder[name] = index

        val writingBuildings = ArrayList<EmpireBuilding>()
        val artBuildings = ArrayList<EmpireBuilding>()
        val musicBuildings = ArrayList<EmpireBuilding>()
        val writings = ArrayList<GreatWorkInstance>()
        val art = ArrayList<GreatWorkInstance>()
        val artifacts = ArrayList<GreatWorkInstance>()
        val music = ArrayList<GreatWorkInstance>()

        // Cities in order, their buildings in the order of the ruleset (the DLL loops over building classes), slots in order
        for (city in civ.cities) {
            val endangered = city.health < city.getMaxHealth()
            for (building in city.cityConstructions.getBuiltBuildings().sortedBy { buildingOrder[it.name] ?: Int.MAX_VALUE }) {
                val types = building.greatWorkSlotTypes
                if (types.isEmpty()) continue
                val entry = EmpireBuilding(city, building, endangered)
                when (types[0]) {
                    GreatWorkSlotType.Literature -> writingBuildings.add(entry)
                    GreatWorkSlotType.ArtArtifact -> artBuildings.add(entry)
                    GreatWorkSlotType.Music -> musicBuildings.add(entry)
                }
                for (slot in city.greatWorks.getSlots(building)) {
                    val work = slot.getWork() ?: continue
                    when (context.typeOf(work)) {
                        GreatWorkType.Literature -> writings.add(work)
                        GreatWorkType.Art -> art.add(work)
                        GreatWorkType.Artifact -> artifacts.add(work)
                        GreatWorkType.Music -> music.add(work)
                        null -> {}
                    }
                }
            }
        }

        moveWorks(context, GreatWorkSlotType.Literature, writingBuildings, writings, ArrayList())
        moveWorks(context, GreatWorkSlotType.ArtArtifact, artBuildings, art, artifacts)
        moveWorks(context, GreatWorkSlotType.Music, musicBuildings, music, ArrayList())
        for (city in context.touchedCities) city.cityStats.update()
    }

    /** CvPlayerCulture::MoveWorks */
    private fun moveWorks(context: Context, type: GreatWorkSlotType, unsorted: List<EmpireBuilding>,
                          works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        // SortThemingBonus: by the AI priority of the first theming bonus, buildings without one last
        val buildings = unsorted.sortedByDescending { it.building.themingBonuses.firstOrNull()?.aiPriority ?: Int.MIN_VALUE }

        for (building in buildings) if (!building.endangered)
            building.themed = themeBuilding(context, building, works1, works2, considerOtherPlayers = false)
        for (building in buildings) if (building.endangered)
            building.themed = themeBuilding(context, building, works1, works2, considerOtherPlayers = false)
        if (type != GreatWorkSlotType.Music)
            for (building in buildings) if (!building.endangered && !building.themed
                && themeBuilding(context, building, works1, works2, considerOtherPlayers = true)) building.themed = true

        // The first work left that is not themed is the one we are willing to trade
        val culture = context.civ.culture
        when (type) {
            GreatWorkSlotType.Literature -> culture.swappableWriting = works1.firstOrNull()?.id ?: ""
            GreatWorkSlotType.ArtArtifact -> {
                culture.swappableArt = works1.firstOrNull()?.id ?: ""
                culture.swappableArtifact = works2.firstOrNull()?.id ?: ""
            }
            GreatWorkSlotType.Music -> {}
        }

        for (building in buildings) if (!building.endangered && !building.themed) fillBuilding(context, building, works1, works2)
        for (building in buildings) if (building.endangered && !building.themed) fillBuilding(context, building, works1, works2)
    }

    /** CvPlayerCulture::ThemeBuilding: fills [entry] with works that make one of its theming bonuses, trying the bonuses in order */
    private fun themeBuilding(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>,
                              considerOtherPlayers: Boolean): Boolean {
        val building = entry.building
        val numSlots = entry.slots
        if (numSlots < 2) return false
        val owner = context.civ.civName

        for ((bonusIndex, bonus) in building.themingBonuses.withIndex()) {
            val worksToConsider: List<GreatWorkInstance> = when {
                bonus.mustBeArt -> ArrayList(works1)
                bonus.mustBeArtifact -> ArrayList(works2)
                bonus.mustBeEqualArtArtifact -> {
                    if (themeEqualArtArtifact(context, entry, bonusIndex, numSlots, works1, works2)) return true
                    // The DLL goes on with the list of the previous bonus (empty for the first). Every building with this bonus in BNW (the Louvre)
                    // has no other, so that list is always empty there
                    continue
                }
                else -> works1 + works2
            }
            if (worksToConsider.size < numSlots) continue

            for ((starterIndex, starter) in worksToConsider.withIndex()) {
                if (bonus.requiresOwner && starter.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

                val chosen = arrayListOf(starter)
                val players = arrayListOf(starter.creatorCiv)
                val eras = arrayListOf(starter.era)
                for (index in starterIndex + 1 until worksToConsider.size) {
                    if (chosen.size >= numSlots) break
                    val work = worksToConsider[index]
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }

                var themedProperly = false
                if (chosen.size == numSlots && getThemingBonusIndex(context, owner, building, chosen) == bonusIndex)
                    themedProperly = true
                else if (considerOtherPlayers && chosen.size == numSlots - 1 && building.greatWorkSlotTypes[0] != GreatWorkSlotType.Music) {
                    val artSlots = building.greatWorkSlotTypes[0] == GreatWorkSlotType.ArtArtifact
                    // What we take from another civilization and what we give for it
                    val wantedType = when {
                        !artSlots -> GreatWorkType.Literature
                        bonus.mustBeArt -> GreatWorkType.Art
                        bonus.mustBeArtifact -> GreatWorkType.Artifact
                        else -> null
                    }
                    val giveFrom = if (wantedType == GreatWorkType.Artifact) works2 else works1
                    if (wantedType != null)
                        themedProperly = acquireLastWork(context, building, bonusIndex, bonus, wantedType, chosen, eras, players, giveFrom)
                }

                if (themedProperly) {
                    // The works are put in the building in the order they were chosen; one that was just acquired is not among those
                    // considered and stays where it arrived (as in the DLL)
                    for ((slotIndex, work) in chosen.withIndex())
                        if (worksToConsider.any { it === work }) moveWorkIntoSlot(context, work, entry, slotIndex)
                    works1.removeAll { work -> chosen.any { it === work } }
                    works2.removeAll { work -> chosen.any { it === work } }
                    return true
                }
            }
        }
        return false
    }

    /**
     * CvPlayerCulture::ThemeEqualArtArtifact: half the slots artifacts, half art. Like the DLL this looks to other civilizations for a last piece of art
     * whether or not the pass considers other players.
     */
    private fun themeEqualArtArtifact(context: Context, entry: EmpireBuilding, bonusIndex: Int, numSlots: Int,
                                      works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>): Boolean {
        val worksInHalf = numSlots / 2
        if (worksInHalf % 2 != 0 || works1.size < worksInHalf || works2.size < worksInHalf) return false
        val building = entry.building
        if (building.greatWorkSlotTypes.size < 2) return false
        val bonus = building.themingBonuses[bonusIndex]
        val owner = context.civ.civName

        val artifactsSnapshot = ArrayList(works2)
        for ((starterIndex, starter) in artifactsSnapshot.withIndex()) {
            if (bonus.requiresOwner && starter.creatorCiv != owner) continue
            if (bonus.requiresAnyButOwner && starter.creatorCiv == owner) continue

            val artifactsChosen = arrayListOf(starter)
            val artifactPlayers = arrayListOf(starter.creatorCiv)
            val artifactEras = arrayListOf(starter.era)
            for (index in starterIndex + 1 until artifactsSnapshot.size) {
                if (artifactsChosen.size >= worksInHalf) break
                val work = artifactsSnapshot[index]
                if (isValidForThemingBonus(bonus, work.era, artifactEras, work.creatorCiv, artifactPlayers, owner)) {
                    artifactsChosen.add(work)
                    artifactPlayers.add(work.creatorCiv)
                    artifactEras.add(work.era)
                }
            }
            if (artifactsChosen.size != worksInHalf) continue

            // Now art for the other half
            var chosen = ArrayList<GreatWorkInstance>()
            var artIndex = 0
            while (artIndex < works1.size && chosen.size < numSlots) {
                val art = works1[artIndex]
                artIndex++
                if (bonus.requiresOwner && art.creatorCiv != owner) continue
                if (bonus.requiresAnyButOwner && art.creatorCiv == owner) continue
                if (bonus.sameEra && art.era != artifactEras[0]) continue
                if (bonus.uniqueEras && art.era in artifactEras) continue

                chosen = ArrayList(artifactsChosen)
                val players = ArrayList(artifactPlayers)
                val eras = ArrayList(artifactEras)
                chosen.add(art)
                players.add(art.creatorCiv)
                eras.add(art.era)
                for (index in artIndex until works1.size) {
                    if (chosen.size >= numSlots) break
                    val work = works1[index]
                    if (isValidForThemingBonus(bonus, work.era, eras, work.creatorCiv, players, owner)) {
                        chosen.add(work)
                        players.add(work.creatorCiv)
                        eras.add(work.era)
                    }
                }

                var themedProperly = false
                if (chosen.size == numSlots && getThemingBonusIndex(context, owner, building, chosen) == bonusIndex)
                    themedProperly = true
                else if (chosen.size == numSlots - 1)
                    themedProperly = acquireLastWork(context, building, bonusIndex, bonus, GreatWorkType.Art, chosen, eras, players, works1)

                if (themedProperly) {
                    for ((slotIndex, work) in chosen.withIndex()) {
                        works1.firstOrNull { it === work }?.let { moveWorkIntoSlot(context, it, entry, slotIndex) }
                        works2.firstOrNull { it === work }?.let { moveWorkIntoSlot(context, it, entry, slotIndex) }
                    }
                    works1.removeAll { work -> chosen.any { it === work } }
                    works2.removeAll { work -> chosen.any { it === work } }
                    return true
                }
            }
        }
        return false
    }

    /**
     * The "one work short, let's look to other players" part of ThemeBuilding and ThemeEqualArtArtifact: each other civilization in turn offers
     * the work of [wantedType] it marked as swappable, which we take for the first of [giveFrom] not [chosen]. On success the work we gave
     * is replaced in [giveFrom] by the one we got, and that is added to [chosen].
     */
    private fun acquireLastWork(context: Context, building: Building, bonusIndex: Int, bonus: ThemingBonus, wantedType: GreatWorkType,
                                chosen: ArrayList<GreatWorkInstance>, eras: List<String>, players: List<String>,
                                giveFrom: ArrayList<GreatWorkInstance>): Boolean {
        val civ = context.civ
        for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other)) continue
            val toGive = giveFrom.firstOrNull { work -> chosen.none { it === work } } ?: continue
            if (other.isHuman()) {
                if (proposeSwapToHuman(context, other, building, bonusIndex, bonus, wantedType, chosen, eras, players, toGive)) return false
                continue
            }
            val wantedId = getSwappable(other, wantedType)
            if (wantedId.isEmpty()) continue
            val wantedSlot = GreatWorkFunctions.findTradeSlot(other, wantedId) ?: continue
            val wanted = wantedSlot.getWork() ?: continue
            if (!isValidForThemingBonus(bonus, wanted.era, eras, wanted.creatorCiv, players, civ.civName)) continue
            chosen.add(wanted) // The DLL keeps it in the list even if the bonus turns out wrong, so later civilizations can not complete the set
            if (getThemingBonusIndex(context, civ.civName, building, chosen) != bonusIndex) continue
            val creator = civ.gameInfo.civilizations.firstOrNull { it.civName == wanted.creatorCiv }
            if (!swapGreatWorks(context, civ, toGive, creator, wanted)) continue
            val index = giveFrom.indexOfFirst { it === toGive }
            giveFrom[index] = wanted
            creator!!.addNotification("[${civ.civName}] has swapped your Great Work [${wanted.work}] for the Great Work [${toGive.work}]",
                NotificationCategory.General, "StatIcons/Culture")
            return true
        }
        return false
    }

    /**
     * Where the DLL would take the work a human marked as swappable: the AI asks for the first work of [wantedType] of the human that completes
     * the set, offering [toGive], which must be the work it marked itself. Not while a request of ours is pending or after a recent refusal.
     * @return whether a request was made
     */
    private fun proposeSwapToHuman(context: Context, human: Civilization, building: Building, bonusIndex: Int, bonus: ThemingBonus,
                                   wantedType: GreatWorkType, chosen: List<GreatWorkInstance>, eras: List<String>, players: List<String>,
                                   toGive: GreatWorkInstance): Boolean {
        val civ = context.civ
        val toGiveType = context.typeOf(toGive) ?: return false
        if (getSwappable(civ, toGiveType) != toGive.id) return false
        if (human.tradeRequests.any { it.requestingCiv == civ.civID }) return false
        if (civ.getDiplomacyManager(human)?.hasFlag(DiplomacyFlags.DeclinedGreatWorkExchange) != false) return false
        for (city in human.cities) for (slot in city.greatWorks.getSlots()) {
            val wanted = slot.getWork() ?: continue
            if (context.typeOf(wanted) != wantedType || wanted.creatorCiv != human.civName) continue
            if (!isValidForThemingBonus(bonus, wanted.era, eras, wanted.creatorCiv, players, civ.civName)) continue
            if (getThemingBonusIndex(context, civ.civName, building, chosen + wanted) != bonusIndex) continue
            val trade = Trade()
            trade.ourOffers.add(TradeOffer(toGive.id, TradeOfferType.GreatWork, speed = civ.gameInfo.speed))
            trade.theirOffers.add(TradeOffer(wanted.id, TradeOfferType.GreatWork, speed = civ.gameInfo.speed))
            if (!TradeEvaluation().isTradeValid(trade, civ, human)) return false
            human.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
            return true
        }
        return false
    }

    /** CvPlayerCulture::FillBuilding: any works, in the order of the lists */
    private fun fillBuilding(context: Context, entry: EmpireBuilding, works1: ArrayList<GreatWorkInstance>, works2: ArrayList<GreatWorkInstance>) {
        if (entry.slots < 1) return
        val worksToConsider = works1 + works2
        val chosen = worksToConsider.take(entry.slots)
        for ((slotIndex, work) in chosen.withIndex()) moveWorkIntoSlot(context, work, entry, slotIndex)
        works1.removeAll { work -> chosen.any { it === work } }
        works2.removeAll { work -> chosen.any { it === work } }
    }

    /** CvPlayerCulture::MoveWorkIntoSlot: [work] goes to the slot, what was there goes where [work] was */
    private fun moveWorkIntoSlot(context: Context, work: GreatWorkInstance, entry: EmpireBuilding, slotIndex: Int) {
        val from = findSlot(context.civ, work) ?: return
        val to = GreatWorkSlot(entry.city, entry.building, slotIndex)
        if (from == to) return
        val other = to.getWork()
        from.city.greatWorks.take(from)
        to.city.greatWorks.take(to)
        to.city.greatWorks.place(work, to)
        if (other != null) from.city.greatWorks.place(other, from)
        context.touchedCities.add(from.city)
        context.touchedCities.add(to.city)
    }

    private fun findSlot(civ: Civilization, work: GreatWorkInstance): GreatWorkSlot? {
        for (city in civ.cities) for (slot in city.greatWorks.getSlots()) if (slot.getWork() === work) return slot
        return null
    }

    /**
     * CvGameCulture::SwapGreatWorks: both works must be of one kind and be what their owners marked as swappable. The marking of [first] is used up
     * before the one of [second] is checked, as in the DLL. Works are exchanged slot for slot.
     */
    private fun swapGreatWorks(context: Context, first: Civilization, firstWork: GreatWorkInstance, second: Civilization?, secondWork: GreatWorkInstance): Boolean {
        if (second == null || !first.isAlive() || !second.isAlive()) return false
        val type = context.typeOf(firstWork) ?: return false
        if (type != context.typeOf(secondWork)) return false
        if (getSwappable(first, type) != firstWork.id) return false
        setSwappable(first, type, "")
        if (getSwappable(second, type) != secondWork.id) return false
        setSwappable(second, type, "")
        val firstSlot = findSlot(first, firstWork) ?: return false
        val secondSlot = findSlot(second, secondWork) ?: return false
        firstSlot.city.greatWorks.place(secondWork, firstSlot)
        secondSlot.city.greatWorks.place(firstWork, secondSlot)
        context.touchedCities.add(firstSlot.city)
        secondSlot.city.cityStats.update()
        return true
    }

    /** CvDiplomacyAI::IsPlayerValid: another living major civilization with cities that we have met */
    private fun isPlayerValid(civ: Civilization, other: Civilization): Boolean =
        other != civ && other.isMajorCiv && other.isAlive() && other.cities.isNotEmpty() && civ.knows(other)

    private fun getSwappable(civ: Civilization, type: GreatWorkType): String = when (type) {
        GreatWorkType.Literature -> civ.culture.swappableWriting
        GreatWorkType.Art -> civ.culture.swappableArt
        GreatWorkType.Artifact -> civ.culture.swappableArtifact
        GreatWorkType.Music -> civ.culture.swappableMusic
    }

    private fun setSwappable(civ: Civilization, type: GreatWorkType, id: String) {
        when (type) {
            GreatWorkType.Literature -> civ.culture.swappableWriting = id
            GreatWorkType.Art -> civ.culture.swappableArt = id
            GreatWorkType.Artifact -> civ.culture.swappableArtifact = id
            GreatWorkType.Music -> civ.culture.swappableMusic = id
        }
    }

    /** CultureHelpers::IsValidForThemingBonus: whether a work can join a set with [eras] and [players] seen so far */
    private fun isValidForThemingBonus(bonus: ThemingBonus, era: String, eras: List<String>, player: String, players: List<String>, owner: String): Boolean {
        if (bonus.sameEra && era != eras[0]) return false
        if (bonus.uniqueEras && era in eras) return false
        if (bonus.requiresOwner && player != owner) return false
        if (bonus.requiresAnyButOwner && player == owner) return false
        if (bonus.requiresSamePlayer && player != players[0]) return false
        if (bonus.requiresUniquePlayers && player in players) return false
        return true
    }

    /** CultureHelpers::GetThemingBonusIndex: the first theming bonus of [building] that [works] make, -1 if none or not one work per slot */
    private fun getThemingBonusIndex(context: Context, owner: String, building: Building, works: List<GreatWorkInstance>): Int {
        if (works.size != building.greatWorkSlotTypes.size) return -1
        val types = works.map { context.typeOf(it) ?: return -1 }
        return building.themingBonuses.indexOfFirst { it.isSatisfiedBy(works, types, owner) }
    }
}
