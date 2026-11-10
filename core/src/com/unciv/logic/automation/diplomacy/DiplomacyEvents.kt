package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  What CvDiplomacyAI remembers of events in the game: the calls other game code makes into it (CvTeam::declareWar, CvTeam::makePeace,
 *  CvPlayer::acquireCity, CvPlayer::DoLiberatePlayer, CvUnit::kill, CvCity's wonder completion, CvMinorCivAI bullying, nukes, culture bombs,
 *  espionage, religion). Called from the Unciv game code at the same moments.
 */
object DiplomacyEvents {

    //region Contact

    /**
     *  CvDiplomacyAI::DoFirstContact / DoFirstContactInitRelationship: [civ] met [other]. Strengths, aggressive postures and the target value
     *  are assessed first, so the opinion and the first approach are chosen with them known.
     */
    fun onMeet(civ: Civilization, other: Civilization) {
        if (!civ.isMajorCiv || other.isSpectator) return
        val memory = civ.ai.diplomacy.memoryOf(other)
        memory.turnMet = civ.gameInfo.turns
        if (!other.isMajorCiv || other.cities.isEmpty() || civ.cities.isEmpty()) return
        val rules = civ.gameInfo.ruleset.ai
        WarStateAi.updateOnContact(civ, other)
        DiplomacyTurn.updateEconomicStrength(civ, other, WarStateAi.economicMight(civ), rules)
        DisputeAi.updateMilitaryPosture(civ, other, rules)
        DisputeAi.updateExpansionPosture(civ, other, rules)
        OpinionAi.updateOpinion(civ, other, rules)
        ApproachAi.initApproach(civ, other, rules)
    }
    //endregion

    //region War and peace

