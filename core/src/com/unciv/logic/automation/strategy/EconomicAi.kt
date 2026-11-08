package com.unciv.logic.automation.strategy

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import kotlin.math.min
import kotlin.math.sqrt

/** CvEconomicAI ReconState */
enum class ReconState { Enough, Neutral, Needed }

/**
 *  The part of CvEconomicAI beyond the strategy loop (`civ.ai.economic`): recon state, antiquity sites, worker disbanding.
 *  Plot purchases are in [UseGoldAutomation.doPlotPurchases], the purchase savings in [PurchaseSavings].
 *
 *  Not ported, with the reason: DoHurry (its gold branch is commented out in the original, and the population branch needs
 *  HURRY_POPULATION access that no Brave New World policy or building grants - Unciv has no population rushing either);
 *  DisbandExtraArchaeologists and DoSwapGreatWorks (culture port). The exploration plots and the goody-hut assignments (UpdatePlots) are in [ExplorationAi]/[ExplorationPlots].
 */
class EconomicAi : IsPartOfGameInfoSerialization {
    /** CvEconomicAI::m_iLastTurnWorkerDisbanded, -1 if never */
    var lastTurnWorkerDisbanded = -1
    /** CvEconomicAI::m_iVisibleAntiquitySites, updated by [doAntiquitySites] */
    var visibleAntiquitySites = 0
    /** CvEconomicAI::m_iExplorersDisbanded: land explorers [com.unciv.logic.automation.unit.HomelandAi] disbanded for finding no way home; they count as explorers in [doReconState] for good */
    var explorersDisbanded = 0

    /** CvEconomicAI::m_eReconState, recomputed each turn before the strategy loop (null before the first computation) */
    @Transient var reconState: ReconState? = null
    /** CvEconomicAI::m_eNavalReconState */
    @Transient var navalReconState: ReconState? = null

    fun clone(): EconomicAi {
        val toReturn = EconomicAi()
        toReturn.lastTurnWorkerDisbanded = lastTurnWorkerDisbanded
        toReturn.visibleAntiquitySites = visibleAntiquitySites
        toReturn.explorersDisbanded = explorersDisbanded
        return toReturn
    }

    /** What CvEconomicAI::DoTurn does before its strategy loop: DoReconState and DoAntiquitySites, in one pass over the map */
    fun doTurnBeforeStrategies(civ: Civilization) {
        doReconState(civ)
    }

    /** What CvEconomicAI::DoTurn does after its strategy loop for AI players: DoPlotPurchases, DisbandExtraWorkers */
    fun doTurnAfterStrategies(civ: Civilization) {
        UseGoldAutomation.doPlotPurchases(civ)
        disbandExtraWorkers(civ)
    }

