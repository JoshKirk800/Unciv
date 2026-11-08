package com.unciv.logic.automation.civilization

import com.unciv.models.metadata.GameSettings
import com.unciv.view.isAirUnit
import com.unciv.view.isCivilian
import com.unciv.view.isMilitary
import com.unciv.view.isNuclearWeapon
import com.unciv.view.isAtWar
import com.unciv.UncivGame
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.diplomacy.PromiseAi
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.unit.OperationMoves
import com.unciv.logic.automation.unit.ArchaeologistAutomation
import com.unciv.logic.automation.unit.EspionageAutomation
import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.battle.*
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.utils.randomWeighted
import com.unciv.view.CivView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Readonly

object NextTurnAutomation {

    /** Top-level AI turn task list */
    fun automateCivMoves(civInfo: Civilization,
                         /** set false for 'forced' automation, such as skip turn */
                         tradeAndChangeState: Boolean = true): Unit = timeThis("automateCivMoves") {
        if (civInfo.isBarbarian) return BarbarianAutomation(civInfo).automate()
        if (civInfo.isSpectator) return // When there's a spectator in multiplayer games, it's processed automatically, but shouldn't be able to actually do anything

        val civView = GameView(civInfo.gameInfo, civInfo).civView

        timeThis("AI.respondToPopupAlerts") { respondToPopupAlerts(civView) }
        timeThis("AI.respondToTradeRequests") { TradeAutomation.respondToTradeRequests(civInfo, tradeAndChangeState) }

        if (tradeAndChangeState && civInfo.isMajorCiv) {
            // CvPlayerAI::AI_doTurnPostDiplomacy order: CvDiplomacyAI::DoTurn (war, demands, planning), then the contacts, the deals, the requests
            val relationshipsCanChange = !civView.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
            if (relationshipsCanChange) timeThis("AI.DiplomacyActions") { DiplomacyAutomation.doTurn(civInfo) }
            // CvDiplomacyAI::DoTurn: DoContactMinorCivs before DoContactMajorCivs
            if (relationshipsCanChange) CityStateAi.contactMinorCivs(civInfo)
            if (civView.isReligionEnabled()) {
                timeThis("AI.spendFaithOnReligion") { ReligionAutomation.spendFaithOnReligion(civInfo) }
            }

            if (relationshipsCanChange) DiplomacyAutomation.contactMajorCivs(civInfo)
            if (relationshipsCanChange) TradeAutomation.doOffers(civInfo)
            if (relationshipsCanChange) {
                DiplomacyAutomation.contactMajorCivsAfterOffers(civInfo)
                DiplomacyAutomation.checkMilitaryPresenceNearBorder(civInfo)
            }
            if (civInfo.gameInfo.ruleset.greatWorks.isNotEmpty()) timeThis("AI.greatWorks") {
                // CvEconomicAI::DoTurn, AI part: DisbandExtraArchaeologists, then CvPlayerCulture::DoSwapGreatWorks
                ArchaeologistAutomation.disbandExtraArchaeologists(civInfo)
                GreatWorkSwapAutomation.doSwapGreatWorks(civInfo)
            }
            
            timeThis("AI.issueRequests") { issueRequests(civView) }
            timeThis("AI.adoptPolicy") { adoptPolicy(civView) }  // todo can take a second - why?
            timeThis("AI.freeUpSpaceResources") { freeUpSpaceResources(civView) }
        } else if (civView.isCityState) {
            civInfo.cityStateFunctions.getFreeTechForCityState()
            civInfo.cityStateFunctions.updateDiplomaticRelationshipForCityState()
        }

        timeThis("AI.chooseTechToResearch") { TechAutomation.chooseTechToResearch(civInfo) }
        if (tradeAndChangeState) timeThis("AI.considerAnnex") { CityConquestAutomation.considerAnnex(civInfo) }
        if (tradeAndChangeState) timeThis("AI.upgradeUnits") { UseGoldAutomation.plotUpgradeMoves(civInfo) }
        val cache = AutomationCache()
        // CvPlayer::doTurnUnits' operational AI: the armies of the operations move before the other units
        OperationMoves.doTurn(civInfo, cache)
        timeThis("AI.automateUnits") { automateUnits(civView, cache) }  // this is the most expensive part

        if (tradeAndChangeState && civInfo.isMajorCiv) {
            if (civView.isReligionEnabled()) {
                // Can only be done now, as the prophet first has to decide to found/enhance a religion
                timeThis("AI.chooseReligiousBeliefs") { ReligionAutomation.chooseReligiousBeliefs(civInfo) }
            }
            if (civInfo.gameInfo.isEspionageEnabled()) {
                // Do after cities are conquered
                EspionageAutomation(civInfo).automateSpies()
            }
        }

        timeThis("AI.automateCities") { automateCities(civView) }  // second most expensive
        // I'm not sure what will happen if we *don't* vote when we can, so automate vote even when forced automation
        timeThis("AI.tryVoteForDiplomaticVictory") { tryVoteForDiplomaticVictory(civView) }
        timeThis("AI.automateLeague") { LeagueAutomation.automateLeague(civInfo) } // the World Congress of Brave New World, also for skipped turns
    }

