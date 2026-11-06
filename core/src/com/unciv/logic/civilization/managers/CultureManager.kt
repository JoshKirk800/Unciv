package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** How far a civilization's tourism has made another take to its culture (CvCultureClasses.h InfluenceLevels) */
enum class InfluenceLevel(val label: String) {
    Unknown("Unknown"), Exotic("Exotic"), Familiar("Familiar"), Popular("Popular"), Influential("Influential"), Dominant("Dominant");

    /** The tourism needed, in percent of the culture of the other civilization, see the `influencePercent...` constants */
    @Readonly
    fun getThresholdPercent(civ: Civilization): Int {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        return when (this) {
            Unknown -> 0
            Exotic -> constants.influencePercentExotic
            Familiar -> constants.influencePercentFamiliar
            Popular -> constants.influencePercentPopular
            Influential -> constants.influencePercentInfluential
            Dominant -> constants.influencePercentDominant
        }
    }

    companion object {
        @Readonly
        fun fromLabel(label: String): InfluenceLevel? = entries.firstOrNull { it.label == label }
    }
}

/** The relations that change the modifier of the tourism towards another civilization, in the unique `[relativeAmount]% Tourism towards civilizations with [tourismRelation]` */
enum class TourismRelation(val label: String) {
    SharedReligion("Shared Religion"),
    OpenBorders("Open Borders"),
    TradeRoute("Trade Route"),
    LowerHappiness("Lower Happiness"),
    CommonEnemies("Common Enemies"),
    /** Both civilizations follow the same ideology */
    SharedIdeology("Shared Ideology"),
    /** Both follow an ideology, and not the same one */
    DifferentIdeologies("Different Ideologies");

    companion object {
        @Readonly
        fun fromLabel(label: String): TourismRelation? = entries.firstOrNull { it.label == label }
    }
}

enum class InfluenceTrend { Rising, Falling, Static }

/**
 * Tourism and influence of a civilization (CvPlayerCulture in the original).
 *
 * The tourism of its cities is added every turn to its influence on every other civilization it has met, after the modifiers that
 * depend on the two civilizations. Influence only grows. How influential a civilization is over another depends on how much that
 * is compared to all the culture the other has made, see [getInfluenceLevel]. Being [InfluenceLevel.Influential] over all other civilizations wins the culture victory.
 */
class CultureManager : IsPartOfGameInfoSerialization {

    /** Tourism towards each other civilization added up over the game, by civ id */
    var influence = Counter<String>()
        private set

    /** [influence] and the lifetime culture as they were before the last turn, to see where things are heading */
    var lastTurnInfluence = Counter<String>()
        private set
    var lastTurnLifetimeCulture = 0
        private set

    /** The notifications about the progress towards a culture victory come once each */
    var reportedTwoCivsAway = false
        private set
    var reportedOneCivAway = false
        private set
    var numCivsInfluentialLastTurn = 0
        private set

    /** What the people think of the ideology of the civilization */
    var publicOpinion = PublicOpinion()
        private set

    @Transient
    lateinit var civ: Civilization
        private set

    fun clone(): CultureManager {
        val toReturn = CultureManager()
        toReturn.influence = influence.clone()
        toReturn.lastTurnInfluence = lastTurnInfluence.clone()
        toReturn.lastTurnLifetimeCulture = lastTurnLifetimeCulture
        toReturn.reportedTwoCivsAway = reportedTwoCivsAway
        toReturn.reportedOneCivAway = reportedOneCivAway
        toReturn.numCivsInfluentialLastTurn = numCivsInfluentialLastTurn
        toReturn.publicOpinion = publicOpinion.clone()
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        publicOpinion.setTransients(civ)
    }

    //region Tourism of the cities
    /** The tourism a city makes that does not depend on who it goes to, before the modifiers of the city (CvCityCulture::GetBaseTourismBeforeModifiers) */
    @Readonly
    fun getCityTourismBeforeModifiers(city: City): Int {
        if (city.isInResistance() || city.isBeingRazed) return 0
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val state = city.state

        var base = city.greatWorks.getNumWorks() * constants.greatWorkBaseTourism
        var greatWorkPercent = 0
        for (unique in city.getMatchingUniques(UniqueType.GreatWorksTourismPercent, state))
            if (city.matchesFilter(unique.params[1])) greatWorkPercent += unique.params[0].toInt()
        base += greatWorkPercent * base / 100
        base += city.greatWorks.getTotalThemingBonus()

        var landmarkPercent = 0
        for (unique in city.getMatchingUniques(UniqueType.LandmarksTourismPercent, state))
            if (city.matchesFilter(unique.params[1])) landmarkPercent += unique.params[0].toInt()
        if (landmarkPercent > 0)
            base += (getCultureOfWonders(city) + getCultureOfWorkedTiles(city)) * landmarkPercent / 100

        for (unique in city.getMatchingUniques(UniqueType.FlatTourism, state))
            if (city.matchesFilter(unique.params[1])) base += unique.params[0].toInt()
        return base
    }

