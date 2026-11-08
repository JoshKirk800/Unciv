package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsReligion
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PurchaseReason
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import kotlin.random.Random

/** Port of CvReligionAI (CvReligionClasses.cpp): faith purchases, belief choices and the strategic targets of religious units */
object ReligionAutomation {

    //region Inputs the original reads

    /** CvReligionAI::GetReligionToSpread: the religion this civilization founded, null before it has one (a pantheon does not count) */
    fun religionToSpread(civ: Civilization): Religion? =
        civ.religionManager.religion?.takeIf { civ.religionManager.religionState >= ReligionState.Religion }

    /** The original's UNIT_MISSIONARY, as this civilization's unit: the first unit that can spread religion and is not a Great Person */
    fun missionaryUnit(civ: Civilization): BaseUnit? =
        civ.gameInfo.ruleset.units.values
            .firstOrNull { it.replaces == null && it.hasUnique(UniqueType.CanSpreadReligion) && !it.isGreatPerson }
            ?.let { civ.getEquivalentUnit(it) }

    /** The original's UNIT_INQUISITOR, as this civilization's unit: the first unit that can remove heresy and is not a Great Person */
    fun inquisitorUnit(civ: Civilization): BaseUnit? =
        civ.gameInfo.ruleset.units.values
            .firstOrNull { it.replaces == null && it.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals) && !it.isGreatPerson }
            ?.let { civ.getEquivalentUnit(it) }

    /** CvPlayer::GetNumUnitsWithUnitAI(UNITAI_MISSIONARY) */
    private fun numberOfMissionaries(civ: Civilization): Int =
        civ.units.getCivUnits().count { it.baseUnit.hasUnique(UniqueType.CanSpreadReligion) && !it.baseUnit.isGreatPerson }

    /** CvDiplomacyAI::IsPlayerAgreeNotToConvert: we promised [other] not to spread our religion to it */
    private fun agreedNotToConvert(civ: Civilization, other: Civilization): Boolean =
        civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.AgreedToNotSpreadReligion) == true

    private fun isIndustrialOrLater(civ: Civilization): Boolean =
        civ.getEraNumber() >= (civ.gameInfo.ruleset.eras["Industrial era"]?.eraNumber ?: -1)

    /** CvMap::GetAIMapHint() & 1 (naval map): the map scripts that set it are Archipelago and Tiny Islands, Unciv's Archipelago */
    private fun isNavalMap(civ: Civilization): Boolean = civ.gameInfo.tileMap.mapParameters.type == MapType.archipelago

    /** The name of the Great Person class of [unit] (the original's UNITCLASS_*): its own name or the unit it replaces */
    private fun unitClass(unit: BaseUnit): String = unit.replaces ?: unit.name

    private fun majorityReligionName(city: City): String? = city.religion.getMajorityReligionName()

    /** CvCityReligions::GetFollowersOtherReligions: followers of the founded religions other than [religion] (no pantheons, no atheists) */
    private fun followersOfOtherReligions(city: City, religion: String): Int {
        val religions = city.civ.gameInfo.religions
        var followers = 0
        for ((name, amount) in city.religion.getNumberOfFollowers())
            if (name != religion && religions[name]?.isMajorReligion() == true) followers += amount
        return followers
    }
    //endregion

    //region Faith purchases (CvReligionAI::DoFaithPurchases)

    /** The branches of CvReligionAI::DoFaithPurchases, in priority order */
    enum class FaithPurchase {
        /** No religion (or not enhanced yet) and still possible: save */
        Save,
        /** Locked out of founding: a faith building of the majority religion of one of our cities */
        LockedOutFaithBuilding,
        /** Locked out of founding, nothing to build, Industrial era: the desired Great Person */
        LockedOutGreatPerson,
        /** A city of ours needs a Great Prophet critically, and we have earned at most 5 */
        Prophet,
        MissionaryForOwnCities,
        FaithBuilding,
        NonFaithBuilding,
        GreatPerson,
        MissionaryForPuppets,
        MissionaryForCivsWithoutReligion,
        Inquisitor,
        MissionaryForReligionFounders,
        OtherFaithBuilding,
    }

    /** CvReligionAI::DoTurn → DoFaithPurchases */
    fun spendFaithOnReligion(civ: Civilization) {
        if (civ.cities.isEmpty()) return
        when (chooseFaithPurchase(civ)) {
            FaithPurchase.Save -> {}
            FaithPurchase.LockedOutFaithBuilding, FaithPurchase.OtherFaithBuilding -> buyAnyAvailableFaithBuilding(civ)
            FaithPurchase.LockedOutGreatPerson, FaithPurchase.GreatPerson -> getDesiredFaithGreatPerson(civ)?.let { buyGreatPerson(civ, it) }
            FaithPurchase.Prophet -> civ.religionManager.getGreatProphetEquivalent()?.let { buyGreatPerson(civ, it) }
            FaithPurchase.MissionaryForOwnCities, FaithPurchase.MissionaryForPuppets,
            FaithPurchase.MissionaryForCivsWithoutReligion, FaithPurchase.MissionaryForReligionFounders ->
                buyReligiousUnit(civ, missionaryUnit(civ))
            FaithPurchase.FaithBuilding -> {
                val religion = religionToSpread(civ) ?: return
                val building = faithBuildingAvailable(civ, religion) ?: return
                buyFaithBuilding(civ, religion, building)
            }
            FaithPurchase.NonFaithBuilding -> findNonFaithBuildingPurchase(civ)?.let { (city, building) -> purchase(city, building) }
            FaithPurchase.Inquisitor -> buyReligiousUnit(civ, inquisitorUnit(civ))
        }
    }

    /** CvReligionAI::DoFaithPurchases: which purchase this turn's faith is saved for */
    fun chooseFaithPurchase(civ: Civilization): FaithPurchase {
        val religionManager = civ.religionManager
        val religion = religionToSpread(civ)
        if (religion == null || religionManager.religionState != ReligionState.EnhancedReligion) {
            if (religion != null || religionManager.remainingFoundableReligions() > 0) return FaithPurchase.Save
            if (findAnyAvailableFaithBuilding(civ) != null) return FaithPurchase.LockedOutFaithBuilding
            if (isIndustrialOrLater(civ) && getDesiredFaithGreatPerson(civ) != null) return FaithPurchase.LockedOutGreatPerson
            return FaithPurchase.Save
        }

        val faithBuilding = faithBuildingAvailable(civ, religion)
        val tooManyMissionaries = numberOfMissionaries(civ) > civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MAX_MISSIONARIES)
        return when {
            religionManager.getGreatProphetEquivalent() != null && chooseProphetConversionCity(civ, true) != null
                && religionManager.greatProphetsEarned() <= 5 -> FaithPurchase.Prophet
            !tooManyMissionaries && !areAllOurCitiesConverted(civ, religion, includePuppets = false) -> FaithPurchase.MissionaryForOwnCities
            faithBuilding != null && !areAllOurCitiesWithFaithBuilding(civ, religion, faithBuilding, includePuppets = false) ->
                FaithPurchase.FaithBuilding
            findNonFaithBuildingPurchase(civ) != null -> FaithPurchase.NonFaithBuilding
            isIndustrialOrLater(civ) && getDesiredFaithGreatPerson(civ) != null -> FaithPurchase.GreatPerson
            !tooManyMissionaries && faithBuilding != null && !areAllOurCitiesWithFaithBuilding(civ, religion, faithBuilding, includePuppets = true) ->
                FaithPurchase.MissionaryForPuppets
            !tooManyMissionaries && haveNearbyConversionTarget(civ, religion, canIncludeReligionStarter = false) ->
                FaithPurchase.MissionaryForCivsWithoutReligion
            !haveEnoughInquisitors(civ, religion) -> FaithPurchase.Inquisitor
            !tooManyMissionaries && haveNearbyConversionTarget(civ, religion, canIncludeReligionStarter = true) ->
                FaithPurchase.MissionaryForReligionFounders
            else -> FaithPurchase.OtherFaithBuilding
        }
    }

    /** CvReligionAI::AreAllOurCitiesConverted */
    private fun areAllOurCitiesConverted(civ: Civilization, religion: Religion, includePuppets: Boolean): Boolean =
        civ.cities.none { majorityReligionName(it) != religion.name && (includePuppets || !it.isPuppet) }

    /** CvReligionAI::AreAllOurCitiesHaveFaithBuilding: every city following [religion] has [building] */
    private fun areAllOurCitiesWithFaithBuilding(civ: Civilization, religion: Religion, building: Building, includePuppets: Boolean): Boolean =
        civ.cities.none {
            majorityReligionName(it) == religion.name && (includePuppets || !it.isPuppet) && !it.cityConstructions.isBuilt(building.name)
        }

    /** CvReligionAI::FaithBuildingAvailable: the first building [religion]'s beliefs make purchasable that yields Faith, as ours */
    internal fun faithBuildingAvailable(civ: Civilization, religion: Religion): Building? =
        religion.buildingsPurchasableByBeliefs.asSequence()
            .map { civ.getEquivalentBuilding(it) }
            .firstOrNull { it.faith > 0 }

    /**
     *  CvReligionAI::HaveNearbyConversionTarget: a revealed city not of our religion within missionary moves × RELIGION_MISSIONARY_RANGE_IN_TURNS
     *  of our capital, owned by a city-state or by a major that has not founded a religion (or any major, with [canIncludeReligionStarter]),
     *  skipping majors we promised not to convert.
     */
    internal fun haveNearbyConversionTarget(civ: Civilization, religion: Religion, canIncludeReligionStarter: Boolean): Boolean {
        val missionary = missionaryUnit(civ) ?: return false
        val capital = civ.getCapital() ?: return false
        val range = missionary.movement * civ.gameInfo.ruleset.ai.define(AiDefine.RELIGION_MISSIONARY_RANGE_IN_TURNS)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
            val startedOwnReligion = if (other.isCityState) false else {
                if (agreedNotToConvert(civ, other)) continue
                religionToSpread(other) != null
            }
            if (startedOwnReligion && !canIncludeReligionStarter) continue
            for (city in other.cities)
                if (city.getCenterTile().isExplored(civ) && majorityReligionName(city) != religion.name
                    && capital.getCenterTile().aerialDistanceTo(city.getCenterTile()) <= range)
                    return true
        }
        return false
    }

    /** CvReligionAI::HaveEnoughInquisitors: one per own city whose majority is another founded religion, plus one */
    internal fun haveEnoughInquisitors(civ: Civilization, religion: Religion): Boolean {
        val needed = 1 + civ.cities.count {
            val majority = it.religion.getMajorityReligion()
            majority != null && majority.isMajorReligion() && majority != religion
        }
        val inquisitors = civ.units.getCivUnits().count { it.baseUnit.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals) && it.religion == religion.name }
        return inquisitors >= needed
    }
    //endregion

    //region Where to buy (CvReligionAI::Buy*, CvReligionAIHelpers)

    private fun canPurchase(city: City, construction: INonPerpetualConstruction): Boolean {
        val cost = construction.getStatBuyCost(city, Stat.Faith) ?: return false
        return city.cityConstructions.isConstructionPurchaseAllowed(construction, Stat.Faith, cost)
    }

    /** The Buy* functions buy only if the price in the capital fits the stored faith */
    private fun capitalCanAfford(civ: Civilization, construction: INonPerpetualConstruction): Boolean {
        val capital = civ.getCapital() ?: return false
        val cost = construction.getStatBuyCost(capital, Stat.Faith) ?: return true
        return cost <= civ.religionManager.storedFaith
    }

    private fun purchase(city: City, construction: INonPerpetualConstruction) {
        city.cityConstructions.purchaseConstruction(construction, -1, true, Stat.Faith)
    }

    /** CvReligionAI::BuyMissionary / BuyInquisitor */
    private fun buyReligiousUnit(civ: Civilization, unit: BaseUnit?) {
        if (unit == null || !capitalCanAfford(civ, unit)) return
        val city = bestCityForFaithUnit(civ, unit, religionToSpread(civ)) ?: return
        purchase(city, unit)
    }

    /** CvReligionAI::BuyGreatPerson */
    private fun buyGreatPerson(civ: Civilization, unit: BaseUnit) {
        if (!capitalCanAfford(civ, unit)) return
        val city = bestCityForFaithUnit(civ, unit, null) ?: return
        purchase(city, unit)
    }

    /** CvReligionAI::BuyFaithBuilding */
    private fun buyFaithBuilding(civ: Civilization, religion: Religion, building: Building) {
        if (!capitalCanAfford(civ, building)) return
        val city = bestCityForFaithBuilding(civ, building, religion) ?: return
        purchase(city, building)
    }

    /**
     *  CvReligionAIHelpers::GetBestCityFaithUnitPurchase: a Great Person in the first city that can buy it; a religious unit (one that spreads
     *  religion or removes heresy) first in a city of our religion that gives extra spreads (Great Mosque of Djenne), then in our holy city
     *  if it still follows our religion, then in any other city of our religion.
     */
    internal fun bestCityForFaithUnit(civ: Civilization, unit: BaseUnit, religion: Religion?): City? {
        val religious = unit.hasUnique(UniqueType.CanSpreadReligion) || unit.hasUnique(UniqueType.CanRemoveHeresy, GameContext.IgnoreConditionals)
        if (!religious && unit.isGreatPerson) return civ.cities.firstOrNull { canPurchase(it, unit) }

        var holyCity: City? = null
        if (religious && religion != null) {
            if (unit.hasUnique(UniqueType.CanSpreadReligion)) {
                val ruleset = civ.gameInfo.ruleset
                civ.cities.firstOrNull { city ->
                    majorityReligionName(city) == religion.name && canPurchase(city, unit)
                        && city.getMatchingUniques(UniqueType.UnitStartingPromotions).any {
                            ruleset.unitPromotions[it.params[2]]?.hasUnique(UniqueType.CanSpreadReligion) == true
                        }
                }?.let { return it }
            }
            holyCity = civ.cities.firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
            if (holyCity != null && majorityReligionName(holyCity) == religion.name && canPurchase(holyCity, unit)) return holyCity
        }
        return civ.cities.firstOrNull { it != holyCity && majorityReligionName(it) == religion?.name && canPurchase(it, unit) }
    }

    /** CvReligionAIHelpers::GetBestCityFaithBuildingPurchase: our holy city first, then any city that can buy it */
    private fun bestCityForFaithBuilding(civ: Civilization, building: Building, religion: Religion): City? {
        val holyCity = civ.cities.firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
        if (holyCity != null && canPurchase(holyCity, building)) return holyCity
        return civ.cities.firstOrNull { it != holyCity && canPurchase(it, building) }
    }

    /** CvReligionAI::BuyAnyAvailableFaithBuilding: in the first city whose majority religion has a faith building we can buy there */
    private fun findAnyAvailableFaithBuilding(civ: Civilization): Pair<City, Building>? {
        for (city in civ.cities) {
            val majority = city.religion.getMajorityReligion() ?: continue
            if (!majority.isMajorReligion()) continue
            val building = faithBuildingAvailable(civ, majority) ?: continue
            if (canPurchase(city, building)) return city to building
        }
        return null
    }

    private fun buyAnyAvailableFaithBuilding(civ: Civilization) {
        val (city, building) = findAnyAvailableFaithBuilding(civ) ?: return
        purchase(city, building)
    }

    /**
     *  CvReligionAI::CanBuyNonFaithBuilding / BuyAnyAvailableNonFaithBuilding: the first of our buildings that yields no Faith and that a city
     *  (in city order) can buy with Faith now (e.g. Jesuit Education). Candidates are the buildings a Faith purchase unique of the city names,
     *  or that are purchasable with Faith by themselves.
     */
    private fun findNonFaithBuildingPurchase(civ: Civilization): Pair<City, Building>? {
        val ruleset = civ.gameInfo.ruleset
        val ourBuildings = ruleset.buildings.values.filter { it.faith == 0f && civ.getEquivalentBuilding(it) == it }
        for (city in civ.cities) {
            val state = city.state
            val filters = ArrayList<String>()
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsWithStat, state))
                if (unique.params[1] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsByProductionCost, state))
                if (unique.params[1] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsForAmountStat, state))
                if (unique.params[2] == Stat.Faith.name) filters.add(unique.params[0])
            for (unique in city.getMatchingUniques(UniqueType.BuyBuildingsIncreasingCost, state))
                if (unique.params[2] == Stat.Faith.name) filters.add(unique.params[0])
            for (building in ourBuildings) {
                val candidate = filters.any { building.matchesFilter(it, state) }
                    || building.canBePurchasedWithStatReasons(null, Stat.Faith) == PurchaseReason.UniqueAllowed
                if (candidate && canPurchase(city, building)) return city to building
            }
        }
        return null
    }
    //endregion

    //region Great People for Faith (CvReligionAI::GetDesiredFaithGreatPerson)

    /**
     *  CvReligionAI::GetDesiredFaithGreatPerson: among the Great People this civilization can buy with Faith in its capital, the best by
     *  - Prophet (with a founded religion): 1500 if a city needs one critically, 750 if any city is worth converting; ÷ (1 + prophets earned)
     *  - Writer, Artist, Musician: 1000 with the Culture grand strategy, else 400
     *  - Scientist: 1000 (the original computes 1000 for Spaceship / 400 otherwise, then overwrites it with 1000)
     *  - Merchant: 1000 with the United Nations grand strategy, else 400
     *  - Engineer: 2000 while ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH is active, else 500
     *  - General / Admiral: 1000 with the Conquest grand strategy on a land / naval map, else 400; also ÷ the ones we have
     *  each ÷ (1 + that Great Person bought with Faith). Null if none scores above 0.
     */
    fun getDesiredFaithGreatPerson(civ: Civilization): BaseUnit? = timeThis("AI.DesiredFaithGreatPerson") {
        val capital = civ.getCapital() ?: return@timeThis null
        val ai = civ.ai
        val culture = ai.isGoingForCultureVictory()
        val bought = civ.civConstructions.boughtItemsWithIncreasingPrice
        var best: BaseUnit? = null
        var bestScore = 0
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!unit.isGreatPerson || !unit.hasUnique(UniqueType.Unbuildable, GameContext.IgnoreConditionals)) continue
            if (civ.getEquivalentUnit(unit) != unit) continue
            if (!unit.canBePurchasedWithStat(capital, Stat.Faith)) continue
            val fromFaith = bought[unit.name]
            val score = when (unitClass(unit)) {
                "Great Prophet" -> {
                    if (religionToSpread(civ) == null) 0
                    else {
                        val base = when {
                            chooseProphetConversionCity(civ, true) != null -> 1500
                            chooseProphetConversionCity(civ, false) != null -> 750
                            else -> 0
                        }
                        base / (1 + civ.religionManager.greatProphetsEarned())
                    }
                }
                "Great Writer", "Great Artist", "Great Musician" -> (if (culture) 1000 else 400) / (1 + fromFaith)
                "Great Scientist" -> 1000 / (1 + fromFaith)
                "Great Merchant" -> (if (ai.isGoingForDiploVictory()) 1000 else 400) / (1 + fromFaith)
                "Great Engineer" ->
                    (if (ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_GS_SPACESHIP_HOMESTRETCH")) 2000 else 500) / (1 + fromFaith)
                "Great General" -> (if (ai.isGoingForWorldConquest() && !isNavalMap(civ)) 1000 else 400) /
                    (1 + fromFaith + civ.units.getCivUnits().count { unitClass(it.baseUnit) == "Great General" })
                "Great Admiral" -> (if (ai.isGoingForWorldConquest() && isNavalMap(civ)) 1000 else 400) /
                    (1 + fromFaith + civ.units.getCivUnits().count { unitClass(it.baseUnit) == "Great Admiral" })
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = unit
            }
        }
        best
    }
    //endregion

    //region Strategic targets of religious units

    /**
     *  CvReligionAI::ChooseProphetConversionCity: the city a Great Prophet should convert, scoring above 50.
     *  Our cities: our lost holy city at once; else heretics × 100 / (distance to the holy city + 1), × 3 if another religion holds the majority
     *  (0 otherwise when [onlyBetterThanEnhancingReligion]). Then, unless [onlyBetterThanEnhancingReligion], other players' cities not defended
     *  against our religion: heretics × 50 / (distance + 1), × 3 if not of our religion, ÷ 2 for the owner's holy city, × 2 if the majority
     *  religion's founder does not own the city.
     */
    fun chooseProphetConversionCity(civ: Civilization, onlyBetterThanEnhancingReligion: Boolean): City? =
        timeThis("AI.ChooseProphetConversionCity") {
            val religion = religionToSpread(civ) ?: return@timeThis null
            val holyCity = civ.gameInfo.getCities().firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name }
                ?: return@timeThis null
            val holyTile = holyCity.getCenterTile()
            var bestScore = 50 // not 0, so that prophets do not always pick something
            var best: City? = null

            for (city in civ.cities) {
                val majority = city.religion.getMajorityReligion()
                if (city == holyCity && majority != religion) return@timeThis holyCity
                var score = followersOfOtherReligions(city, religion.name) * 100 / (city.getCenterTile().aerialDistanceTo(holyTile) + 1)
                if (majority != religion && majority != null && majority.isMajorReligion()) score *= 3
                else if (onlyBetterThanEnhancingReligion) score = 0
                if (score > bestScore) {
                    bestScore = score
                    best = city
                }
            }
            if (onlyBetterThanEnhancingReligion) return@timeThis best

            for (other in civ.gameInfo.civilizations) {
                if (other == civ || !other.isAlive() || other.isBarbarian || other.isSpectator) continue
                val ownersReligion = religionToSpread(other)
                for (city in other.cities) {
                    if (city.religion.isProtectedByInquisitor(religion.name)) continue
                    val majority = city.religion.getMajorityReligion()
                    var score = followersOfOtherReligions(city, religion.name) * 50 / (city.getCenterTile().aerialDistanceTo(holyTile) + 1)
                    if (majority != religion) score *= 3
                    if (ownersReligion != null && city.religion.religionThisIsTheHolyCityOf == ownersReligion.name) score /= 2
                    if (majority != null && majority.foundingCiv != other) score *= 2
                    if (score > bestScore) {
                        bestScore = score
                        best = city
                    }
                }
            }
            best
        }

    /** CvReligionAI::ShouldBecomeNewMajority: [newPressure] more would give [religion] more than half of the city's pressure */
    fun shouldBecomeNewMajority(city: City, religion: String, newPressure: Int): Boolean {
        val pressures = city.religion.getPressures()
        val total = pressures.sumValues() + newPressure
        return (pressures[religion] + newPressure) * 2 > total
    }

    /**
     *  CvReligionAI::ScoreCityForMissionary: 0 if unrevealed, already of our religion, or a major we promised not to convert; else 100,
     *  × 2 if the visit makes our religion the majority, × 5 for our own city or × 3 for an owner without a religion of its own,
     *  − the distance, × 2 if not at war with the owner, ÷ 2 for the owner's holy city.
     */
    fun scoreCityForMissionary(civ: Civilization, city: City, unit: MapUnit, religion: Religion): Int {
        if (!city.getCenterTile().isExplored(civ)) return 0
        if (majorityReligionName(city) == religion.name) return 0
        var score = 100
        if (shouldBecomeNewMajority(city, religion.name, UnitActionsReligion.getPressureAddedFromSpread(unit))) score *= 2
        val owner = city.civ
        if (!owner.isCityState && owner != civ && agreedNotToConvert(civ, owner)) return 0
        val ownersReligion = religionToSpread(owner)
        if (owner == civ) score *= 5
        else if (ownersReligion == null) score *= 3
        score -= unit.getTile().aerialDistanceTo(city.getCenterTile())
        if (!civ.isAtWarWith(owner)) score *= 2
        if (ownersReligion != null && city.religion.religionThisIsTheHolyCityOf == ownersReligion.name) score /= 2
        return score
    }

    /**
     *  CvCityReligions::IsDefendedAgainstSpread for the missionary's choice: an inquisitor of another religion in or next to the city.
     *  For a foreign city only the units we can see count (Unciv's AI does not read hidden units).
     */
    private fun isDefendedAgainstSpread(civ: Civilization, city: City, religion: String): Boolean {
        if (city.civ == civ) return city.religion.isProtectedByInquisitor(religion)
        return city.getCenterTile().getTilesInDistance(1).any { tile ->
            tile in civ.viewableTiles && tile.getUnits().any {
                it.religion != null && it.religion != religion && it.hasUnique(UniqueType.PreventSpreadingReligion)
            }
        }
    }

    /** CvReligionAI::ChooseMissionaryTargetCity: the best-scoring city of any player not defended against our religion */
    fun chooseMissionaryTargetCity(civ: Civilization, unit: MapUnit): City? = timeThis("AI.ChooseMissionaryTarget") {
        val religion = religionToSpread(civ) ?: return@timeThis null
        var bestScore = 0
        var best: City? = null
        for (other in civ.gameInfo.civilizations) {
            if (!other.isAlive()) continue
            for (city in other.cities) {
                if (isDefendedAgainstSpread(civ, city, religion.name)) continue
                val score = scoreCityForMissionary(civ, city, unit, religion)
                if (score > bestScore) {
                    bestScore = score
                    best = city
                }
            }
        }
        best
    }

    /** CvReligionAI::ChooseInquisitorTargetCity / ScoreCityForInquisitor: our city not of our religion, scoring 100 − distance */
    internal fun chooseInquisitorTargetCity(civ: Civilization, unit: MapUnit): City? = timeThis("AI.ChooseInquisitorTarget") {
        val religion = religionToSpread(civ) ?: return@timeThis null
        var bestScore = 0
        var best: City? = null
        for (city in civ.cities) {
            if (majorityReligionName(city) == religion.name) continue
            val score = 100 - unit.getTile().aerialDistanceTo(city.getCenterTile())
            if (score > bestScore) {
                bestScore = score
                best = city
            }
        }
        best
    }
    //endregion

    //region Belief choices (CvReligionAI::Choose*Belief, CvGameReligions::GetReligionToFound)

    internal fun chooseReligiousBeliefs(civ: Civilization) {
        choosePantheon(civ)
        foundReligion(civ)
        enhanceReligion(civ)
        chooseFreeBeliefs(civ)
    }

    /** CvReligionAI::ChoosePantheonBelief (from CvGameReligions::DoPlayerTurn) */
    private fun choosePantheon(civ: Civilization) {
        if (!civ.religionManager.canFoundOrExpandPantheon()) return
        val pantheon = chooseBeliefs(civ, Counter<BeliefType>().apply { add(BeliefType.Pantheon, 1) }).firstOrNull() ?: return
        civ.religionManager.chooseBeliefs(listOf(pantheon), useFreeBeliefs = civ.religionManager.usingFreeBeliefs())
    }

    /** CvUnit::DoFoundReligion: the religion from CvGameReligions::GetReligionToFound, then the beliefs */
    private fun foundReligion(civ: Civilization) {
        if (civ.religionManager.religionState != ReligionState.FoundingReligion) return
        val religionName = religionToFound(civ) ?: return
        civ.religionManager.foundReligion(religionName, religionName)
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.getBeliefsToChooseAtFounding()).toList())
    }

    /** CvGameReligions::GetReligionToFound: our nation's religion if still free, else the first free one no civilization in the game
     *  prefers, else the first free one */
    internal fun religionToFound(civ: Civilization): String? {
        val used = civ.gameInfo.religions.keys
        val available = civ.gameInfo.ruleset.religions.filter { it !in used }
        civ.nation.favoredReligion?.let { if (it in available) return it }
        val preferred = civ.gameInfo.civilizations.mapNotNullTo(HashSet()) { it.nation.favoredReligion }
        return available.firstOrNull { it !in preferred } ?: available.firstOrNull()
    }

    /** CvUnit::DoEnhanceReligion: a follower and an enhancer belief */
    private fun enhanceReligion(civ: Civilization) {
        if (civ.religionManager.religionState != ReligionState.EnhancingReligion) return
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.getBeliefsToChooseAtEnhancing()).toList())
    }

    /** Free beliefs, among them the Reformation belief (CvReligionAI::ChooseReformationBelief from CvGameReligions::DoPlayerTurn) */
    private fun chooseFreeBeliefs(civ: Civilization) {
        if (!civ.religionManager.hasFreeBeliefs()) return
        civ.religionManager.chooseBeliefs(chooseBeliefs(civ, civ.religionManager.freeBeliefsAsEnums()).toList(), useFreeBeliefs = true)
    }

    /** One belief per slot of [beliefsToChoose], each by [chooseBelief]; a bonus belief (BeliefType.Any) excludes the ones chosen here */
    private fun chooseBeliefs(civ: Civilization, beliefsToChoose: Counter<BeliefType>): LinkedHashSet<Belief> {
        val chosen = LinkedHashSet<Belief>()
        val context = BeliefScoringContext(civ)
        for (type in BeliefType.entries) {
            if (type == BeliefType.None) continue
            repeat(beliefsToChoose[type]) { index ->
                chosen.add(chooseBelief(civ, type, chosen, context, index) ?: return@repeat)
            }
        }
        return chosen
    }

    /** The beliefs of [type] still available to [civ]: in no religion, not chosen in this batch, and allowed by the ruleset */
    internal fun availableBeliefs(civ: Civilization, type: BeliefType, exclude: Set<Belief> = emptySet()): List<Belief> =
        civ.gameInfo.ruleset.beliefs.values.filter {
            type.includes(it.type)
                && it !in exclude
                && civ.religionManager.getReligionWithBelief(it) == null
                && it.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                    .none { unique -> !unique.conditionalsApply(civ.state) }
                && it.getMatchingUniques(UniqueType.Unavailable, civ.state).none()
        }

    /**
     *  CvReligionAI::ChoosePantheonBelief / ChooseFounderBelief / ChooseFollowerBelief / ChooseEnhancerBelief / ChooseBonusBelief /
     *  ChooseReformationBelief: [BeliefScoring.scoreBelief] for each available belief (× the ruleset's AI weight uniques for the belief),
     *  those above 0 roll among the top 3 weighted by score.
     */
    private fun chooseBelief(civ: Civilization, type: BeliefType, exclude: Set<Belief>, context: BeliefScoringContext, index: Int): Belief? {
        val gameContext = GameContext(civ)
        val scored = availableBeliefs(civ, type, exclude).mapNotNull { belief ->
            val score = (BeliefScoring.scoreBelief(belief, context) * belief.getWeightForAiDecision(gameContext)).toInt()
            if (score > 0) belief to score else null
        }
        return chooseFromTopChoices(scored, 3, civ.ai.random("ReligionAutomation.chooseBelief.${type.name}.$index"))
    }

    /** CvWeightedVector::ChooseFromTopChoices after SortItems: a roll up to the summed weight of the [numChoices] heaviest, ties in input order */
    fun <T> chooseFromTopChoices(choices: List<Pair<T, Int>>, numChoices: Int, random: Random): T? {
        if (choices.isEmpty()) return null
        val top = choices.sortedByDescending { it.second }.take(numChoices)
        var roll = random.nextInt(top.sumOf { it.second })
        for ((choice, weight) in top) {
            roll -= weight
            if (roll < 0) return choice
        }
        return top.last().first
    }
    //endregion
}
