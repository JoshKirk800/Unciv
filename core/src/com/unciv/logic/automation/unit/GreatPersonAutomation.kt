package com.unciv.logic.automation.unit

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.BFS
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import java.util.WeakHashMap

/** The original's UnitAITypes of Great People (UNITAI_WRITER ... UNITAI_ADMIRAL) */
enum class GreatPersonRole(val unitAiType: String) {
    Writer("UNITAI_WRITER"), Artist("UNITAI_ARTIST"), Musician("UNITAI_MUSICIAN"), Engineer("UNITAI_ENGINEER"),
    Merchant("UNITAI_MERCHANT"), Scientist("UNITAI_SCIENTIST"), General("UNITAI_GENERAL"), Prophet("UNITAI_PROPHET"), Admiral("UNITAI_ADMIRAL")
}

/** The original's GreatPeopleDirectiveTypes (SPREAD_RELIGION is the religion port's) */
enum class GreatPeopleDirective { None, UsePower, ConstructImprovement, GoldenAge, CultureBlast, TourismBlast }

/**
 *  What the AI does with its Great People: the directives of CvPlayerAI (GetDirectiveWriter/Artist/Musician/Engineer/Merchant/Scientist/
 *  General/Admiral, recomputed for each Great Person every turn as CvPlayerAI::ProcessGreatPeople does), their execution by CvHomelandAI
 *  (Execute*Moves) and by the MERCHANT_DELEGATION / CONCERT_TOUR operations, and the free Great Person choice (CvPlayerAI::AI_chooseFreeGreatPerson,
 *  CvPlayerTraits::ChooseMayaBoost). The Great Prophet's directive is the religion port's.
 */
object GreatPersonAutomation {
    /** The public-opinion unhappiness above which writers and artists defend the ideology (a literal in GetDirectiveWriter/GetDirectiveArtist) */
    private const val PUBLIC_OPINION_UNHAPPINESS_LIMIT = 10
    private const val MILITARY_AT_WAR = "MILITARYAISTRATEGY_AT_WAR"
    private const val MILITARY_WAR_MOBILIZATION = "MILITARYAISTRATEGY_WAR_MOBILIZATION"

    //region Inputs

    /**
     *  The Great Person type of [unit]: its default UnitAIType from the ruleset's AI data (Unit_AITypes), else - for units without an entry
     *  (mods, rulesets without AI.json) - from what it can do. Null if it is not a Great Person.
     */
    fun roleOf(unit: BaseUnit, ruleset: Ruleset): GreatPersonRole? {
        if (!unit.isGreatPerson) return null
        val roles = rolesByRuleset.getOrPut(ruleset) { HashMap() }
        if (unit.name in roles) return roles[unit.name]
        return deriveRole(unit, ruleset).also { roles[unit.name] = it }
    }

    /** [roleOf] by unit name, per ruleset */
    private val rolesByRuleset = WeakHashMap<Ruleset, HashMap<String, GreatPersonRole?>>()

    private fun deriveRole(unit: BaseUnit, ruleset: Ruleset): GreatPersonRole? {
        val aiType = ruleset.ai.unitAiTypes.firstOrNull { it.name == unit.name }?.defaultUnitAi
        if (aiType != null) return GreatPersonRole.entries.firstOrNull { it.unitAiType == aiType }
        fun has(type: UniqueType) = unit.hasUnique(type, GameContext.IgnoreConditionals)
        return when {
            has(UniqueType.MayFoundReligion) -> GreatPersonRole.Prophet
            has(UniqueType.CanHurryResearch) -> GreatPersonRole.Scientist
            has(UniqueType.CanSpeedupWonderConstruction) || has(UniqueType.CanSpeedupConstruction) -> GreatPersonRole.Engineer
            has(UniqueType.CanTradeWithCityStateForGoldAndInfluence) -> GreatPersonRole.Merchant
            has(UniqueType.OneTimeConcertTour) -> GreatPersonRole.Musician
            has(UniqueType.CanHurryPolicy) -> GreatPersonRole.Writer
            has(UniqueType.OneTimeCreateGreatWork) -> GreatPersonRole.Artist
            has(UniqueType.StrengthBonusInRadius) -> if (unit.isWaterUnit) GreatPersonRole.Admiral else GreatPersonRole.General
            else -> null
        }
    }

    /** CvGame::getEstimateEndTurn: the length of the game speed (the sum of its turn increments) */
    fun estimateEndTurn(gameInfo: GameInfo): Int = gameInfo.speed.numTotalTurns()

    /** IsSafe (CvPlayerAI.cpp): winning all wars, or not using MILITARYAISTRATEGY_AT_WAR */
    fun isSafe(civ: Civilization): Boolean =
        WarStateAi.stateAllWars(civ) == StateAllWars.Winning || !civ.ai.isMilitaryStrategyActive(MILITARY_AT_WAR)

    /** PreparingForWar (CvPlayerAI.cpp): using MILITARYAISTRATEGY_WAR_MOBILIZATION */
    fun preparingForWar(civ: Civilization): Boolean = civ.ai.isMilitaryStrategyActive(MILITARY_WAR_MOBILIZATION)