    /** The culture the world wonders of the city give by themselves */
    @Readonly
    private fun getCultureOfWonders(city: City): Int =
        city.cityConstructions.getBuiltBuildings().filter { it.isWonder }.sumOf { it.culture.toInt() }

    /** CvCityCulture::GetCultureFromNaturalWonders and GetCultureFromImprovements: the culture of worked natural wonders and improved tiles, the city tile left out */
    @Readonly
    private fun getCultureOfWorkedTiles(city: City): Int =
        city.getWorkedTiles()
            .filter { !it.isCityCenter() && (it.isNaturalWonder() || it.improvement != null) }
            .sumOf { it.stats.getTileStats(city, civ).culture.toInt() }

    /** The tourism of a city (CvCityCulture::GetBaseTourism): the above, increased by the bonuses of the city if they add up to something */
    @Readonly
    fun getCityTourism(city: City, skipBlockable: Civilization? = null): Int {
        val before = getCityTourismBeforeModifiers(city)
        var percent = 0
        for (unique in city.getMatchingUniques(UniqueType.TourismPercent, city.state))
            if (city.matchesFilter(unique.params[1])) percent += unique.params[0].toInt()
        for (unique in city.getMatchingUniques(UniqueType.TourismPercentUnlessBlocked, city.state)) {
            if (!city.matchesFilter(unique.params[1])) continue
            if (skipBlockable != null && skipBlockable.cities.any { it.cityConstructions.containsBuildingOrEquivalent(unique.params[2]) }) continue
            percent += unique.params[0].toInt()
        }
        percent += civ.gameInfo.league?.getHolyCityTourismPercent(city) ?: 0 // World Religion (LR 4.3.10)
        return if (percent > 0) before * (100 + percent) / 100 else before
    }

    /** Everything the cities of the civilization make, before what depends on who it goes to (CvPlayerCulture::GetTourism) */
    @Readonly
    fun getTourism(): Int = civ.cities.sumOf { getCityTourism(it) }
    //endregion

    //region Modifiers between two civilizations
    @Readonly
    private fun getReligionInMostCities(civ: Civilization): String? {
        val religions = civ.cities.mapNotNull { it.religion.getMajorityReligionName() }
        return religions.distinct().firstOrNull { name -> religions.count { it == name } * 2 > civ.cities.size }
    }

    @Readonly
    private fun areConnectedByTrade(other: Civilization): Boolean =
        civ.tradeRoutes.routes.any { it.getDestinationCiv() == other } || other.tradeRoutes.routes.any { it.getDestinationCiv() == civ }

    /** The relations between this civilization and [other] that hold, as seen from this one as the one who makes the tourism */
    @Readonly
    fun getRelationsWith(other: Civilization): List<TourismRelation> = TourismRelation.entries.filter { relation ->
        when (relation) {
            TourismRelation.SharedReligion -> getReligionInMostCities(civ).let { it != null && it == getReligionInMostCities(other) }
            TourismRelation.OpenBorders -> civ.getDiplomacyManager(other)?.hasOpenBorders == true
            TourismRelation.TradeRoute -> areConnectedByTrade(other)
            TourismRelation.LowerHappiness -> civ.stats.happiness > other.stats.happiness
            TourismRelation.CommonEnemies -> getNumCommonEnemies(other) > 0
            TourismRelation.SharedIdeology -> civ.getIdeology().let { it != null && it == other.getIdeology() }
            TourismRelation.DifferentIdeologies -> civ.getIdeology().let { mine -> mine != null && other.getIdeology().let { it != null && it != mine } }
        }
    }

    @Readonly
    private fun getNumCommonEnemies(other: Civilization): Int =
        civ.gameInfo.civilizations.count {
            it.isMajorCiv && !it.isDefeated() && it != civ && it != other && civ.isAtWarWith(it) && other.isAtWarWith(it)
        }