    fun automateGoldToSciencePercentage(civView: CivView) {
        // Don't let the AI run blindly with the default convert-gold-to-science ratio if that option is enabled
        val estimatedIncome = civView.getGoldPerTurn()
        val projectedGold = civView.gold + estimatedIncome
        // TODO: some cleverness, this is just wild guessing.
        val pissPoor = civView.getEra().baseUnitBuyCost
        val stinkingRich = civView.getEra().startingGold * 10 + civView.cities().size * 2 * pissPoor
        val maxPercent = 0.8f
        civView.trySetGoldPercentConvertedToScience(when {
            civView.gold <= 0 -> 0f
            projectedGold <= pissPoor -> 0f
            else -> ((projectedGold - pissPoor) * maxPercent / stinkingRich).coerceAtMost(maxPercent)
        })
    }
    
    private fun respondToPopupAlerts(civView: CivView) {
        val civInfo = civView.getCiv()
        for (popupAlert in civInfo.popupAlerts.toList()) { // toList because this can trigger other things that give alerts, like Golden Age
            respondToDemandAlert(civView, popupAlert)
            if (popupAlert.type == AlertType.DeclarationOfFriendship)
                respondToDeclarationOfFriendship(civView, popupAlert)
        }
        civInfo.popupAlerts.clear() // AIs don't care about popups.
    }

    private fun respondToDemandAlert(civView: CivView, popupAlert: PopupAlert) {
        val civInfo = civView.getCiv()
        for (demand in Demand.entries) {
            if (popupAlert.type != demand.demandAlert) continue
            val demandingCiv = civInfo.gameInfo.getCivilization(popupAlert.value)
            if (demandingCiv.isDefeated()) return
            val diploManager = civInfo.getDiplomacyManager(demandingCiv)!!
            PromiseAi.onDemandAsked(demandingCiv, civInfo, demand)
            if (PromiseAi.isDemandAcceptable(civInfo, demandingCiv, demand))
                diploManager.agreeToDemand(demand)
            else diploManager.refuseDemand(demand)
            return
        }
    }