    /**
     *  CvEconomicAI::DoReconState (and DoAntiquitySites, counted in the same pass). Over the tiles the civilization has explored, counts revealed land and revealed coastal water (deep water
     *  too once embarked units may enter the ocean), and the revealed tiles with an unrevealed neighbor (the first such neighbor decides
     *  whether it counts as land or as water fog). The land state compares [reconWeight] of the land counts and the land explorers
     *  (UNITAI_EXPLORE, being trained included, plus [explorersDisbanded]) with 110 - 10 x RECON flavor (at most 100); the naval state, with a coastal city, does
     *  the same for the water counts, the sea explorers and NAVAL_RECON. At ENOUGH the explorers that can attack go back to the attack
     *  role (one land explorer is kept before turn 100).
     */
    fun doReconState(civ: Civilization) {
        if (civ.isCityState) {
            reconState = ReconState.Enough
            navalReconState = ReconState.Enough
            doAntiquitySites(civ)
            return
        }
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val siteResource = constants.archaeologySiteResource
        val hiddenSiteResource = constants.archaeologyHiddenSiteResource
        var antiquitySites = 0
        val rules = civ.gameInfo.ruleset.ai
        val deepWaterToo = civ.tech.embarkedUnitsCanEnterOcean
        UnitAiTypes.pruneOverrides(civ)
        var landRevealed = 1
        var landWithAdjacentFog = 1
        var coastalRevealed = 1
        var coastalWithAdjacentFog = 1
        for (tile in civ.gameInfo.tileMap.values) {
            if (!civ.hasExplored(tile)) continue
            val resource = tile.resource
            if (resource != null && (resource == siteResource || resource == hiddenSiteResource) && civ.canSeeResource(tile.tileResource))
                antiquitySites++
            if (!tile.isWater) landRevealed++
            else if (!tile.isOcean || deepWaterToo) coastalRevealed++
            for (neighbor in tile.neighbors) {
                if (neighbor.isWater) {
                    if ((deepWaterToo || !neighbor.isOcean) && !civ.hasExplored(neighbor)) {
                        coastalWithAdjacentFog++
                        break
                    }
                } else if (!civ.hasExplored(neighbor)) {
                    landWithAdjacentFog++
                    break
                }
            }
        }

        visibleAntiquitySites = antiquitySites

        val landExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE, includeBeingTrained = true, includeWater = false) + explorersDisbanded
        val landWeight = reconWeight(civ, landWithAdjacentFog, landRevealed, landExplorers)
        val landState = reconState(landWeight, reconThreshold(civ, Flavor.Recon))
        reconState = landState
        if (landState == ReconState.Enough) {
            // Keep at least one explorer through turn 100
            var skipFirst = civ.gameInfo.turns < 100
            for (unit in civ.units.getCivUnits()) {
                if (UnitAiTypes.current(unit) != UnitAiType.EXPLORE || !UnitAiTypes.canHave(unit.baseUnit, UnitAiType.ATTACK, rules)) continue
                if (skipFirst) skipFirst = false
                else UnitAiTypes.set(unit, UnitAiType.ATTACK)
            }
        }