    /** EconomicAIHelpers::IsTestStrategy_GS_SpaceshipHomestretch: the Apollo Program is built */
    fun isSpaceshipHomestretch(civ: Civilization): Boolean = civ.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts)

    /** CvPlayerPolicies::CanGetAdvancedTenet: a tenet of level 2 or more of our ideology that we can adopt now (cost included) */
    fun canGetAdvancedTenet(civ: Civilization): Boolean {
        val ideology = civ.getIdeology() ?: return false
        return ideology.policies.any { it.tenetLevel > 1 && civ.policies.canAdopt(it) }
    }

    /** CvGameCulture::GetNumCivsInfluentialForWin: the living majors but one */
    fun numCivsInfluentialForWin(civ: Civilization): Int = civ.gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() } - 1

    /** CvPlayerTraits::GetGoldenAgeMoveChange > 0: a movement bonus during golden ages (Persia) */
    private fun hasGoldenAgeMoveChange(civ: Civilization): Boolean = civ.getMatchingUniques(UniqueType.Movement, GameContext.IgnoreConditionals)
        .any { it.hasModifier(UniqueType.ConditionalGoldenAge) && it.params[0].toInt() > 0 }

    /** CvPlayerTraits::GetGoldenAgeTourismModifier > 0: more tourism during golden ages (Brazil) */
    private fun hasGoldenAgeTourismModifier(civ: Civilization): Boolean = civ.getMatchingUniques(UniqueType.TourismPercent, GameContext.IgnoreConditionals)
        .any { it.hasModifier(UniqueType.ConditionalGoldenAge) && it.params[0].toInt() > 0 }

    /** CvPlayerTraits::IsNoAnnexing (Venice) */
    private fun isVenice(civ: Civilization) = civ.hasUnique(UniqueType.MayNotAnnexCities)

    /** TurnsToReachTarget: 0 = this turn, Int.MAX_VALUE = no path */
    private fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (unit.currentTile == tile) return 0
        val path = unit.movement.getShortestPath(tile)
        return if (path.isEmpty()) Int.MAX_VALUE else path.size - 1
    }

    /** MISSION_MOVE_TO: a step along the path to [tile]. Without a path the mission fails and the unit stays; returns whether it moved on. */
    private fun moveTo(unit: MapUnit, tile: Tile): Boolean {
        if (!unit.movement.canReach(tile)) return false
        unit.movement.tryHeadTowards(tile)
        return true
    }

    private fun isDamaged(city: City) = city.health < city.getMaxHealth()
    //endregion

    //region Directives (CvPlayerAI::GetDirective*)

    /** CvPlayerAI::ProcessGreatPeople: the directive of [unit] this turn by its type; null for non-Great People and Prophets */
    fun directive(unit: MapUnit): GreatPeopleDirective? = when (roleOf(unit.baseUnit, unit.civ.gameInfo.ruleset)) {
        GreatPersonRole.Writer -> directiveWriter(unit)
        GreatPersonRole.Artist -> directiveArtist(unit)
        GreatPersonRole.Musician -> directiveMusician(unit)
        GreatPersonRole.Engineer -> directiveEngineer(unit)
        GreatPersonRole.Merchant -> directiveMerchant(unit)
        GreatPersonRole.Scientist -> directiveScientist(unit.civ)
        GreatPersonRole.General -> directiveGeneral(unit)
        GreatPersonRole.Admiral -> GreatPeopleDirective.None // CvPlayerAI::GetDirectiveAdmiral
        GreatPersonRole.Prophet, null -> null
    }

    /**
     *  CvPlayerAI::GetDirectiveWriter: a political treatise if not going for culture and the public opinion is unhappy beyond 10 or an
     *  advanced tenet is in reach; else a Great Work if [bestGreatWorkCity] finds a city; else a treatise.
     */
    fun directiveWriter(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        val defendIdeology = !civ.ai.isGoingForCultureVictory() &&
            (civ.culture.publicOpinion.unhappiness > PUBLIC_OPINION_UNHAPPINESS_LIMIT || canGetAdvancedTenet(civ))
        return if (!defendIdeology && bestGreatWorkCity(unit) != null) GreatPeopleDirective.UsePower
        else GreatPeopleDirective.CultureBlast
    }

    /**
     *  CvPlayerAI::GetDirectiveArtist: a golden age if not going for culture and (public opinion unhappy beyond 10 or preparing for war),
     *  or going for spaceship in the homestretch, or with a golden-age movement bonus at war with more than one major and not in a golden age,
     *  or with a golden-age tourism bonus and influential on someone; else a Great Work if [bestGreatWorkCity] finds a city;
     *  else a golden age if not in one; else nothing.
     */
    fun directiveArtist(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        val ai = civ.ai
        val goldenAge = when {
            !ai.isGoingForCultureVictory() && civ.culture.publicOpinion.unhappiness > PUBLIC_OPINION_UNHAPPINESS_LIMIT -> true
            !ai.isGoingForCultureVictory() && preparingForWar(civ) -> true
            ai.isGoingForSpaceshipVictory() && isSpaceshipHomestretch(civ) -> true
            hasGoldenAgeMoveChange(civ) && WarStateAi.numberCivsAtWarWith(civ) > 1 && !civ.goldenAges.isGoldenAge() -> true
            hasGoldenAgeTourismModifier(civ) && civ.culture.getNumCivsInfluentialOn() > 0 -> true
            else -> false
        }
        return when {
            goldenAge -> GreatPeopleDirective.GoldenAge
            bestGreatWorkCity(unit) != null -> GreatPeopleDirective.UsePower
            !civ.goldenAges.isGoldenAge() -> GreatPeopleDirective.GoldenAge
            else -> GreatPeopleDirective.None
        }
    }

    /**
     *  CvPlayerAI::GetDirectiveMusician: keep touring once the tour started; a tour if going for culture, influential on more than half of the
     *  civilizations needed and [findBestMusicianTargetPlot] finds a target; else a Great Work if [bestGreatWorkCity] finds a city; else a tour
     *  if there is a target; else nothing.
     */
    fun directiveMusician(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        var directive = GreatPeopleDirective.None
        if (unit.greatPersonTarget != null) directive = GreatPeopleDirective.TourismBlast
        if (directive == GreatPeopleDirective.None && civ.ai.isGoingForCultureVictory()
            && civ.culture.getNumCivsInfluentialOn() > numCivsInfluentialForWin(civ) / 2
            && findBestMusicianTargetPlot(unit) != null)
            directive = GreatPeopleDirective.TourismBlast
        if (directive == GreatPeopleDirective.None && bestGreatWorkCity(unit) != null) return GreatPeopleDirective.UsePower
        // The original looks for a tour target here even when already touring; the directive stays a tour either way
        if (directive == GreatPeopleDirective.None && findBestMusicianTargetPlot(unit) != null) directive = GreatPeopleDirective.TourismBlast
        return directive
    }

    /**
     *  CvPlayerAI::GetDirectiveEngineer: hurry a wonder if the wonder AI wants any (CvWonderProductionAI::ChooseWonder); else a Manufactory if
     *  going for conquest in the first three quarters of the game, or once the engineer is AI_HOMELAND_GREAT_PERSON_TURNS_TO_WAIT turns old;
     *  else wait.
     */
    fun directiveEngineer(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        if (WonderProductionAi.chooseWonder(civ, adjustForOtherPlayers = false).first != null) return GreatPeopleDirective.UsePower
        val turn = civ.gameInfo.turns
        if (turn <= estimateEndTurn(civ.gameInfo) * 3 / 4 && civ.ai.isGoingForWorldConquest()) return GreatPeopleDirective.ConstructImprovement
        if (turn - unit.turnCreated >= civ.gameInfo.ruleset.ai.define(AiDefine.AI_HOMELAND_GREAT_PERSON_TURNS_TO_WAIT))
            return GreatPeopleDirective.ConstructImprovement
        return GreatPeopleDirective.None
    }

    /**
     *  CvPlayerAI::GetDirectiveMerchant: nothing while on a trade mission; a Customs House if going for diplomacy in the first half of the game
     *  (not Venice); else a trade mission if safe and [findBestMerchantTargetPlot] finds a city-state; else a Customs House once
     *  AI_HOMELAND_GREAT_PERSON_TURNS_TO_WAIT turns old (not Venice); else wait.
     */
    fun directiveMerchant(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        if (unit.greatPersonTarget != null) return GreatPeopleDirective.None
        val venice = isVenice(civ)
        val turn = civ.gameInfo.turns
        if (turn <= estimateEndTurn(civ.gameInfo) * 2 / 4 && civ.ai.isGoingForDiploVictory() && !venice)
            return GreatPeopleDirective.ConstructImprovement
        if (isSafe(civ) && findBestMerchantTargetPlot(unit) != null) return GreatPeopleDirective.UsePower
        if (turn - unit.turnCreated >= civ.gameInfo.ruleset.ai.define(AiDefine.AI_HOMELAND_GREAT_PERSON_TURNS_TO_WAIT) && !venice)
            return GreatPeopleDirective.ConstructImprovement
        return GreatPeopleDirective.None
    }

    /**
     *  CvPlayerAI::GetDirectiveScientist: bulb if not safe; an Academy if going for spaceship in the first quarter of the game; else bulb.
     */
    fun directiveScientist(civ: Civilization): GreatPeopleDirective {
        if (!isSafe(civ)) return GreatPeopleDirective.UsePower
        if (civ.gameInfo.turns <= estimateEndTurn(civ.gameInfo) / 4 && civ.ai.isGoingForSpaceshipVictory())
            return GreatPeopleDirective.ConstructImprovement
        return GreatPeopleDirective.UsePower
    }

    /** CvPlayerAI::GetDirectiveGeneral: a citadel (use power) with more than 2 generals and this one in our territory; else nothing */
    fun directiveGeneral(unit: MapUnit): GreatPeopleDirective {
        val civ = unit.civ
        val ruleset = civ.gameInfo.ruleset
        val generals = civ.units.getCivGreatPeople().count { roleOf(it.baseUnit, ruleset) == GreatPersonRole.General }
        return if (generals > 2 && unit.currentTile.getOwner() == civ) GreatPeopleDirective.UsePower else GreatPeopleDirective.None
    }
    //endregion

    //region Targets

    /**
     *  CvEconomicAI::GetBestGreatWorkCity: if a free slot for [unit]'s work exists anywhere in the empire, the undamaged city nearest to [unit]
     *  (straight-line distance); null otherwise.
     */
    fun bestGreatWorkCity(unit: MapUnit): City? {
        val work = GreatWorkFunctions.getWorkOfUnit(unit) ?: return null
        if (GreatWorkFunctions.getNumFreeSlots(unit.civ, work.getSlotType()) == 0) return null
        return unit.civ.cities.filter { !isDamaged(it) }.minByOrNull { it.getCenterTile().aerialDistanceTo(unit.currentTile) }
    }

    /**
     *  CvPlayerAI::FindBestMerchantTargetPlot: among the city-states we are not at war with, do not approach as Conquest and plan no war
     *  against (CvDiplomacyAI::GetWarGoal), the revealed tile owned by the city-state next to its capital that [unit] reaches in the fewest turns.
     */
    fun findBestMerchantTargetPlot(unit: MapUnit): Tile? {
        val civ = unit.civ
        var bestTurns = Int.MAX_VALUE
        var best: Tile? = null
        for (cityState in civ.gameInfo.civilizations) {
            if (!cityState.isCityState || !cityState.isAlive()) continue
            val capitalTile = cityState.getCapital()?.getCenterTile() ?: continue
            if (!capitalTile.isExplored(civ)) continue
            if (civ.ai.minorCivApproaches[cityState.civID] == MinorCivApproach.Conquest) continue
            if (civ.isAtWarWith(cityState) || civ.ai.diplomacy.warGoal(cityState) != null) continue
            for (tile in capitalTile.neighbors) {
                if (tile.getOwner() != cityState || !tile.isExplored(civ)) continue
                val turns = turnsToReach(unit, tile)
                if (turns < bestTurns) {
                    bestTurns = turns
                    best = tile
                }
            }
        }
        return best
    }

    /**
     *  CvPlayerCulture::GetCivLowestInfluence(bCheckOpenBorders = true): the living major we are not at war with, whose borders are open to us,
     *  with the lowest influence percentage below Influential.
     */
    fun civLowestInfluence(civ: Civilization): Civilization? {
        var lowest = civ.gameInfo.ruleset.modOptions.constants.influencePercentInfluential
        var result: Civilization? = null
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive() || civ.isAtWarWith(other)) continue
            if (civ.getDiplomacyManager(other)?.hasOpenBorders != true) continue
            val percent = civ.culture.getInfluencePercent(other)
            if (percent < lowest) {
                lowest = percent
                result = other
            }
        }
        return result
    }

    /**
     *  CvPlayerAI::FindBestMusicianTargetPlot: the city of [civLowestInfluence] next to which [unit] arrives soonest (revealed tiles of that civ
     *  next to its cities), then the revealed tile of that civ within the city's work radius that [unit] reaches soonest.
     */
    fun findBestMusicianTargetPlot(unit: MapUnit): Tile? {
        val civ = unit.civ
        val target = civLowestInfluence(civ) ?: return null
        var bestTurns = Int.MAX_VALUE
        var bestCity: City? = null
        for (city in target.cities) {
            for (tile in city.getCenterTile().neighbors) {
                if (tile.getOwner() != target || !tile.isExplored(civ)) continue
                val turns = turnsToReach(unit, tile)
                if (turns < bestTurns) {
                    bestTurns = turns
                    bestCity = city
                }
            }
        }
        val city = bestCity ?: return null
        bestTurns = Int.MAX_VALUE
        var best: Tile? = null
        for (tile in city.getCenterTile().getTilesInDistance(city.getWorkRange())) {
            if (tile.getOwner() != target || !tile.isExplored(civ)) continue
            val turns = turnsToReach(unit, tile)
            if (turns < bestTurns) {
                bestTurns = turns
                best = tile
            }
        }
        return best
    }

    /**
     *  CvPlayerAI::FindBestArtistTargetPlot (where a general plants its citadel): among our land tiles next to another civilization's territory,
     *  without a luxury, where [unit] can build its improvement (the original: no feature that forbids improvements) and that holds no
     *  tile-taking improvement yet, the one whose neighbours of other civilizations (not their city centers) are worth most:
     *  [resourceWeight] × 10 for a resource, else the sum of the tile's yields. A neighbour of a city-state we do not approach as Conquest
     *  or Ignore, or of a major we are not [isTickedOffAt], rules the tile out. Null if no tile scores above 0.
     */
    fun findBestArtistTargetPlot(unit: MapUnit): Tile? {
        val civ = unit.civ
        val improvement = unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).firstNotNullOfOrNull {
            civ.gameInfo.ruleset.tileImprovements[it.params[0]]
        } ?: return null
        var best: Tile? = null
        var bestScore = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (tile.isWater || tile.isCityCenter()) continue
            if (tile.neighbors.none { it.getOwner() != null && it.getOwner() != civ }) continue
            if (tile.tileResource?.resourceType == ResourceType.Luxury) continue
            if (!tile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)) continue
            if (tile.improvement?.let { tile.ruleset.tileImprovements[it] }?.hasUnique(UniqueType.OneTimeTakeOverTilesInRadius, GameContext.IgnoreConditionals) == true) continue
            var score = 0
            for (neighbor in tile.neighbors) {
                val owner = neighbor.getOwner() ?: continue
                if (owner == civ || neighbor.isCityCenter()) continue
                if (owner.isCityState) {
                    val approach = civ.ai.minorCivApproaches[owner.civID] ?: MinorCivApproach.Ignore
                    if (approach != MinorCivApproach.Conquest && approach != MinorCivApproach.Ignore) {
                        score = 0
                        break
                    }
                } else if (!isTickedOffAt(civ, owner)) {
                    score = 0
                    break
                }
                val resource = neighbor.tileResource
                score += if (resource != null) resourceWeight(civ, resource, null, neighbor.resourceAmount) * 10
                else neighbor.stats.getTileStats(null).let { it.sum() - it.happiness }.toInt() // the six yields
            }
            if (score > bestScore) {
                bestScore = score
                best = tile
            }
        }
        return best
    }

    /**
     *  The major-civ test of FindBestArtistTargetPlot: the approach we show [other] (CvDiplomacyAI::GetMajorCivApproach with hidden
     *  feelings) is HOSTILE, or NEUTRAL with a STRONG or FIERCE land dispute.
     */
    private fun isTickedOffAt(civ: Civilization, other: Civilization): Boolean {
        val approach = civ.ai.diplomacy.approachTowards(other, hideTrueFeelings = true)
        return approach == MajorCivApproach.Hostile
            || approach == MajorCivApproach.Neutral && civ.ai.diplomacy.landDisputeLevel(other) >= DisputeLevel.Strong
    }

    //endregion

    //region Execution (CvHomelandAI::Execute*Moves, MERCHANT_DELEGATION / CONCERT_TOUR operations)

    /**
     *  Moves [unit] if it is a Great Person other than a Prophet. Returns whether it was handled (including waiting in safety);
     *  false lets the caller try other uses (and for Great Generals, the tactical stand-in).
     */
    fun automate(unit: MapUnit): Boolean = timeThis("AI.GreatPeople") {
        val role = roleOf(unit.baseUnit, unit.civ.gameInfo.ruleset) ?: return@timeThis false
        if (role == GreatPersonRole.Prophet) return@timeThis false
        // Trade missions and concert tours under way are the operations' (the original keeps the unit in its army)
        if (unit.greatPersonTarget != null && (role == GreatPersonRole.Merchant || role == GreatPersonRole.Musician))
            return@timeThis continueOperation(unit, role)
        val directive = directive(unit) ?: return@timeThis false
        unit.greatPeopleDirective = directive
        when (role) {
            GreatPersonRole.Writer, GreatPersonRole.Artist, GreatPersonRole.Musician -> executeCultureMoves(unit, directive)
            GreatPersonRole.Scientist -> executeScientistMoves(unit, directive)
            GreatPersonRole.Engineer -> executeEngineerMoves(unit, directive)
            GreatPersonRole.Merchant -> executeMerchantMoves(unit, directive)
            GreatPersonRole.General -> executeGeneralMoves(unit, directive)
            GreatPersonRole.Admiral -> executeAdmiralMoves(unit)
            GreatPersonRole.Prophet -> false
        }
    }

    /** CvHomelandAI::ExecuteWriterMoves / ExecuteArtistMoves / ExecuteMusicianMoves */
    private fun executeCultureMoves(unit: MapUnit, directive: GreatPeopleDirective): Boolean {
        when (directive) {
            GreatPeopleDirective.CultureBlast -> executeCultureBlast(unit)
            GreatPeopleDirective.GoldenAge -> executeGoldenAgeMove(unit)
            GreatPeopleDirective.UsePower -> executeGreatWork(unit)
            GreatPeopleDirective.TourismBlast -> startOperation(unit, GreatPersonRole.Musician)
            else -> CivilianSafety.moveToSafety(unit)
        }
        return true
    }

    /** The USE_POWER case of ExecuteWriter/Artist/MusicianMoves: create the work next to the [bestGreatWorkCity], or walk there */
    private fun executeGreatWork(unit: MapUnit) {
        val city = bestGreatWorkCity(unit)
        if (city == null) {
            CivilianSafety.moveToSafety(unit)
            return
        }
        if (city.getCenterTile().aerialDistanceTo(unit.currentTile) <= 1) {
            createGreatWork(unit)
            return
        }
        // The plot in or next to the city reached soonest. As in the original, the city tile itself is the target only when no
        // neighbour can be reached (its turn count is never kept as the best)
        var best: Tile? = if (turnsToReach(unit, city.getCenterTile()) < Int.MAX_VALUE) city.getCenterTile() else null
        var bestTurns = Int.MAX_VALUE
        for (tile in city.getCenterTile().neighbors) {
            val turns = turnsToReach(unit, tile)
            if (turns < bestTurns) {
                best = tile
                bestTurns = turns
            }
        }
        if (best == null) return
        moveTo(unit, best)
        if (bestTurns == 0 && unit.currentTile == best) createGreatWork(unit)
    }

    private fun createGreatWork(unit: MapUnit) {
        if (!unit.hasMovement() || !GreatWorkFunctions.canCreateWork(unit)) return
        val unique = unit.getMatchingUniques(UniqueType.OneTimeCreateGreatWork).firstOrNull() ?: return
        if (UniqueTriggerActivation.triggerUnique(unique, unit.civ, unit = unit, tile = unit.currentTile))
            UnitActionModifiers.activateSideEffects(unit, unique)
    }

    /** CvHomelandAI::ExecuteCultureBlast: a political treatise if possible, else to safety */
    private fun executeCultureBlast(unit: MapUnit) {
        if (!UnitActions.invokeUnitAction(unit, UnitActionType.HurryPolicy)) CivilianSafety.moveToSafety(unit)
    }

    /** CvHomelandAI::ExecuteGoldenAgeMove: a golden age if possible, else to safety */
    private fun executeGoldenAgeMove(unit: MapUnit) {
        val goldenAge = UnitActions.getUnitActions(unit, UnitActionType.TriggerUnique)
            .firstOrNull {
                it.action != null && (it.associatedUnique?.type == UniqueType.OneTimeEnterGoldenAge
                    || it.associatedUnique?.type == UniqueType.OneTimeEnterGoldenAgeTurns)
            }
        if (goldenAge != null) goldenAge.action!!.invoke()
        else CivilianSafety.moveToSafety(unit)
    }

    /** CvHomelandAI::ExecuteScientistMoves. The two uses have their own spans, so simulations count bulbs and academy moves */
    private fun executeScientistMoves(unit: MapUnit, directive: GreatPeopleDirective): Boolean {
        when (directive) {
            GreatPeopleDirective.UsePower -> timeThis("AI.GreatPeople.ScientistBulb") {
                if (!UnitActions.invokeUnitAction(unit, UnitActionType.HurryResearch)) CivilianSafety.moveToSafety(unit)
            }
            GreatPeopleDirective.ConstructImprovement -> timeThis("AI.GreatPeople.ScientistAcademy") {
                SpecificUnitAutomation.automateImprovementPlacer(unit)
            }
            GreatPeopleDirective.GoldenAge -> executeGoldenAgeMove(unit)
            else -> CivilianSafety.moveToSafety(unit)
        }
        return true
    }

    /** CvHomelandAI::ExecuteMerchantMoves (USE_POWER is the MERCHANT_DELEGATION operation) */
    private fun executeMerchantMoves(unit: MapUnit, directive: GreatPeopleDirective): Boolean {
        when (directive) {
            GreatPeopleDirective.UsePower -> startOperation(unit, GreatPersonRole.Merchant)
            GreatPeopleDirective.ConstructImprovement -> SpecificUnitAutomation.automateImprovementPlacer(unit)
            GreatPeopleDirective.GoldenAge -> executeGoldenAgeMove(unit)
            else -> CivilianSafety.moveToSafety(unit)
        }
        return true
    }

    /**
     *  CvHomelandAI::ExecuteEngineerMoves. USE_POWER: the wonder of CvWonderProductionAI::ChooseWonderForGreatEngineer. If the wonder city
     *  builds that wonder, is under a third done and the engineer gets there in under a third of the turns it still needs, hurry it there;
     *  else go to the city chosen for the wonder, switch its production to it and hurry it (unless it is done next turn anyway).
     */
    private fun executeEngineerMoves(unit: MapUnit, directive: GreatPeopleDirective): Boolean {
        when (directive) {
            GreatPeopleDirective.ConstructImprovement -> SpecificUnitAutomation.automateImprovementPlacer(unit)
            GreatPeopleDirective.GoldenAge -> executeGoldenAgeMove(unit)
            GreatPeopleDirective.UsePower -> executeWonderHurry(unit)
            else -> CivilianSafety.moveToSafety(unit)
        }
        return true
    }

    private fun executeWonderHurry(unit: MapUnit) {
        val civ = unit.civ
        val choice = WonderProductionAi.chooseWonderForGreatEngineer(civ)
        if (choice == null) {
            CivilianSafety.moveToSafety(unit)
            return
        }
        val wonder = choice.wonder
        val specializationCity = WonderProductionAi.wonderBuildCity(civ)
        if (specializationCity != null) {
            val constructions = specializationCity.cityConstructions
            val building = constructions.currentConstructionName() == wonder.name
            val productionSoFar = constructions.getWorkDone(wonder.name)
            val productionNeeded = wonder.getProductionCost(civ, specializationCity)
            if (building && productionSoFar * 3 < productionNeeded) {
                val turnsRemaining = constructions.turnsToConstruction(wonder.name)
                val turnsToTarget = turnsToReach(unit, specializationCity.getCenterTile())
                if (turnsToTarget < Int.MAX_VALUE && turnsToTarget * 3 < turnsRemaining) {
                    if (unit.currentTile == specializationCity.getCenterTile()) hurry(unit)
                    else moveTo(unit, specializationCity.getCenterTile())
                    return
                }
            }
        }
        val city = choice.city ?: return
        if (unit.currentTile == city.getCenterTile()) {
            city.cityConstructions.editQueue {
                remove(wonder.name)
                add(0, wonder.name)
            }
            if (city.cityConstructions.turnsToConstruction(wonder.name) > 1) hurry(unit)
        } else moveTo(unit, city.getCenterTile())
    }

    private fun hurry(unit: MapUnit) {
        if (!UnitActions.invokeUnitAction(unit, UnitActionType.HurryBuilding)) UnitActions.invokeUnitAction(unit, UnitActionType.HurryWonder)
    }

    /**
     *  Starts the MERCHANT_DELEGATION or CONCERT_TOUR operation (EconomicAIHelpers::IsTestStrategy_TradeWithCityState / _ConcertTour launch it
     *  for a loose Great Person with that directive): the target is FindBestMerchantTargetPlot / FindBestMusicianTargetPlot.
     */
    private fun startOperation(unit: MapUnit, role: GreatPersonRole) {
        val target = if (role == GreatPersonRole.Merchant) findBestMerchantTargetPlot(unit) else findBestMusicianTargetPlot(unit)
        if (target == null) {
            CivilianSafety.moveToSafety(unit)
            return
        }
        unit.greatPersonTarget = target.position
        continueOperation(unit, role)
    }

    /**
     *  CvAIOperationMerchantDelegation / CvAIOperationConcertTour::ArmyInPosition: at the target, trade / perform the concert; at the target
     *  but unable to (the city-state was conquered, the borders closed), retarget; else move on. No target any more aborts the operation.
     *  Deviation: the operations' escort is not formed (the military operations are not ported).
     */
    private fun continueOperation(unit: MapUnit, role: GreatPersonRole): Boolean {
        val target = unit.greatPersonTarget?.let { unit.civ.gameInfo.tileMap[it] } ?: return false
        // The operation's army cannot reach its target any more: retarget, as when it cannot act there
        if (unit.currentTile != target && moveTo(unit, target)) {
            if (unit.currentTile != target || !unit.hasMovement()) return true
        }
        if (unit.currentTile == target && performAtTarget(unit, role)) return true
        // Can no longer act here: retarget (RetargetCivilian)
        val newTarget = if (role == GreatPersonRole.Merchant) findBestMerchantTargetPlot(unit) else findBestMusicianTargetPlot(unit)
        unit.greatPersonTarget = newTarget?.position
        if (newTarget == null) CivilianSafety.moveToSafety(unit)
        return true
    }

    /**
     *  At the operation's target: the trade mission (MISSION_TRADE; Unciv has no city-state purchase, the alternative of Venice's merchant
     *  in GreatMerchantWantsCash) or the concert tour (MISSION_ONE_SHOT_TOURISM). Returns whether it happened.
     */
    private fun performAtTarget(unit: MapUnit, role: GreatPersonRole): Boolean {
        if (role == GreatPersonRole.Merchant) {
            val done = UnitActions.invokeUnitAction(unit, UnitActionType.ConductTradeMission)
            if (done) unit.greatPersonTarget = null
            return done
        }
        val unique = unit.getMatchingUniques(UniqueType.OneTimeConcertTour).firstOrNull() ?: return false
        if (!UniqueTriggerActivation.triggerUnique(unique, unit.civ, unit = unit, tile = unit.currentTile)) return false
        UnitActionModifiers.activateSideEffects(unit, unique)
        unit.greatPersonTarget = null
        return true
    }

    /**
     *  CvHomelandAI::ExecuteGeneralMoves for a general the homeland AI controls: USE_POWER plants a citadel at
     *  [findBestArtistTargetPlot]; otherwise it stays in, or goes to, the nearest own city without another general or admiral, avoiding the
     *  capital once the Apollo Program is built and our holy city.
     */
    private fun executeGeneralMoves(unit: MapUnit, directive: GreatPeopleDirective): Boolean {
        if (directive == GreatPeopleDirective.UsePower) {
            val target = findBestArtistTargetPlot(unit)
            if (target != null) {
                if (unit.currentTile == target) buildCitadel(unit)
                else moveTo(unit, target)
                return true
            }
        }
        val civ = unit.civ
        val apollo = isSpaceshipHomestretch(civ)
        val holyCity = civ.religionManager.getHolyCity()?.takeIf { it.civ == civ }
        val here = unit.currentTile
        val city = here.getCity()
        if (here.isCityCenter() && city?.civ == civ && !(apollo && city.isCapital()) && city != holyCity) return true
        var bestTurns = Int.MAX_VALUE
        var best: Tile? = null
        for (candidate in civ.cities) {
            if (apollo && candidate.isCapital() || candidate == holyCity) continue
            val tile = candidate.getCenterTile()
            if (tile.getUnits().any { it != unit && isGeneralOrAdmiral(it) }) continue
            val turns = turnsToReach(unit, tile)
            if (turns < bestTurns) {
                bestTurns = turns
                best = tile
            }
        }
        if (best != null) moveTo(unit, best)
        return true
    }

    private fun isGeneralOrAdmiral(unit: MapUnit): Boolean {
        val role = roleOf(unit.baseUnit, unit.civ.gameInfo.ruleset)
        return role == GreatPersonRole.General || role == GreatPersonRole.Admiral
    }

    private fun buildCitadel(unit: MapUnit) {
        UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, unit.currentTile)
            .firstOrNull { it.action != null }?.action?.invoke()
    }

    /**
     *  CvHomelandAI::ExecuteAdmiralMoves: the coastal own city (not the capital once the Apollo Program is built, not a coastal holy city of ours,
     *  without another general or admiral, reachable unless we are in a city) with the largest adjacent water body, divided by its damage, minus
     *  the turns to get there. Stay if already there, else go there.
     */
    private fun executeAdmiralMoves(unit: MapUnit): Boolean {
        val civ = unit.civ
        val apollo = isSpaceshipHomestretch(civ)
        val holyCity = civ.religionManager.getHolyCity()?.takeIf { it.civ == civ && it.isCoastal() }
        val notAtFriendlyCity = !unit.currentTile.isCityCenter() || unit.currentTile.getOwner() != civ
        var best: City? = null
        var bestWeight = Int.MIN_VALUE
        for (city in civ.cities) {
            if (apollo && city.isCapital() || city == holyCity || !city.isCoastal()) continue
            val tile = city.getCenterTile()
            if (tile.getUnits().any { it != unit && isGeneralOrAdmiral(it) }) continue
            val turns = turnsToReach(unit, tile)
            if (notAtFriendlyCity && turns == Int.MAX_VALUE) continue
            var weight = largestAdjacentWater(tile)
            val damage = city.getMaxHealth() - city.health
            if (damage > 0) weight /= damage
            if (turns != Int.MAX_VALUE) weight -= turns
            if (weight > bestWeight) {
                bestWeight = weight
                best = city
            }
        }
        if (best != null && unit.currentTile != best.getCenterTile()) moveTo(unit, best.getCenterTile())
        return true
    }

    /** The game's water bodies: for each water tile, the size of its connected body of water (computed once per map) */
    private val waterBodySizes = WeakHashMap<TileMap, HashMap<Tile, Int>>()

    /** CvPlot::GetSizeLargestAdjacentWater: the size of the largest body of water next to [tile] */
    private fun largestAdjacentWater(tile: Tile): Int {
        val sizes = waterBodySizes.getOrPut(tile.tileMap) { HashMap() }
        var largest = 0
        for (neighbor in tile.neighbors) {
            if (!neighbor.isWater) continue
            val size = sizes[neighbor] ?: run {
                val body = BFS(neighbor) { it.isWater }.apply { stepToEnd() }.getReachedTiles()
                for (water in body) sizes[water] = body.size
                body.size
            }
            if (size > largest) largest = size
        }
        return largest
    }


    //endregion

    //region Free Great People (CvPlayerAI::AI_chooseFreeGreatPerson, CvPlayerTraits::ChooseMayaBoost)

    /** Spends the free Great People of an AI civilization: Maya Long Count picks by [mayaBoostChoice], others by [freeGreatPersonChoice] */
    fun chooseFreeGreatPeople(civ: Civilization) = timeThis("AI.FreeGreatPerson") {
        val manager = civ.greatPeople
        while (manager.freeGreatPeople > 0) {
            val options = manager.getFreeGreatPersonOptions()
            if (options.isEmpty()) return@timeThis
            val choice = (if (manager.mayaLimitedFreeGP > 0) mayaBoostChoice(civ, options) else freeGreatPersonChoice(civ, options))
                ?: return@timeThis
            if (manager.chooseFreeGreatPerson(choice.name) == null) return@timeThis
        }
    }

    /**
     *  CvPlayerAI::AI_chooseFreeGreatPerson: an Engineer if WonderCompetitiveness ≥ 8 in the first half of the game; else by grand strategy:
     *  Conquest a General, Culture an Artist, United Nations a Merchant, Spaceship a Scientist. Null (keep the pick) without a grand strategy
     *  or when that person is not offered.
     */
    fun freeGreatPersonChoice(civ: Civilization, options: Collection<BaseUnit>): BaseUnit? {
        val role = if (civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness) >= 8
            && civ.gameInfo.turns <= estimateEndTurn(civ.gameInfo) / 2) GreatPersonRole.Engineer
        else when (civ.ai.grandStrategy) {
            GrandStrategyAi.CONQUEST -> GreatPersonRole.General
            GrandStrategyAi.CULTURE -> GreatPersonRole.Artist
            GrandStrategyAi.UNITED_NATIONS -> GreatPersonRole.Merchant
            GrandStrategyAi.SPACESHIP -> GreatPersonRole.Scientist
            else -> return null
        }
        return options.firstOrNull { roleOf(it, civ.gameInfo.ruleset) == role }
    }

    /**
     *  CvPlayerTraits::ChooseMayaBoost, among [options] (the Great People not taken yet in this Long Count cycle): a Prophet if our religion
     *  is founded but not enhanced, or we have none and religions can still be founded; else an Engineer if WonderCompetitiveness ≥ 8 in the
     *  first half of the game; else the grand strategy's (Spaceship Scientist, Culture Artist, United Nations Merchant, Conquest General);
     *  else the first offered of Prophet, Engineer, Writer, Merchant, Scientist, Artist, Musician, General, Admiral.
     */
    fun mayaBoostChoice(civ: Civilization, options: Collection<BaseUnit>): BaseUnit? {
        val ruleset = civ.gameInfo.ruleset
        fun offered(role: GreatPersonRole) = options.firstOrNull { roleOf(it, ruleset) == role }

        val religion = civ.religionManager
        val wantsProphet = when {
            religion.religionState >= ReligionState.Religion -> religion.religionState < ReligionState.EnhancedReligion
            else -> religion.remainingFoundableReligions() > 0
        }
        if (wantsProphet) offered(GreatPersonRole.Prophet)?.let { return it }
        if (civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness) >= 8
            && civ.gameInfo.turns <= estimateEndTurn(civ.gameInfo) / 2)
            offered(GreatPersonRole.Engineer)?.let { return it }
        val strategyRole = when (civ.ai.grandStrategy) {
            GrandStrategyAi.SPACESHIP -> GreatPersonRole.Scientist
            GrandStrategyAi.CULTURE -> GreatPersonRole.Artist
            GrandStrategyAi.UNITED_NATIONS -> GreatPersonRole.Merchant
            GrandStrategyAi.CONQUEST -> GreatPersonRole.General
            else -> null
        }
        if (strategyRole != null) offered(strategyRole)?.let { return it }
        for (role in MAYA_FALLBACK_ORDER) offered(role)?.let { return it }
        return null
    }

    private val MAYA_FALLBACK_ORDER = listOf(GreatPersonRole.Prophet, GreatPersonRole.Engineer, GreatPersonRole.Writer, GreatPersonRole.Merchant,
        GreatPersonRole.Scientist, GreatPersonRole.Artist, GreatPersonRole.Musician, GreatPersonRole.General, GreatPersonRole.Admiral)
    //endregion
}