    /**
     *  CvTeam::declareWar: the victim's DoSomeoneDeclaredWarOnMe, then every major that knows the declarer runs DoPlayerDeclaredWarOnSomeone and
     *  (not for defensive pacts) counts the attack in its warmonger amounts (ChangeOtherPlayerNumMinorsAttacked / NumMajorsAttacked).
     *  Call after the war state is set and before the friendship flags are removed.
     */
    fun onWarDeclared(declarer: Civilization, victim: Civilization, isDefensivePact: Boolean) {
        val gameInfo = declarer.gameInfo
        val rules = gameInfo.ruleset.ai
        // DoSomeoneDeclaredWarOnMe: "shocked and disappointed" until the next update
        if (victim.isMajorCiv && declarer.isMajorCiv) victim.ai.diplomacy.memoryOf(declarer).warGoal = WarGoal.Peace
        if (!declarer.isMajorCiv) return
        for (observer in gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == declarer || !observer.isAlive() || !observer.knows(declarer)) continue
            playerDeclaredWarOnSomeone(observer, declarer, victim)
            if (isDefensivePact) continue
            val memory = observer.ai.diplomacy.memoryOf(declarer)
            if (victim.isCityState) memory.warmongerAmount += rules.define(AiDefine.WARMONGER_THREAT_MINOR_ATTACKED_WEIGHT)
            // Not counted if the observer is at war with the victim too
            else if (victim.isMajorCiv && (observer == victim || !observer.isAtWarWith(victim)))
                memory.warmongerAmount += rules.define(AiDefine.WARMONGER_THREAT_MAJOR_ATTACKED_WEIGHT)
        }
        // DoPlayerDeclaredWarOnSomeone runs for the declarer too (ePlayer == me)
        playerDeclaredWarOnSomeone(declarer, declarer, victim)
    }

    /** CvDiplomacyAI::DoPlayerDeclaredWarOnSomeone, from [me]'s point of view */
    private fun playerDeclaredWarOnSomeone(me: Civilization, declarer: Civilization, victim: Civilization) {
        val ai = me.ai.diplomacy
        if (me == declarer || me == victim) {
            val them = if (me == declarer) victim else declarer
            val memory = ai.memoryOf(them)
            memory.numWarsFought++
            var wasFriends = false
            if (them.isMajorCiv) {
                if (OpinionAi.isDoFAccepted(me, them)) {
                    wasFriends = true
                    memory.doFCounter = -666
                }
                // Coop wars planned with the one we now fight are off
                for (entry in memory.coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
                    entry.state = null
                    entry.counter = -666
                }
            }
            if (me == victim) {
                val rules = me.gameInfo.ruleset.ai
                if (wasFriends) {
                    memory.friendDeclaredWarOnUs = true
                    memory.declaredWarOnFriendValue += rules.define(AiDefine.OPINION_WEIGHT_WAR_FRIEND_EACH) * DiplomacyTurn.opinionDurationPercent(me)
                }
                if (declarer.isMajorCiv) {
                    memory.approach = MajorCivApproach.War
                    memory.warFace = WarFace.Neutral
                }
                if (memory.madeMilitaryPromise) memory.brokenMilitaryPromise = true
                // Clear what made us like them
                memory.numCiviliansReturned = 0
                memory.recentTradeValue = 0
                memory.commonFoeValue = 0
                if (memory.recentAssistValue < 0) memory.recentAssistValue = 0
                declarer.ai.diplomacy.memoryOf(me).doFCounter = -1
            }
            return
        }
        // Someone else's war: did they attack a city-state we protect?
        if (victim.isCityState && WarStateAi.isPlayerValid(me, victim) && me in victim.cityStateFunctions.getProtectorCivs()) {
            val memory = ai.memoryOf(declarer)
            memory.turnsSinceAttackedProtectedMinor = 0
            memory.protectedMinorAttacked = victim.civID
            memory.protectedMinorAttackedUnseen = true
            memory.numProtectedMinorsAttacked++
        }
    }

    /** CvTeam::makePeace: both sides run DoWeMadePeaceWithSomeone and remember the turn of the treaty */
    fun onPeace(civ: Civilization, other: Civilization) {
        weMadePeaceWith(civ, other)
        weMadePeaceWith(other, civ)
    }

    /** CvDiplomacyAI::DoWeMadePeaceWithSomeone */
    private fun weMadePeaceWith(civ: Civilization, other: Civilization) {
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(other)
        memory.turnMadePeace = civ.gameInfo.turns
        // CvDiplomacyAI::ResetSentAttackProtectedMinorTaunts: at peace with a city-state the taunts about it start over
        if (other.isCityState) for (tauntMemory in ai.memories.values) tauntMemory.sentAttackProtectedMinorTaunts.remove(other.civID)
        memory.musteringForAttack = false
        if (!other.isMajorCiv) return
        // Coop wars against them are over
        for (partnerMemory in ai.memories.values) partnerMemory.coopWars[other.civID]?.state = null
        // Bias against another war right away
        if (memory.approach == MajorCivApproach.War) memory.approach = MajorCivApproach.Neutral
    }
    //endregion

    //region War damage, conquest, liberation

    /**
     *  CvDiplomacyAI::ChangeWarValueLost: [loser] lost [value] to [winner]; the majors at war with the loser like the winner for it (common
     *  foe). A city-state's losses count too (its own record of them is not read by anything): the majors at war with it like whoever hurts it.
     */
    fun changeWarValueLost(loser: Civilization, winner: Civilization, value: Int) {
        if (loser.isMajorCiv) loser.ai.diplomacy.memoryOf(winner).warValueLost += value
        else if (!loser.isCityState) return
        if (value <= 0) return
        for (third in loser.gameInfo.civilizations) {
            if (!third.isMajorCiv || third == winner || third == loser || !WarStateAi.isPlayerValid(loser, third)) continue
            if (loser.isAtWarWith(third)) third.ai.diplomacy.changeCommonFoeValue(winner, value)
        }
    }

    /** CvUnit::kill: a unit of [owner] killed by [killer] - the war damage both sides note */
    fun onUnitKilled(owner: Civilization, killer: Civilization, unitPower: Int) {
        if (owner.isBarbarian || killer.isBarbarian) return
        val rules = owner.gameInfo.ruleset.ai
        val value = DiplomacyTurn.warValueOfUnit(unitPower, DiplomacyTurn.powerOfStrongestBuildableLandUnit(killer), rules)
        changeWarValueLost(owner, killer, value)
        if (killer.isMajorCiv) killer.ai.diplomacy.memoryOf(owner).theirWarValueLostToUs += value
    }

    /**
     *  CvPlayer::acquireCity by conquest: the war damage (the conqueror's and the loser's view; everyone else learns it too), the loser forgets
     *  liberation credit, and the warmonger penalties (CvDiplomacyAIHelpers::ApplyWarmongerPenalties) unless the city was the conqueror's.
     *  Call before the city changes hands.
     */
    fun onCityConquered(conqueror: Civilization, loser: Civilization, city: City) {
        if (conqueror.isBarbarian || loser.isBarbarian) return
        val rules = conqueror.gameInfo.ruleset.ai
        var value = rules.define(AiDefine.WAR_DAMAGE_LEVEL_CITY_WEIGHT) +
            city.population.population * rules.define(AiDefine.WAR_DAMAGE_LEVEL_INVOLVED_CITY_POP_MULTIPLIER)
        if (city.isOriginalCapital) value = value * 3 / 2
        if (conqueror.isMajorCiv) conqueror.ai.diplomacy.memoryOf(loser).theirWarValueLostToUs += value
        changeWarValueLost(loser, conqueror, value)
        if (loser.isMajorCiv) loser.ai.diplomacy.memoryOf(conqueror).numCitiesLiberated = 0
        // The loser's city count after the loss, as the original applies the penalties once the city has changed hands
        if (conqueror.isMajorCiv && city.foundingCivObject != conqueror) applyWarmongerPenalties(conqueror, loser, loser.cities.size - 1)
    }

    /** CvDiplomacyAIHelpers::GetWarmongerOffset: 1000 × estimated cities on the map / (actual cities × cities the victim has left) */
    fun warmongerOffset(conqueror: Civilization, numCitiesRemaining: Int): Int {
        val gameInfo = conqueror.gameInfo
        val estimated = gameInfo.ruleset.ai.worldSize(gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().name).estimatedNumCities
        val actual = gameInfo.civilizations.sumOf { it.cities.size }
        return 1000 * estimated / (maxOf(actual, 1) * numCitiesRemaining)
    }

    /** CvDiplomacyAIHelpers::ApplyWarmongerPenalties: every major that knows the conqueror, halved if it is at war with the loser too */
    fun applyWarmongerPenalties(conqueror: Civilization, loser: Civilization, loserCitiesLeft: Int) {
        val offset = warmongerOffset(conqueror, maxOf(loserCitiesLeft, 1))
        for (observer in conqueror.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == conqueror || !observer.isAlive() || !observer.knows(conqueror)) continue
            val penalty = if (observer.isAtWarWith(loser)) offset / 2 else offset
            observer.ai.diplomacy.memoryOf(conqueror).warmongerAmount += penalty
        }
    }

    /**
     *  CvPlayer::DoLiberatePlayer: [liberator] gave [city] back to [receiver]. A living receiver counts the liberated city; a dead one is
     *  resurrected and forgets its grievances; the liberator's warmonger amounts go down for everyone who knows it.
     *  Call before the city changes hands, with [receiverWasAlive] the receiver's state before.
     */
    fun onCityLiberated(liberator: Civilization, receiver: Civilization, receiverWasAlive: Boolean) {
        if (receiver.isMajorCiv) {
            val memory = receiver.ai.diplomacy.memoryOf(liberator)
            if (receiverWasAlive) memory.numCitiesLiberated++
            else {
                memory.resurrectedUs = true
                memory.landDispute = DisputeLevel.None
                memory.wonderDispute = DisputeLevel.None
                memory.minorCivDispute = DisputeLevel.None
                memory.warmongerThreat = AiThreat.None
                memory.noSettleRequestEverAsked = false
                memory.stopSpyingRequestEverAsked = false
                memory.demandCounter = -1
                memory.numTimesCultureBombed = 0
                memory.negativeReligiousConversionPoints = 0
                memory.numTimesRobbed = 0
                memory.brokenMilitaryPromise = false
                memory.ignoredMilitaryPromise = false
                memory.brokenBorderPromiseValue = 0
                memory.ignoredBorderPromiseValue = 0
                memory.brokenExpansionPromiseValue = 0
                memory.ignoredExpansionPromiseValue = 0
                memory.brokenNoConvertPromise = false
                memory.ignoredNoConvertPromise = false
                memory.brokenNoDiggingPromise = false
                memory.ignoredNoDiggingPromise = false
                memory.brokenSpyPromise = false
                memory.ignoredSpyPromise = false
                memory.brokenCoopWarPromise = false
                memory.numProtectedMinorsKilled = 0
                memory.numProtectedMinorsAttacked = 0
                memory.numProtectedMinorsBullied = 0
                memory.turnsSinceSidedWithProtectedMinor = -1
                memory.friendDenouncedUs = false
                memory.friendDeclaredWarOnUs = false
                memory.numTimesNuked = 0
                receiver.getDiplomacyManager(liberator)?.let {
                    it.negativeArchaeologyPoints = 0
                    it.removeFlag(DiplomacyFlags.Denunciation)
                }
                liberator.getDiplomacyManager(receiver)?.removeFlag(DiplomacyFlags.Denunciation)
            }
        }
        // Negate the warmongering
        val offset = warmongerOffset(liberator, maxOf(receiver.cities.size + 1, 1))
        for (observer in liberator.gameInfo.civilizations) {
            if (!observer.isMajorCiv || observer == liberator || !observer.isAlive() || !observer.knows(liberator)) continue
            val memory = observer.ai.diplomacy.memoryOf(liberator)
            memory.warmongerAmount = maxOf(0, memory.warmongerAmount - offset)
        }
    }

    /**
     *  CvPlayer::acquireCity -> CvDiplomacyAI::DoPlayerKilledSomeone: [newOwner] took the last city of [oldOwner]. Only a major notifies anyone
     *  (not a city-state, not the barbarians); every other living major that has met it counts one minor or major conquered, and the
     *  protectors of a dead city-state remember the killing.
     */
    fun onLastCityTaken(newOwner: Civilization, oldOwner: Civilization) {
        if (!newOwner.isMajorCiv) return
        GrandStrategyAi.onCivDestroyed(newOwner, oldOwner)
        if (!oldOwner.isCityState) return
        for (observer in oldOwner.cityStateFunctions.getProtectorCivs()) {
            if (!observer.isMajorCiv || observer == newOwner || !observer.isAlive() || !observer.knows(newOwner) || !WarStateAi.isPlayerValid(observer, newOwner)) continue
            val memory = observer.ai.diplomacy.memoryOf(newOwner)
            memory.turnsSinceKilledProtectedMinor = 0
            memory.numProtectedMinorsKilled++
        }
    }
    //endregion

    //region Other grievances

    /** CvCity: [loser] was building [wonder] when [builder] completed it (CvDiplomacyAI::ChangeNumWondersBeatenTo) */
    fun onWonderBeaten(loser: Civilization, builder: Civilization) {
        if (loser.isMajorCiv && builder.isMajorCiv && loser != builder) loser.ai.diplomacy.memoryOf(builder).numWondersBeatenTo++
    }

    /** CvMinorCivAI::DoMajorBullied -> DoPlayerBulliedSomeone for every major that has met the bully */
    fun onCityStateBullied(bully: Civilization, cityState: Civilization) {
        for (protector in cityState.cityStateFunctions.getProtectorCivs()) {
            if (!protector.isMajorCiv || protector == bully || !WarStateAi.isPlayerValid(protector, bully)) continue
            val memory = protector.ai.diplomacy.memoryOf(bully)
            memory.numProtectedMinorsBullied++
            memory.turnsSinceBulliedProtectedMinor = 0
            memory.protectedMinorBullied = cityState.civID
            memory.protectedMinorBulliedTurn = bully.gameInfo.turns
        }
        // CvMinorCivAI::m_aiTurnLastBulliedByMajor, as the bully's own memory of the city-state
        bully.ai.diplomacy.memoryOf(cityState).turnLastBulliedByUs = bully.gameInfo.turns
    }

    /** DoFromUIDiploEvent: [protector] sided with its city-state against [bully] */
    fun onSidedWithProtectedMinor(protector: Civilization, bully: Civilization) {
        if (bully.isMajorCiv) bully.ai.diplomacy.memoryOf(protector).turnsSinceSidedWithProtectedMinor = 0
    }

    /** CvUnitCombat nuke damage: [victim] was nuked by [attacker] */
    fun onNuked(victim: Civilization, attacker: Civilization) {
        if (victim.isMajorCiv && victim != attacker) victim.ai.diplomacy.memoryOf(attacker).numTimesNuked++
    }

    /** CvUnit culture bomb (citadel and similar): [owner] lost tiles to [bomber] */
    fun onCultureBombed(owner: Civilization, bomber: Civilization) {
        if (owner.isMajorCiv && owner != bomber) owner.ai.diplomacy.memoryOf(bomber).numTimesCultureBombed++
    }

    /** CvPlayerEspionage: a spy of [thief] stealing from [victim] was identified or killed */
    fun onRobbed(victim: Civilization, thief: Civilization) {
        if (victim.isMajorCiv) victim.ai.diplomacy.memoryOf(thief).numTimesRobbed++
    }

    /** CvPlayerEspionage::ProcessSpy: a spy of [thief] stealing from [victim] was identified (the victim's CvEspionageAI::m_aiTurnLastSpyCaught) */
    fun onSpyCaught(victim: Civilization, thief: Civilization) {
        if (victim.isMajorCiv && thief.isMajorCiv) victim.ai.diplomacy.memoryOf(thief).turnLastSpyCaught = victim.gameInfo.turns
    }

    /** CvPlayerEspionage::ProcessSpy: a spy of [thief] was killed in a city of [victim] (m_aiTurnLastSpyKilled and m_aiNumSpiesKilled of the victim, m_aiTurnLastSpyDied of the thief) */
    fun onSpyKilled(victim: Civilization, thief: Civilization) {
        if (!victim.isMajorCiv || !thief.isMajorCiv) return
        val memory = victim.ai.diplomacy.memoryOf(thief)
        memory.turnLastSpyKilled = victim.gameInfo.turns
        memory.numSpiesKilled++
        thief.ai.diplomacy.memoryOf(victim).turnLastSpyDied = victim.gameInfo.turns
    }

    /**
     *  CvCityReligions: [responsible]'s religion [newMajority] took a city of [owner] who founded another religion: 1 point if the owner's religion was not
     *  the majority there, 25 for the owner's holy city, 3 otherwise (RELIGION_DIPLO_HIT_*).
     */
    fun onCityConverted(owner: Civilization, responsible: Civilization, city: City, oldMajority: String?, newMajority: String) {
        if (!owner.isMajorCiv || owner == responsible) return
        val ownReligion = owner.religionManager.religion?.name ?: return
        if (newMajority == ownReligion) return  // the owner founded the new majority
        if (owner.religionManager.religionState < com.unciv.logic.civilization.managers.ReligionState.Pantheon) return
        val rules = owner.gameInfo.ruleset.ai
        val points = when {
            oldMajority != ownReligion -> rules.define(AiDefine.RELIGION_DIPLO_HIT_INITIAL_CONVERT_FRIENDLY_CITY)
            city.isHolyCityOf(ownReligion) -> rules.define(AiDefine.RELIGION_DIPLO_HIT_CONVERT_HOLY_CITY)
            else -> rules.define(AiDefine.RELIGION_DIPLO_HIT_RELIGIOUS_FLIP_FRIENDLY_CITY)
        }
        owner.ai.diplomacy.memoryOf(responsible).negativeReligiousConversionPoints += points
    }

    /** CvPlayer::DoCivilianReturnLogic: [returner] gave back a captured civilian of [owner] */
    fun onCivilianReturned(owner: Civilization, returner: Civilization) {
        if (owner.isMajorCiv) owner.ai.diplomacy.memoryOf(returner).numCiviliansReturned++
    }

    /** CvCity::BuyPlot: [buyer] bought a tile within 3 tiles of one of [owner]'s cities (AI_ChangeNumPlotsAcquiredByOtherPlayer) */
    fun onPlotBoughtNear(owner: Civilization, buyer: Civilization, count: Int) {
        if (owner.isMajorCiv && owner != buyer) owner.ai.diplomacy.memoryOf(buyer).numPlotsBoughtNearUs += count
    }
    //endregion
}