    private fun respondToDeclarationOfFriendship(civView: CivView, popupAlert: PopupAlert) {
        val civInfo = civView.getCiv()
        val requestingCiv = civInfo.gameInfo.getCivilization(popupAlert.value)
        if (requestingCiv.isDefeated()) return
        val diploManager = civInfo.getDiplomacyManager(requestingCiv)!!
        if (civInfo.diplomacyFunctions.canSignDeclarationOfFriendshipWith(requestingCiv)
            && DiplomacyAutomation.isDoFAcceptable(civInfo, requestingCiv)) {
            diploManager.signDeclarationOfFriendship()
            requestingCiv.addNotification("We have signed a Declaration of Friendship with [${civView.civName}]!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civView.civName)
        } else {
            diploManager.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 10)
            requestingCiv.addNotification("[${civView.civName}] has denied our Declaration of Friendship!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civView.civName)
        }
    }

    /** CvPlayer::doTurn: an AI without an ideology chooses one when it is time (CvPolicyAI::DoChooseIdeology), then CvPlayerPolicies::DoPolicyAI */
    private fun adoptPolicy(civView: CivView) {
        val civInfo = civView.getCiv()
        PolicyAutomation.chooseIdeology(civInfo)?.let { civInfo.policies.adopt(it) }
        PolicyAutomation.doPolicyAi(civInfo)
    }

    /** If we are able to build a spaceship but have already spent our resources, try disbanding
     *  a unit and selling a building to make room. Can happen due to trades etc */
    private fun freeUpSpaceResources(civView: CivView) {
        val civInfo = civView.getCiv()
        // No need to build spaceship parts just yet
        if (civView.getAvailableVictories()
                .none { civInfo.victoryManager.getNextMilestone(it)?.type == MilestoneType.AddedSSPartsInCapital })
            return

        for (resource in civInfo.gameInfo.spaceResources) {
            // Have enough resources already
            if (civView.getResourceAmount(resource) >= 2) continue

            val unitToDisband = civInfo.units.getCivUnits()
                .filter { it.requiresResource(resource) && !it.hasUnique(UniqueType.SpaceshipPart) }
                .minByOrNull { it.getForceEvaluation() }
            unitToDisband?.disband()
            sellBuildingForResource(civView, resource)
        }
    }

    private fun sellBuildingForResource(civView: CivView, resource: String) {
        val civInfo = civView.getCiv()
        for (city in civInfo.cities) {
            if (city.hasSoldBuildingThisTurn) continue
            val rng = city.state.stateBasedRandom("NextTurnAutomation.freeUpSpaceResources")
            val buildingToSell = civView.ruleset.buildings.values
                .filter {
                    city.cityConstructions.isBuilt(it.name)
                        && it.requiredResources(city.state).contains(resource)
                        && it.isSellable()
                        && !city.greatWorks.holdsWorks(it)
                        && !civInfo.civConstructions.hasFreeBuilding(city, it)
                }
                .randomOrNull(rng) ?: continue
            city.sellBuilding(buildingToSell)
            return
        }
    }


    private fun automateUnits(civView: CivView, cache: AutomationCache) {
        val civInfo = civView.getCiv()
        val isAtWar = civView.isAtWar()
        // Instances present when the pass starts. addUnit/removeUnit replace UnitManager's list,
        // so units acquired later must not extend this pass. The instances stay mutable:
        // capturedBy changes owner on the same object, and an earlier action can do that to a
        // later one, so ownership and destruction are checked at each dispatch, not before the loop.
        val unitsAtStart = civView.getUnits()
        fun unitsInPriorityOrder() = unitsAtStart.sortedBy { unit -> getUnitPriority(unit, isAtWar, cache) }

        for (unit in unitsInPriorityOrder()) {
            if (!isStillOurUnit(unit, civView)) continue
            applyPromotions(unit)
        }
        // CvTacticalAI::Update runs before the homeland AI: the units near the enemy or in danger first (the attacks of units and cities
        // included), the rest of the unit AI skips them
        TacticalAi.automate(civInfo, unitsInPriorityOrder().map { it.getUnit() }, cache)
        for (unit in unitsInPriorityOrder()) {
            // move spaceship parts and settlers first, so we're not blocking them
            if (!unit.hasUnique(UniqueType.SpaceshipPart) && !unit.hasUnique(UniqueType.FoundCity)) continue
            if (!isStillOurUnit(unit, civView)) continue
            UnitAutomation.automateUnitMoves(unit, cache)
        }

        for (unit in unitsInPriorityOrder()) {
            // spaceship parts and settlers have already moved
            if (unit.hasUnique(UniqueType.SpaceshipPart) || unit.hasUnique(UniqueType.FoundCity)) continue
            if (!isStillOurUnit(unit, civView)) continue
            UnitAutomation.automateUnitMoves(unit, cache)
        }
    }

    /** False once an earlier action in this pass has captured or destroyed [unit]. */
    @Readonly
    private fun isStillOurUnit(unit: MapUnitView, civView: CivView): Boolean {
        return !unit.isDestroyed() && unit.civ() == civView
    }

    internal fun applyPromotions(unitView: MapUnitView) {
        val unit = unitView.getUnit()
        // Humans only auto-promote when the setting is not Off (AI always does)
        if (!unit.civ.isAI() && UncivGame.Current.settings.automaticPromotions == GameSettings.AutomaticPromotions.Off) return
        val rng = unit.cache.state.stateBasedRandom("NextTurnAutomation.automateUnits")
        val stateForConditionals = unit.cache.state
        while (unitView.canBePromoted()) {
            val promotions = unit.promotions.getAvailablePromotions()
            val availablePromotions = if (unitView.unitHealth <= 60
                && promotions.any { it.hasUnique(UniqueType.OneTimeUnitHeal) }
                && !(unitView.isAirUnit() || unitView.hasUnique(UniqueType.CanMoveAfterAttacking))
            ) {
                promotions.filter { it.hasUnique(UniqueType.OneTimeUnitHeal) }
            } else promotions.filterNot { it.hasUnique(UniqueType.SkipPromotion) }

            if (availablePromotions.none()) break
            val freePromotions = availablePromotions.filter { it.hasUnique(UniqueType.FreePromotion) }.toList()
            val chosenPromotion =
                if (freePromotions.isNotEmpty()) freePromotions.randomWeighted(rng) {
                    it.getWeightForAiDecision(stateForConditionals)
                }
                else availablePromotions.toList()
                    .randomWeighted(rng) { it.getWeightForAiDecision(stateForConditionals) }
            unitView.tryAddPromotion(chosenPromotion.name)
        }
    }

    /** Returns the priority of the unit, a lower value is higher priority **/
    fun getUnitPriority(unit: MapUnitView, isAtWar: Boolean, cache: AutomationCache): Int {
        val mapUnit = unit.getUnit()
        if (unit.hasUnique(UniqueType.SpaceshipPart)) return 0 // Move before everything else so we can swap
        if (unit.isCivilian() && unit.hasUnique(UniqueType.FoundCity)) return 1// Settlers before workers
        if (unit.isCivilian() && !mapUnit.isGreatPersonOfType("War")) return 2 // Civilian
        if (unit.isAirUnit()) return when {
            mapUnit.canIntercept() -> 2 // Fighers first
            unit.isNuclearWeapon() -> 3 // Then Nukes (area damage)
            !unit.hasUnique(UniqueType.SelfDestructs) -> 4 // Then Bombers (reusable)
            else -> 5 // Missiles
        }
        val distance = if (!isAtWar) 0 else ThreatManager.getDistanceToClosestEnemyUnit(unit.civ(), cache, unit.getTile(), 6)
        // Lower health units should move earlier to swap with higher health units
        return distance + (unit.unitHealth / 10) - mapUnit.promotions.numberOfPromotions + when {
            unit.getBaseUnit().isRanged() -> 10
            unit.getBaseUnit().isMelee() -> 30
            mapUnit.isGreatPersonOfType("War") -> 100 // Generals move after military units
            else -> 1
        }
    }

    /** Every city of a human player shoots at what it can (the end-of-turn setting, auto play); the AI's cities shoot as part of [TacticalAi]'s attacks */
    fun automateCityBombardment(civView: CivView) {
        for (city in civView.cities()) UnitAutomation.tryBombardEnemy(city)
    }

    fun automateCities(civView: CivView) {
        val civInfo = civView.getCiv()
        for (city in civInfo.cities) {


            city.cityConstructions.chooseNextConstruction()
        }
    }

    // Technically, this function should also check for civs that have liberated one or more cities
    // However, that can be added in another update, this PR is large enough as it is.
    private fun tryVoteForDiplomaticVictory(civView: CivView) {
        val civ = civView.getCiv()
        if (!civView.mayVoteForDiplomaticVictory()) return
        val rng = civ.state.stateBasedRandom("NextTurnAutomation.tryVoteForDiplomaticVictory")

        val chosenCiv: Civilization? = if (civ.isMajorCiv) {
            val knownMajorCivs = civ.getKnownCivs().filter { it.isMajorCiv }
            val highestOpinion = knownMajorCivs
                .maxOfOrNull {
                    civ.getDiplomacyManager(it)!!.opinionOfOtherCiv()
                }

            if (highestOpinion == null) null  // Abstain if we know nobody
            else if (highestOpinion < -80 || highestOpinion < -40 && highestOpinion + rng.nextInt(40) < -40)
                null // Abstain if we hate everybody (proportional chance in the RelationshipLevel.Enemy range - lesser evil)
            else knownMajorCivs
                .filter { civ.getDiplomacyManager(it)!!.opinionOfOtherCiv() == highestOpinion }
                .toList().random(rng)

        } else {
            civ.allyCiv
        }

        civ.diplomaticVoteForCiv(chosenCiv?.civID)
    }

    private fun issueRequests(civView: CivView) {
        val civInfo = civView.getCiv()
        for (otherCiv in civInfo.getKnownCivs().filter { it.isMajorCiv }) {
            val diploManager = civInfo.getDiplomacyManager(otherCiv)!!
            for (demand in Demand.entries) {
                if (diploManager.hasFlag(demand.violationOccurred))
                    onDemandViolation(demand, civView, otherCiv)
            }
        }
    }
    
    private fun onDemandViolation(demand: Demand, civView: CivView, offendingCiv: Civilization) {
        val civInfo = civView.getCiv()
        // most demand violations are not checked during war
        if (civInfo.isAtWarWith(offendingCiv) && demand != Demand.DoNotAttackUs)
            return
        val diplomacyManager = civInfo.getDiplomacyManager(offendingCiv)!!
        when {
            diplomacyManager.hasFlag(demand.willIgnoreViolation) -> {}
            // violation after promise (broke promise)
            diplomacyManager.hasFlag(demand.agreedToDemand) -> {
                offendingCiv.popupAlerts.add(PopupAlert(demand.violationDiscoveredAlert, civView.civID))
                diplomacyManager.setFlag(demand.willIgnoreViolation, if (demand == Demand.DoNotDigInOurLands) 1 else 100, demand != Demand.DoNotDigInOurLands)
                PromiseAi.onDemandBroken(civInfo, offendingCiv, demand)
                diplomacyManager.removeFlag(demand.agreedToDemand)
            }
            // violation before promise
            else -> {
                if (PromiseAi.wantsPromiseFrom(civInfo, offendingCiv, demand))
                    offendingCiv.popupAlerts.add(PopupAlert(demand.demandAlert, civView.civID))
            }
        }
        diplomacyManager.removeFlag(demand.violationOccurred)
    }

    data class CityDistance(val city: City, val aerialDistance: Int)

    @Readonly
    fun getForeignCityNearCapital(capital: City?, foreignCiv: Civilization): CityDistance? {
        if (capital == null || foreignCiv.cities.isEmpty())
            return null
        
        var minDistance: CityDistance? = null
        for (foreignCity in foreignCiv.cities) { 
            val currentDistance =
                foreignCity.getCenterTile().aerialDistanceTo(capital.getCenterTile())
            if (minDistance == null || currentDistance < minDistance.aerialDistance)
                minDistance = CityDistance(foreignCity, currentDistance)
        }
        return minDistance
    }
}