        if (civ.cities.none { it.isCoastal() }) {
            navalReconState = ReconState.Enough
            return
        }
        val seaExplorers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.EXPLORE_SEA, includeBeingTrained = true, includeWater = true)
        val navalWeight = reconWeight(civ, coastalWithAdjacentFog, coastalRevealed, seaExplorers)
        val navalState = reconState(navalWeight, reconThreshold(civ, Flavor.NavalRecon))
        navalReconState = navalState
        if (navalState == ReconState.Enough) {
            // The original keeps one only when NEUTRAL, which cannot be the case here: all go back
            for (unit in civ.units.getCivUnits())
                if (UnitAiTypes.current(unit) == UnitAiType.EXPLORE_SEA && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.ATTACK_SEA, rules))
                    UnitAiTypes.set(unit, UnitAiType.ATTACK_SEA)
        }
    }

    /** CvEconomicAI::DoAntiquitySites: antiquity sites (hidden ones included) on tiles the civilization has revealed ([doReconState] counts them for majors) */
    fun doAntiquitySites(civ: Civilization) {
        visibleAntiquitySites = ArchaeologyFunctions.getVisibleSites(civ).size
    }

    /**
     *  CvEconomicAI::DisbandExtraWorkers: when losing money (LOSING_MONEY) or paying more than 4 gold per unit, scraps one land worker if
     *  there are more than one, more than one per four cities, at least two thirds of the improvable tiles are improved, and more workers
     *  than one per five unimproved tiles (rounded up) plus one per unconnected city on the capital's landmass.
     */
    fun disbandExtraWorkers(civ: Civilization) {
        val inDeficit = civ.ai.isEconomicStrategyActive(LOSING_MONEY)
        if (!inDeficit && civ.stats.getUnitMaintenance() / maxOf(1, civ.units.getCivUnitsSize()) <= 4) return

        val numWorkers = UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false)
        val numCities = civ.cities.size
        if (numWorkers / numCities.toDouble() <= 0.25 || numWorkers == 1) return

        var valid = 0
        var improved = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!EconomicAiRatios.isImprovablePlot(tile)) continue
            valid++
            if (tile.improvement != null && !tile.improvementIsPillaged) improved++
        }
        if (valid <= 0) return
        if (improved / valid.toDouble() < 2 / 3.0) return

        val unimproved = valid - improved
        var minWorkers = (unimproved + 4) / 5
        val capital = civ.getCapital(firstCityIfNoCapital = false) ?: return
        val capitalContinent = capital.getCenterTile().getContinent()
        for (city in civ.cities)
            if (city != capital && city.getCenterTile().getContinent() == capitalContinent && !city.isConnectedToCapital()) minWorkers++
        if (numWorkers <= minWorkers) return

        lastTurnWorkerDisbanded = civ.gameInfo.turns
        // CvEconomicAI::FindWorkerToScrap: the first land worker that is not a combat unit
        val rules = civ.gameInfo.ruleset.ai
        val worker = civ.units.getCivUnits().firstOrNull {
            it.baseUnit.isLandUnit && it.isCivilian() && UnitAiTypes.defaultOf(it.baseUnit, rules) == UnitAiType.WORKER
        } ?: return
        worker.disband()
    }

    companion object {
        /**
         *  The recon part of CvCity::CreateUnit, for units AI majors train: under NEED_RECON a unit that can explore (Unit_AITypes) becomes
         *  an explorer unless the threat (total threat weight + barbarian threat) reaches the CRITICAL threat weight; under NEED_RECON_SEA or
         *  REALLY_NEED_RECON_SEA a unit that can explore the sea becomes a sea explorer.
         */
        fun onUnitTrained(unit: MapUnit) {
            val civ = unit.civ
            if (civ.isHuman() || civ.isCityState || civ.isBarbarian) return
            val rules = civ.gameInfo.ruleset.ai
            if (civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON)
                && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.EXPLORE, rules) && UnitAiTypes.current(unit) != UnitAiType.EXPLORE) {
                val military = civ.ai.military
                val threat = military.totalThreatWeight + military.barbarianThreatTotal(civ)
                if (threat < military.threatWeight(civ, AiThreat.Critical)) UnitAiTypes.set(unit, UnitAiType.EXPLORE)
            }
            if ((civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.NEED_RECON_SEA)
                    || civ.ai.isEconomicStrategyActive(EconomicMilitaryStrategyTests.REALLY_NEED_RECON_SEA))
                && UnitAiTypes.canHave(unit.baseUnit, UnitAiType.EXPLORE_SEA, rules))
                UnitAiTypes.set(unit, UnitAiType.EXPLORE_SEA)
        }

        const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"

        /** The recon weight of CvEconomicAI::DoReconState: start weight x tiles next to fog / (explorers + divisor)^2 / (int)sqrt(revealed) */
        fun reconWeight(civ: Civilization, withAdjacentFog: Int, revealed: Int, explorers: Int): Int {
            val rules = civ.gameInfo.ruleset.ai
            var weight = rules.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_STARTING_WEIGHT) * withAdjacentFog
            val divisor = explorers + rules.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_EXPLORERS_WEIGHT_DIVISOR)
            weight /= divisor * divisor
            weight /= sqrt(revealed.toDouble()).toInt()
            return weight
        }

        /** The threshold of CvEconomicAI::DoReconState: 110 - flavor (personality and grand strategy) x 10, at most 100 */
        fun reconThreshold(civ: Civilization, flavor: Flavor): Int =
            min(100, 110 - civ.ai.personalityAndGrandStrategy(flavor) * civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_EARLY_EXPLORATION_WEIGHT_PER_FLAVOR))

        private fun reconState(weight: Int, threshold: Int) = when {
            weight > threshold -> ReconState.Needed
            weight > threshold / 4 -> ReconState.Neutral
            else -> ReconState.Enough
        }

        /** CvGame::getHandicapInfo().GetID(): the index of the game's difficulty (Settler = 0 ... Deity = 7) */
        fun gameHandicapIndex(gameInfo: GameInfo): Int =
            gameInfo.ruleset.difficulties.keys.indexOf(gameInfo.getDifficulty().name).coerceAtLeast(0)
    }
}