    /** The percent more that the tourism towards [other] is (CvCityCulture::GetTourismMultiplier, which the original asks of the capital) */
    @Readonly
    fun getTourismModifierToward(other: Civilization): Int {
        if (civ.getCapital() == null) return 0
        val constants = civ.gameInfo.ruleset.modOptions.constants
        var modifier = 0
        val relations = getRelationsWith(other)
        for (relation in relations) modifier += when (relation) {
            TourismRelation.SharedReligion -> constants.tourismModifierSharedReligion
            TourismRelation.OpenBorders -> constants.tourismModifierOpenBorders
            TourismRelation.TradeRoute -> constants.tourismModifierTradeRoute
            TourismRelation.DifferentIdeologies -> constants.tourismModifierDifferentIdeologies
            else -> 0
        }
        for (unique in civ.getMatchingUniques(UniqueType.TourismTowardsPercent)) {
            val relation = TourismRelation.fromLabel(unique.params[1]) ?: continue
            if (relation !in relations) continue
            modifier += unique.params[0].toInt() * (if (relation == TourismRelation.CommonEnemies) getNumCommonEnemies(other) else 1)
        }
        return modifier
    }
    //endregion

    //region Influence
    /** Whether tourism goes to [other] at all: it has to be a living major civilization that we have met */
    @Readonly
    fun canInfluence(other: Civilization) = other != civ && other.isMajorCiv && !other.isDefeated() && civ.knows(other)

    /** The tourism that is added to the influence on [other] every turn (CvPlayerCulture::GetInfluencePerTurn) */
    @Readonly
    fun getInfluencePerTurn(other: Civilization): Int {
        if (!canInfluence(other)) return 0
        val total = civ.cities.sumOf { getCityTourism(it, skipBlockable = other) }
        return total * (100 + getTourismModifierToward(other)) / 100
    }

    @Readonly
    fun getInfluence(other: Civilization) = influence[other.civID]

    /** The influence on [other] as a percentage of the culture it has made in the whole game */
    @Readonly
    fun getInfluencePercent(other: Civilization): Int {
        val lifetimeCulture = other.totalCultureForContests
        return if (lifetimeCulture > 0) (getInfluence(other).toLong() * 100 / lifetimeCulture).toInt() else 0
    }

    /** CvPlayerCulture::GetInfluenceLevel, null if we have not met [other] */
    @Readonly
    fun getInfluenceLevel(other: Civilization): InfluenceLevel? {
        if (!civ.knows(other)) return null
        val percent = getInfluencePercent(other)
        return InfluenceLevel.entries.reversed().first { percent >= it.getThresholdPercent(civ) }
    }

    @Readonly
    fun isInfluentialOver(other: Civilization) = (getInfluenceLevel(other) ?: InfluenceLevel.Unknown) >= InfluenceLevel.Influential

    /** CvPlayerCulture::GetInfluenceTrend: whether the influence on [other] grows faster than the culture of the other */
    @Readonly
    fun getInfluenceTrend(other: Civilization): InfluenceTrend? {
        val now = other.totalCultureForContests.toLong()
        val before = other.culture.lastTurnLifetimeCulture.toLong()
        if (now <= 0 || before <= 0) return null
        val lhs = getInfluence(other).toLong() * before
        val rhs = lastTurnInfluence[other.civID].toLong() * now
        return when {
            lhs > rhs -> InfluenceTrend.Rising
            lhs < rhs -> InfluenceTrend.Falling
            else -> InfluenceTrend.Static
        }
    }

    /** CvPlayerCulture::GetTurnsToInfluential, if things go on as they are. 999 if they will not */
    @Readonly
    fun getTurnsToInfluential(other: Civilization): Int {
        val level = getInfluenceLevel(other) ?: return NEVER
        if (level >= InfluenceLevel.Influential) return 0
        if (getInfluenceTrend(other) != InfluenceTrend.Rising) return NEVER
        val percent = civ.gameInfo.ruleset.modOptions.constants.influencePercentInfluential
        val needed = percent.toLong() * other.totalCultureForContests / 100 - getInfluence(other)
        val perTurn = getInfluencePerTurn(other) - percent.toLong() * other.stats.statsForNextTurn.culture.toInt() / 100
        return if (perTurn > 0) ((needed + perTurn - 1) / perTurn).toInt() else NEVER
    }

