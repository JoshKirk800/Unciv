package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.city.managers.CityEspionageManager
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile
import com.unciv.models.Spy
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.devconsole.ConsoleCivCommands
import yairm210.purity.annotations.Readonly


class EspionageManager : IsPartOfGameInfoSerialization {

    var spyList = ArrayList<Spy>()
    val erasSpyEarnedFor = LinkedHashSet<String>()

    /** The game turn our first spy arrived (CvEspionageAI::m_iTurnEspionageStarted), -1 before; read by espionage strategy tests */
    var turnEspionageStarted = -1
    /** How often a foreign spy stole a technology in each of our cities, by [City.id] (CvCityEspionage::m_aiNumTimesCityRobbed) */
    var timesCityRobbed = HashMap<String, Int>()
    /** The steal rate x100 our spy saw when surveillance was established in a foreign city, by [City.id] (CvCityEspionage::m_aiLastBasePotential) */
    var lastBasePotential = HashMap<String, Int>()
    /** AI memory: the turn each civilization (by civID) we had surveillance on last had nothing left to steal (CvEspionageAI::m_aiCivOutOfTechTurn) */
    var civOutOfTechTurn = HashMap<String, Int>()
    /** AI memory: whether the United Nations countdown already made every spy reconsider its post (CvEspionageAI::m_bUNCountdownStarted) */
    var unCountdownStarted = false
    /** The intrigue we know, uncovered by our spies or told to us (CvPlayerEspionage::m_aIntrigueNotificationMessages) */
    var intrigueMessages = ArrayList<IntrigueMessage>()

    @Transient
    lateinit var civInfo: Civilization

    /**
     * Part of the nextTurnAction of MoveSpies.
     * We need to store if the player has clicked the button already
     */
    @Transient
    var dismissedShouldMoveSpies = false

    fun clone(): EspionageManager {
        val toReturn = EspionageManager()
        spyList.mapTo(toReturn.spyList) { it.clone() }
        toReturn.erasSpyEarnedFor.addAll(erasSpyEarnedFor)
        toReturn.turnEspionageStarted = turnEspionageStarted
        toReturn.timesCityRobbed.putAll(timesCityRobbed)
        toReturn.lastBasePotential.putAll(lastBasePotential)
        toReturn.civOutOfTechTurn.putAll(civOutOfTechTurn)
        toReturn.unCountdownStarted = unCountdownStarted
        intrigueMessages.mapTo(toReturn.intrigueMessages) { it.clone() }
        return toReturn
    }

