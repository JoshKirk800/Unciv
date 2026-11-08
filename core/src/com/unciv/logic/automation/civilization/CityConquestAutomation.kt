package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.unique.UniqueType

/** The player-level city decisions of CvPlayerAI: what to do with a conquered city, and which puppet to annex */
object CityConquestAutomation {

    /** What [conquerCity] decided */
    enum class ConquestChoice { Liberate, Raze, Puppet, Annex }

    /**
     *  CvPlayerAI::AI_conquerCity, for [conqueror] that has just taken [city] (still owned by its previous owner).
     *
     *  Liberation, when the city's founder is neither the previous owner nor us and is alive or may be brought back
     *  (CvPlayer::CanLiberatePlayerCity; Unciv keeps no record of who destroyed a civilization, so any dead founder may be; a city gained
 *  by diplomatic marriage cannot be liberated in Unciv):
     *  - a city-state: our approach to it is Friendly or Protective, or we go for a diplomatic victory (CvDiplomacyAI::DoPossibleMinorLiberation);
     *  - a living major: we have a defensive pact with it and both of us are at war with the previous owner, or we have a declaration of
     *    friendship with it and go for a diplomatic victory;
     *  - a dead major that was not human: we go for a diplomatic victory.
     *  Otherwise it razes the city if it can (CvPlayer::canRaze: not one we founded, and Unciv's raze rules) and our empire is unhappy,
     *  or the map tells the AI to raze (map hint 2: no Unciv map gives it), or it razes faster (the Huns) and has at least 3 + turn/100 cities.
     *  Otherwise it puppets the city, unless we founded it and may annex (Venice may not), then it annexes.
     */
    fun chooseConquest(conqueror: Civilization, city: City): ConquestChoice {
        val founder = city.foundingCivObject
        val previousOwner = city.civ
        if (founder != null && founder != previousOwner && founder != conqueror && !city.hasDiplomaticMarriage()) {
            if (founder.isCityState) {
                val approach = conqueror.ai.minorCivApproaches[founder.civID] ?: MinorCivApproach.Ignore
                if (approach == MinorCivApproach.Friendly || approach == MinorCivApproach.Protective || conqueror.ai.isGoingForDiploVictory())
                    return ConquestChoice.Liberate
            } else if (founder.isAlive()) {
                val diplomacy = conqueror.getDiplomacyManager(founder)
                val defensivePact = diplomacy?.diplomaticStatus == DiplomaticStatus.DefensivePact
                if (defensivePact && conqueror.isAtWarWith(previousOwner) && founder.isAtWarWith(previousOwner)) return ConquestChoice.Liberate
                if (diplomacy?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true && conqueror.ai.isGoingForDiploVictory())
                    return ConquestChoice.Liberate
            } else if (!founder.isHuman() && conqueror.ai.isGoingForDiploVictory()) return ConquestChoice.Liberate
        }

        val canRaze = founder != conqueror && city.canBeDestroyed(justCaptured = true)
        if (canRaze) {
            val razesFaster = conqueror.hasUnique(UniqueType.CitiesAreRazedXTimesFaster)
            if (conqueror.getHappiness() < 0 || SettleAreas.aiMapHint(conqueror.gameInfo) and 2 != 0
                || razesFaster && conqueror.cities.size >= 3 + conqueror.gameInfo.turns / 100)
                return ConquestChoice.Raze
        }

        return if (founder != conqueror || conqueror.hasUnique(UniqueType.MayNotAnnexCities)) ConquestChoice.Puppet
        else ConquestChoice.Annex
    }

    /** Carries out [chooseConquest] for [conqueror] on the just conquered [city] */
    fun conquerCity(conqueror: Civilization, city: City) {
        when (chooseConquest(conqueror, city)) {
            ConquestChoice.Liberate -> city.liberateCity(conqueror)
            ConquestChoice.Raze -> {
                city.puppetCity(conqueror)
                if (!conqueror.hasUnique(UniqueType.MayNotAnnexCities)) city.annexCity()
                city.isBeingRazed = true
            }
            ConquestChoice.Puppet -> city.puppetCity(conqueror)
            ConquestChoice.Annex -> {
                city.puppetCity(conqueror)
                city.annexCity()
            }
        }
    }

    /**
     *  CvPlayerAI::AI_considerAnnex, once per turn: not when the empire is unhappy, not when going for a culture victory, not when it may
     *  not annex (Venice). A puppeted capital is annexed first. Otherwise, among the cities sorted by production (highest first), the first
     *  puppet in the top NORMAL_ANNEX share (AGGRESSIVE_ANNEX when a policy boosts the production of the courthouse building) is annexed,
     *  unless it is in resistance. At most one city per turn.
     *  @return the city annexed, null if none
     */
    fun considerAnnex(civ: Civilization): City? {
        if (civ.getHappiness() < 0) return null
        if (civ.ai.isGoingForCultureVictory()) return null
        if (civ.hasUnique(UniqueType.MayNotAnnexCities)) return null

        val capital = civ.getCapital(firstCityIfNoCapital = false)
        if (capital != null && capital.isPuppet) {
            capital.annexCity()
            return capital
        }

        val rules = civ.gameInfo.ruleset.ai
        val byProduction = civ.cities.sortedByDescending { it.cityStats.currentCityStats.production }
        val cutoffShare = if (courthouseProductionBoosted(civ)) rules.defineFloat(AiDefine.AGGRESSIVE_ANNEX)
            else rules.defineFloat(AiDefine.NORMAL_ANNEX)
        val cutoff = (byProduction.size * cutoffShare).toInt()
        val target = byProduction.take(cutoff).firstOrNull { it.isPuppet } ?: return null
        if (target.isInResistance()) return null
        target.annexCity()
        return target
    }

    /** CvPlayerPolicies::GetBuildingClassProductionModifier of the first building that removes occupied unhappiness (the courthouse) > 0 */
    private fun courthouseProductionBoosted(civ: Civilization): Boolean {
        val courthouse = civ.gameInfo.ruleset.buildings.values.firstOrNull { it.hasUnique(UniqueType.RemovesAnnexUnhappiness) } ?: return false
        return civ.getMatchingUniques(UniqueType.PercentProductionBuildings, civ.state).any {
            it.params[0].toInt() > 0 && courthouse.matchesFilter(it.params[1], civ.state)
        }
    }
}