    /** How many steps above Exotic the influence on [other] is: 0 up to Exotic, 1 for Familiar, up to 4 for Dominant */
    @Readonly
    private fun getInfluenceSteps(other: Civilization): Int =
        ((getInfluenceLevel(other)?.ordinal ?: 0) - InfluenceLevel.Exotic.ordinal).coerceAtLeast(0)

    /** The science more that a trade route to [other] gives (CvPlayerCulture::GetInfluenceTradeRouteScienceBonus) */
    @Readonly
    fun getTradeRouteScienceBonus(other: Civilization): Int = getInfluenceSteps(other)

    /** The percent by which a conquest of a city of [other] costs less population and resistance (CvPlayerCulture::GetInfluenceCityConquestReduction) */
    @Readonly
    fun getConquestReductionPercent(other: Civilization): Int = 25 * getInfluenceSteps(other)

    /** The civilizations that count for a culture victory: all other living major civilizations, those we have not met too */
    @Readonly
    fun getOtherMajorCivs(): List<Civilization> =
        civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() && it != civ }

    @Readonly
    fun getNumCivsInfluentialOn(): Int = getOtherMajorCivs().count { isInfluentialOver(it) }

    fun addInfluence(other: Civilization, amount: Int) {
        if (amount > 0 && canInfluence(other)) influence.add(other.civID, amount)
    }

    /** Tourism that is not made by the cities: goes to every civilization we have met, as it is (CvPlayerCulture::AddTourismAllKnownCivs) */
    fun addInfluenceOnAllKnownCivs(amount: Int) {
        for (other in getOtherMajorCivs()) addInfluence(other, amount)
    }

    /** CvUnit::DoConcertTour: [times] our tourism with the civilization that owns the tile, a share [percentOthers] of it for each of the others */
    fun addInfluenceFromConcertTour(target: Civilization, blast: Int, percentOthers: Int) {
        for (other in getOtherMajorCivs()) {
            if (other == target) addInfluence(other, blast)
            else addInfluence(other, blast * percentOthers / 100)
        }
    }
    //endregion

    //region Turns
    /** What the trend looks at: how things were before this turn's culture and tourism */
    fun rememberLastTurn() {
        lastTurnInfluence = influence.clone()
        lastTurnLifetimeCulture = civ.totalCultureForContests
    }

    /** Adds this turn's tourism to the influence on every civilization met, and tells how that is going (CvPlayerCulture::DoTurn) */
    fun endTurn() {
        for (other in getOtherMajorCivs()) addInfluence(other, getInfluencePerTurn(other))
        publicOpinion.update()
        reportProgress()
    }

    private fun reportProgress() {
        if (!civ.isMajorCiv || civ.isSpectator) return
        val victory = civ.gameInfo.ruleset.victories.values.firstOrNull { victory ->
            victory.milestoneObjects.any { it.type == com.unciv.models.ruleset.MilestoneType.InfluentialOverAllCivs }
        }
        if (victory == null || victory.name !in civ.gameInfo.gameParameters.victoryTypes) return

        val count = getNumCivsInfluentialOn()
        val needed = getOtherMajorCivs().size
        if (count > numCivsInfluentialLastTurn && count < needed)
            civ.addNotification("We are now Influential over [$count] of [$needed] civilizations", NotificationCategory.Diplomacy, "StatIcons/Culture")
        else if (count < numCivsInfluentialLastTurn)
            civ.addNotification("We are no longer Influential over every civilization we were", NotificationCategory.Diplomacy, "StatIcons/Culture")
        numCivsInfluentialLastTurn = count

        val everAlive = civ.gameInfo.civilizations.count { it.isMajorCiv }
        if (needed > 0 && count == needed - 2 && count > 0 && !reportedTwoCivsAway && everAlive >= 4) {
            reportedTwoCivsAway = true
            for (other in civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() })
                other.addNotification("[${civ.civName}] is Influential over all but two civilizations and nears a culture victory!", NotificationCategory.Diplomacy, "StatIcons/Culture")
        }
        if (needed > 0 && count == needed - 1 && !reportedOneCivAway && everAlive >= 3) {
            reportedOneCivAway = true
            for (other in civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() })
                other.addNotification("[${civ.civName}] is Influential over all but one civilization and is about to win a culture victory!", NotificationCategory.Diplomacy, "StatIcons/Culture")
        }
    }
    //endregion

    companion object {
        /** What [getTurnsToInfluential] returns when it will not come to that */
        const val NEVER = 999
    }
}