    private companion object {
        /** CvEspionageClasses.cpp: iIntrigueTurnsValid */
        const val INTRIGUE_TURNS_VALID = 5
    }

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        for (spy in spyList) {
            spy.setTransients(civInfo)
        }
        // Saves from before the field existed: espionage started at the latest now
        if (turnEspionageStarted == -1 && spyList.isNotEmpty()) turnEspionageStarted = civInfo.gameInfo.turns
    }

    fun endTurn() {
        for (spy in spyList.toList()) {
            val city = spy.getCityOrNull()
            // Razing removes the city even for ordinary spies. War, capture or a new capital ends a diplomat's post.
            if ((city == null && !spy.isIdle() && spy.isAlive()) ||
                (spy.isDiplomat && (city == null || !spy.canMoveTo(city, asDiplomat = true))))
                spy.moveTo(null)
            spy.endTurn()
        }
    }

    //region Intrigue
    /**
     * CvPlayerEspionage::AddIntrigueMessage: remember a piece of intrigue found out by [discoverer] ([civInfo] or an AI that told us), unless we
     * know it already: a message of the same source, target and type within the last 30 turns (for Deception and the armies being built that
     * is all that is compared, for the others the construction and the city too). A human gets the notification if [notify].
     * Returns whether the message was added.
     */
    fun addIntrigueMessage(
        discoverer: Civilization, source: Civilization, target: Civilization?, targetHidden: Boolean, construction: String?,
        type: IntrigueType, city: City?, notify: Boolean, spy: Spy?
    ): Boolean {
        val turn = civInfo.gameInfo.turns
        val cityLocation = city?.location
        for (known in intrigueMessages) {
            if (known.source != source.civID || known.target != target?.civID || known.targetHidden != targetHidden || known.type != type) continue
            val withinTimer = turn - known.turn < 30
            if (type == IntrigueType.Deception || type == IntrigueType.BuildingArmy || type == IntrigueType.BuildingAmphibiousArmy) {
                if (withinTimer) return false
            } else if (known.construction == construction && known.cityLocation == cityLocation && withinTimer) return false
        }
        val message = IntrigueMessage()
        message.discoverer = discoverer.civID
        message.source = source.civID
        message.target = target?.civID
        message.targetHidden = targetHidden
        message.construction = construction
        message.type = type
        message.cityLocation = cityLocation
        message.turn = turn
        if (discoverer == civInfo && spy != null) message.spyName = spy.name
        intrigueMessages.add(message)
        if (notify) IntrigueFunctions.notify(civInfo, message)
        return true
    }

    /** CvPlayerEspionage::GetRecentIntrigueInfo: the first message about [target] that is not shared yet and at most 5 turns old (iIntrigueTurnsValid) */
    @Readonly
    fun getRecentIntrigueInfo(target: Civilization): IntrigueMessage? =
        intrigueMessages.firstOrNull { !it.shared && it.target == target.civID && it.turn >= civInfo.gameInfo.turns - INTRIGUE_TURNS_VALID }

    /** CvPlayerEspionage::HasRecentIntrigueAbout */
    @Readonly fun hasRecentIntrigueAbout(target: Civilization) = getRecentIntrigueInfo(target) != null

    /** CvPlayerEspionage::HasSharedIntrigue: we found this out and told [target] about it within the last 30 turns */
    @Readonly
    fun hasSharedIntrigue(target: Civilization, source: Civilization, type: IntrigueType): Boolean =
        intrigueMessages.any {
            it.shared && it.discoverer == civInfo.civID && it.target == target.civID && it.source == source.civID && it.type == type &&
                civInfo.gameInfo.turns - it.turn <= 30
        }

    /** CvPlayerEspionage::MarkRecentIntrigueAsShared: [target] knows now what we found out of this kind. Returns the number of messages marked. */
    fun markRecentIntrigueAsShared(target: Civilization, source: Civilization, type: IntrigueType): Int {
        var count = 0
        for (message in intrigueMessages) {
            if (message.shared || message.discoverer != civInfo.civID || message.target != target.civID ||
                message.source != source.civID || message.type != type) continue
            message.shared = true
            count++
        }
        return count
    }
    //endregion

    @Readonly
    fun getSpyName(): String {
        val rng = civInfo.state.stateBasedRandom("EspianageManager.getSpyName")
        val usedSpyNames = spyList.map { it.name }.toHashSet()
        val validSpyNames = civInfo.nation.spyNames.filter { it !in usedSpyNames }
        return validSpyNames.randomOrNull(rng)
            ?: "Spy ${spyList.size + 1}" // +1 as non-programmers count from 1
    }

    fun addSpy(): Spy {
        val spyName = getSpyName()
        val newSpy = Spy(spyName, getStartingSpyRank())
        newSpy.setTransients(civInfo)
        spyList.add(newSpy)
        if (turnEspionageStarted == -1) turnEspionageStarted = civInfo.gameInfo.turns
        return newSpy
    }

    @Readonly
    fun getTilesVisibleViaSpies(): Sequence<Tile> {
        return spyList.asSequence()
            .filter { it.isSetUp() }
            .mapNotNull { it.getCityOrNull() }
            .flatMap { it.getCenterTile().getTilesInDistance(1) }
    }

    @Readonly
    fun getTechsToSteal(otherCiv: Civilization): Set<String> {
        val techsToSteal = mutableSetOf<String>()
        for (tech in otherCiv.tech.techsResearched) {
            if (civInfo.tech.isResearched(tech)) continue
            if (!civInfo.tech.canBeResearched(tech)) continue
            techsToSteal.add(tech)
        }
        return techsToSteal
    }

    /**
     * How fast a spy without rank steals in [city]: CvPlayerEspionage::CalcPerTurn(SPY_STATE_GATHERING_INTEL, city, -1).
     * The city's science x100 with the percentages of the city owner against spies ([UniqueType.EnemySpyEffectiveness]) and of this
     * civilization's spies ([UniqueType.SpyEffectiveness]) summed as in the original; at least 1. The game speed factor of the original
     * is left out: Unciv puts it into the cost of the theft instead.
     */
    @Readonly
    fun getBaseStealRateTimes100(city: City): Int {
        var percent = 100
        for (unique in city.getMatchingUniques(UniqueType.EnemySpyEffectiveness, city.state, includeCivUniques = true))
            if (city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        for (unique in civInfo.getMatchingUniques(UniqueType.SpyEffectiveness))
            if (city.matchesFilter(unique.params[1], civInfo)) percent += unique.params[0].toInt()
        val science = (city.cityStats.currentCityStats.science * 100).toInt()
        return maxOf(science * percent / 100, 1)
    }

    @Readonly fun getSpiesInCity(city: City): List<Spy> = spyList.filter { it.getCityOrNull() == city }

    @Readonly fun getStartingSpyRank(): Int = 1 + civInfo.getMatchingUniques(UniqueType.SpyStartingLevel).sumOf { it.params[0].toInt() }

    /**
     * Returns a list of all cities with our spies in them.
     * The list needs to be stable across calls on the same turn.
     */
    @Readonly fun getCitiesWithOurSpies(): List<City> = spyList.filter { it.isSetUp() }.mapNotNull { it.getCityOrNull() }

    @Readonly fun getSpyAssignedToCity(city: City): Spy? = spyList.firstOrNull { it.getCityOrNull() == city }

    /** Only a schmoozing diplomat in the target's *current* capital is active (CvPlayerEspionage::IsMyDiplomatVisitingThem). */
    @Readonly
    fun getActiveDiplomatFor(other: Civilization): Spy? {
        if (!other.isMajorCiv || other == civInfo || other.isDefeated() || civInfo.isAtWarWith(other)) return null
        val capital = other.getCapital() ?: return null
        return getSpyAssignedToCity(capital)?.takeIf { it.isDiplomat && it.action == com.unciv.models.SpyAction.Schmoozing }
    }

    /**
     * Determines whether the NextTurnAction MoveSpies should be shown or not
     * @return true if there are spies waiting to be moved
     */
    @Readonly fun shouldShowMoveSpies(): Boolean = !dismissedShouldMoveSpies && spyList.any { it.isIdle() }
            && civInfo.gameInfo.getCities().any { civInfo.hasExplored(it.getCenterTile()) && getSpyAssignedToCity(it) == null }

    @Readonly fun getIdleSpies(): List<Spy> = spyList.filter { it.isIdle() }

    /**
     * Takes all spies away from their cities.
     * Called when the civ is destroyed.
     */
    fun removeAllSpies() {
        spyList.forEach { it.moveTo(null) }
    }

    /**
     * Recalls all spies stationed in any of [otherCiv]'s cities to the hideout.
     *
     * Called when [otherCiv] is destroyed via console [`civ remove`][ConsoleCivCommands].
     * Not using [CityEspionageManager.removeAllPresentSpies], so no notifications.
     */
    internal fun recallAllSpiesFrom(otherCiv: Civilization) {
        val toRecall = spyList.filter { it.getCityOrNull() in otherCiv.cities }
        for (spy in toRecall)
            spy.moveTo(null)
    }
}
