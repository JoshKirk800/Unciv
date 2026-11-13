package com.unciv.models

import com.unciv.utils.withHash
import java.lang.reflect.Modifier

/** Used as a member of [ModOptions][com.unciv.models.ruleset.ModOptions] for moddable "constants" - factors in formulae and such.
 *
 *  When combining mods, this is [merge]d _per constant/field_, not as entire object like other RulesetObjects.
 *  Merging happens on a very simple basis: If a Mod comes with a non-default value, it is copied, otherwise the parent value is left intact.
 *  If several mods change the same field, the last one wins.
 *
 *  Supports equality contract to enable the Json serializer to recognize unchanged defaults.
 *
 *  Methods [merge], [equals], [hashCode] and [toString] are done through reflection!
 *  Therefore, adding a field will not need to update these methods, but all members ***must*** conform to the equality contract.
 *  (overhead is not a factor, these routines run very rarely. The alternative would be to make the entire thing a data class.)
 */
class ModConstants {
    // Max amount of experience that can be gained from combat with barbarians
    @Suppress("SpellCheckingInspection") // Pfrom is not a word ;)
    var maxXPfromBarbarians = 30

    // Formula for city Strength:
    // Strength = baseStrength + strengthPerPop + strengthFromTiles +
    //            ((%techs * multiplier) ^ exponent) * fullMultiplier +
    //            (garrisonBonus * garrisonUnitStrength * garrisonUnitHealth/100) +
    //            defensiveBuildingStrength
    // where %techs is the percentage of techs in the tech tree that are complete
    // If no techs exist in this ruleset, %techs = 0.5 (=50%)
    var cityStrengthBase = 8.0
    var cityStrengthPerPop = 0.4
    var cityStrengthFromTechsMultiplier = 5.5
    var cityStrengthFromTechsExponent = 2.8
    var cityStrengthFromTechsFullMultiplier = 1.0
    var cityStrengthFromGarrison = 0.2

    // Formula for Unit Supply:
    // Supply = unitSupplyBase (difficulties.json)
    //          unitSupplyPerCity * amountOfCities + (difficulties.json)
    //          unitSupplyPerPopulation * amountOfPopulationInAllCities
    // unitSupplyBase and unitSupplyPerCity can be found in difficulties.json
    // unitSupplyBase, unitSupplyPerCity and unitSupplyPerPopulation can also be increased through uniques
    var unitSupplyPerPopulation = 0.5

    // Gold maintenance of units (CvTreasury::CalculateUnitCost), with 0.5 gold per unit that is not free:
    // cost = (paidUnits * 0.5 * (1 + unitMaintenanceGameMultiplier * progress)) ^ (1 + progress / unitMaintenanceExponentDivisor)
    // where progress is the turn divided by the number of turns of the game speed. The defaults are the original Unciv values; the Civ V rulesets use 0, 8 and 7.
    var unitMaintenanceFreeUnits = 3
    var unitMaintenanceGameMultiplier = 1f
    var unitMaintenanceExponentDivisor = 3f

    // The minimal distance that must be between any two cities, not counting the tiles cities are on
    // The number is the amount of tiles between two cities, not counting the tiles the cities are on.
    // e.g. "C__C", where "C" is a tile with a city and "_" is a tile without a city, has a distance of 2.
    // First constant is for cities on the same landmass, the second is for cities on different continents.
    var minimalCityDistance = 3
    var minimalCityDistanceOnDifferentContinents = 2

    var baseCityBombardRange = 2
    var cityWorkRange = 3
    var cityExpandRange = 5
    
    // Number of air units that can be stationed in a city
    var cityAirUnitCapacity = 6

    /** The extras of the Civ V resource placement (see CivVResourceExtras): a bonus at the third ring of each start, more bonuses in hills regions,
     *  small coal, oil or aluminum at the city states, top-ups of iron, horses, coal, oil, aluminum and uranium per civ, and no sugar on jungle.
     *  Replaces Unciv's own versions of these. It names the resources of Civ V. */
    var resourceExtrasLikeCivV = false

    /** Deal the regions to the civs as AssignStartingPlots:BalanceAndAssign does (see CivVStartAssignment): a random one of the fitting regions
     *  for each civ, by groups (the sea, preferred, avoided types), civs with the `Chooses its start region before...` unique first. */
    var startAssignmentLikeCivV = false

    /** Assign and place the luxuries as AssignStartingPlots.lua does (see CivVLuxuryPlacement). It names the luxuries of Civ V.
     *  false: the generic placement of the resources' `Generated with weight` rules. */
    var luxuryPlacementLikeCivV = false

    /** Make the terrain and the features of land as the Civ V map scripts do (see CivVTerrainGenerator): the base terrain by latitude
     *  with fractal noise, rivers and lakes as MapGenerator.lua places them, then jungle, forest, marsh, oases, flood plains, ice and atolls
     *  from fractal thresholds. It names the terrains and features of the Civ V rulesets.
     *  false: the temperature and humidity of the terrain definitions, and the generic rivers. */
    var terrainGeneratorLikeCivV = false

    /** AssignStartingPlots:PlaceOilInTheSea. The strategic resource that is also found in the sea, but only as many deposits as
     *  a quarter of the half of the units of it on land (one in the sea per 4 units of the Coast deposit, Civ V: "Oil").
     *  Empty: the resource is placed in the sea like on any terrain, by its "Generated with weight" rule for it. */
    var seaDepositsOfLandResource = ""

    /** Normalize starts as AssignStartingPlots:NormalizeStartLocation and NormalizeCityState do (see CivVStartNormalizer).
     *  It names the terrains and resources of the Civ V rulesets, so it only makes sense for them. */
    var startNormalizerLikeCivV = false
    /** Choose and place the natural wonders as PlaceNaturalWonders does: as many as the world size gives (2 on Duel to 7 on Huge, or fewer if
     *  fewer have a candidate tile), drawn one by one by their weights from the wonders that have a candidate tile and placed in the order drawn,
     *  not the order of the fewest candidate tiles. The wonders that fail to place are replaced by the next ones drawn. */
    var naturalWondersLikeCivV = false
    /** How the map generator widens the coast. Empty: the generic way (`maxCoastExtension` passes, an ocean tile next to a coast tile
     *  becomes coast with 1 in 2). Otherwise as GenerateCoasts of MapGenerator.lua: ocean next to land becomes coast, then one pass
     *  for each entry in which an ocean tile next to a coast tile becomes coast with 1 in that number (Civ V: 4 and 4). */
    var coastExpansionDice: List<Int> = emptyList()

    // The culture a city needs for its next tile. false: 6 x (tiles claimed + 1.4813)^1.3 with the percentages multiplied.
    // true: the formula of Civ V (CvCity::GetJONSCultureThreshold), 15 + (10 x tiles gained by culture)^1.1, x 1.5 for city-states,
    // the percentages added and at least -85, x game speed, rounded down to 5
    var cultureCostLikeCivV = false

    // What happens to a treasury that the turn's income would take below zero. false: it goes into debt (which costs science).
    // true: as in Civ V (CvTreasury::DoGold) it is set to 0 and the rest of the deficit is lost; a civilization never has negative gold
    var treasuryNeverNegative = false

    // Where the small deposits of strategic resources ("Minor deposits generated with weight") may be put, and how many.
    // minorDepositsOnFlatLandOnly false: on any land tile that is not blocked. true: as in AssignStartingPlots:GenerateGlobalResourcePlotLists
    // and PlaceSmallQuantitiesOfStrategics, only on land_list: flat land (no hills, mountains, marsh, flood plains, oasis or wonders) that has no feature
    // but forest or jungle, no resource and is no start. minorDepositFrequency: one deposit for this many tiles of that list (Civ V: 23)
    var minorDepositsOnFlatLandOnly = false
    var minorDepositFrequency = 30

    // The happiness an annexed city of a conquered civilization costs. Default: its citizens count double, and the city costs 2 more ("Occupied City").
    // The Civ V rulesets: citizens x UNHAPPINESS_PER_OCCUPIED_POPULATION (1.34, CvPlayer::GetUnhappinessFromOccupiedCities) and no extra term
    var annexedCityCitizenUnhappinessFactor = 2f
    var annexedCityFlatUnhappiness = 2f
    // The damage (of 100 hit points) from a nuclear blast at which a civilian unit dies. 60: it dies when its health is down to 40 or less.
    // Civ V (CvUnitCombat::ApplyNuclearExplosionDamage, NUKE_NON_COMBAT_DEATH_THRESHOLD): 6, so every civilian in the blast dies
    var nukeCivilianDeathDamage = 60
    // The gold a disbanded unit gives, in percent of its production cost (times its health left and the game speed, plus what it carries).
    // 0: 5% of its purchase price, whatever its health. Civ V (CvUnit::GetScrapGold, DISBAND_UNIT_REFUND_PERCENT): 25
    var disbandRefundPercent = 0
    // Movement points extra for entering a hill that has a feature (forest, jungle, fallout...) on top, besides the feature's own cost.
    // 0: the feature's cost only. Civ V (CvUnitMovement::GetCostsForMove, HILLS_EXTRA_MOVEMENT): 1, so a forested hill costs 3
    var featureOnHillsExtraMovement = 0
    // The divisor of the production cost of a building to get the gold for selling it. 10: the raw cost / 10 (Unciv).
    // Civ V (CvCityBuildings::GetSellBuildingRefund): the production cost (game speed, era and modifiers included) / 4
    var buildingSaleDivisor = 10
    // The share of the alive major civilizations that must know a technology for the city-states to get it for free.
    // 0: more than half of them (Unciv). Civ V (CvTeam::DoMinorCivTech, GlobalDefines MINOR_CIV_TECH_PERCENT 40): at least max(1, majors x percent / 100)
    var minorCivTechPercent = 0
    // The percentage of the production stored in a unit or non-wonder building that is converted to gold (as for wonders, always 100%) when its limit is reached
    // and it can no longer be built, and of the production above the overflow cap when it is completed. 0: it is lost (Unciv).
    // Civ V: GlobalDefines MAXED_UNIT_GOLD_PERCENT and MAXED_BUILDING_GOLD_PERCENT, both 100 (CvCity::doCheckProduction, CvCity::popOrder)
    var maxedUnitGoldPercent = 0
    var maxedBuildingGoldPercent = 0
    // What a damaged city heals each turn. false: 20. true: as in Civ V (CvCity::doTurn) 20 (CITY_HIT_POINTS_HEALED_PER_TURN),
    // +1 in the capital of a major civilization, + (strength of the city's buildings x 100) / 500
    var cityHealLikeCivV = false
    // The countdowns of a city's demand for a resource. false: the turns until a city demands a resource are 15 + random(10) + 10 in a new capital, and both
    // that and the length of the We Love the King Day (20) are scaled by the game speed. true: as in Civ V (CvCity::DoSeedResourceDemandedCountdown, DoTestResourceDemanded)
    // the capital adds 25 (RESOURCE_DEMAND_COUNTDOWN_CAPITAL_ADD) and neither is scaled by the game speed
    var resourceDemandLikeCivV = false
    // How much more combat points the next Great General or Admiral needs. false: 50 more each. true: as in Civ V
    // (CvPlayer::createGreatGeneral) the threshold of 200 grows by 100 x (earned / 10 + 1) with the number earned so far
    var greatGeneralThresholdLikeCivV = false
    // Percent of extra Great General points a land unit earns from fighting on a tile of its own civilization's territory, added to the
    // other bonuses (CvPlayer::recomputeGreatPeopleModifiers, CvUnit::changeExperience: COMBAT_EXPERIENCE_IN_BORDERS_PERCENT)
    var greatGeneralPercentInBorders = 0
    // Whether a unit that "Withdraws before melee combat" does so as in Civ V (CvUnit::CanWithdrawFromMelee, DoWithdrawFromMelee). false: it
    // withdraws when the chance of its unique comes up, to a random tile behind it. true: the chances of all its such uniques are added,
    // lowered by withdrawModEnemyMoves for each move of the attacker above 2 and by withdrawModBlockedTile for each of the three tiles behind
    // that it cannot retreat to, with no withdrawal at all if all three are blocked; it retreats to the tile straight behind it if it can
    var withdrawLikeCivV = false
    /** Percentage points added to the withdraw chance for each move the attacker has above 2 (WITHDRAW_MOD_ENEMY_MOVES); used if [withdrawLikeCivV] */
    var withdrawModEnemyMoves = -20
    /** Percentage points added to the withdraw chance for each blocked tile behind the unit (WITHDRAW_MOD_BLOCKED_TILE); used if [withdrawLikeCivV] */
    var withdrawModBlockedTile = -20
    // Whether a city can bombard an enemy city within its range. false: it cannot, only the units outside cities, as in Civ V
    // (CvCity::canRangeStrikeAt: "I don't want cities attacking each other directly")
    var citiesCanBombardCities = true
    // What taking tiles of a city-state ([UniqueType.OneTimeTakeOverTilesInRadius], the culture bomb) does to its influence with the taker.
    // The change is applied for each tile taken, or if cityStateInfluenceOncePerTilesTaken once for each city-state tiles were taken from
    // (CvUnit::PerformCultureBomb: CULTURE_BOMB_MINOR_FRIENDSHIP_CHANGE -50)
    var cityStateInfluenceChangeForTilesTaken = -15f
    var cityStateInfluenceOncePerTilesTaken = false

    // Constants used to calculate Unit Upgrade gold Cost (can only be modded all-or-nothing)
    // This is a data class for one reason only: The equality implementation enables Gdx Json to omit it when default (otherwise only the individual fields are omitted)
    data class UnitUpgradeCost(
        val base: Float = 10f,
        val perProduction: Float = 2f,
        val eraMultiplier: Float = 0f,  // 0.3 in Civ5 cpp sources but 0 in xml
        val exponent: Float = 1f,
        val roundTo: Int = 5
    )
    var unitUpgradeCost = UnitUpgradeCost()

    // How the "[relativeAmount]% Gold cost of upgrading" uniques of a civilization combine. false: they are multiplied (-33% twice gives x0.4489).
    // true: as in Civ V (CvPlayer::SetUnitUpgradeCostMod, CvUnit::upgradePrice) the percentages are summed (-33% twice gives x0.34) and the sum is at least [unitUpgradeCostDiscountMax]
    var unitUpgradeCostModsAreSummed = false
    // The lowest sum of the percentages of the upgrade cost uniques, only used with unitUpgradeCostModsAreSummed (GlobalDefines UNIT_UPGRADE_COST_DISCOUNT_MAX)
    var unitUpgradeCostDiscountMax = -75

    // NaturalWonderGenerator uses these to determine the number of Natural Wonders to spawn for a given map size.
    // With these values, radius * mul + add gives a 1-2-3-4-5 progression for Unciv predefined map sizes and a 2-3-4-5-6-7 progression for the original Civ5 map sizes.
    // 0.124 = (Civ5.Huge.getHexagonalRadiusForArea(w*h) - Civ5.Duel.getHexagonalRadiusForArea(w*h)) / 5 (if you do not round in the radius function)
    // The other constant is empiric to avoid an ugly jump in the progression.
    var naturalWonderCountMultiplier = 0.124f
    var naturalWonderCountAddedConstant = 0.1f

    // MapGenerator.spreadAncientRuins: number of ruins = suitable tile count * this
    var ancientRuinCountMultiplier = 0.025f
    /** Place the ancient ruins as AddGoodies of the Civ V scripts does: per landmass of at least 3 tiles one per [tilesPerAncientRuinLikeCivV]
     *  land tiles (rounded to nearest), on tiles at least 3 away from each other and more than 3 from any start, without resource.
     *  Replaces [ancientRuinCountMultiplier] and the spreading over the whole map. */
    var ancientRuinsLikeCivV = false
    var tilesPerAncientRuinLikeCivV = 40
    // MapGenerator.spawnLakesAndCoasts: Water bodies up to this tile count become Lakes
    var maxLakeSize = 10
    // RiverGenerator: river frequency and length bounds
    var riverCountMultiplier = 0.01f
    var minRiverLength = 5
    var maxRiverLength = 666  // Do not set to less than the maximal map radius. Set to 0 to disable river generation.

    // Factors in formula for Maximum Number of foundable Religions
    var religionLimitBase = 1
    var religionLimitMultiplier = 0.5f

    // Factors in formula for pantheon cost
    var pantheonBase = 10
    var pantheonGrowth = 5

    // AI behaviour
    var workboatAutomationSearchMaxTiles = 37

    // Civilization
    var minimumWarDuration = 10
    var baseTurnsUntilRevolt = 4
    var cityStateElectionTurns = 15
    // false: cityStateElectionTurns is the interval on every game speed. true: as in Civ V (CvGame::GetTurnsBetweenMinorCivElections) it is multiplied by the modifier of the game speed
    // (MinorCivElectionFreqMod 67, 100, 150, 300 percent for Quick to Marathon, equal to the speed modifier of the Civ V rulesets), rounded down
    var cityStateElectionsScaleWithGameSpeed = false

    // Economy
    var maxGoldTradeOffer = 10000

    // City State Tribute: maximum points from Force ranking towards reaching Tribute willingness threshold
    var tributeGlobalModifier = 100 // 75 in BNW
    var tributeLocalModifier = 100 // 125 in BNW

    // Espionage
    var maxSpyRank = 3
    // How much of a skill bonus each rank gives.
    // Rank 0 is 100, rank 1 is 130, and so on.
    // Half as much for a coup.
    var spyRankSkillPercentBonus = 30
    // Rank 2 is +25% tech steal rate, rank 3 is +50%, and so on
    var spyRankStealPercentBonus = 25
    // Steal cost equal to 125% of the most expensive stealable tech
    var spyTechStealCostModifier = 1.25f
    
    // Score value of things
    var scoreFromPopulation = 3 // 4 in BNW
    var scoreFromWonders = 40 // 25 in BNW
    var scoreFromGreatWorks = 0 // 4 in BNW
    // The map size at which Cities, Population and Land score at face value; smaller maps score more, larger maps less. 4160 (80 x 52) in Civ V
    var scoreBaseMapTiles = 1276

    // Trade routes (CvTradeClasses.cpp and GlobalDefines.xml; the values are those of Brave New World)
    // Gold of a route for its origin: base + (the gold of the origin city divided by this + the same of the destination city) + bonuses,
    // multiplied by the percentages below. The destination gets the base and its own bonuses, multiplied the same way
    var tradeRouteBaseGold = 1f
    var tradeRouteCityGoldDivisor = 20
    var tradeRouteGoldPerDifferentResource = 0.5f
    var tradeRouteSeaBonusPercent = 100
    var tradeRouteRiverBonusPercent = 25
    // A route lasts the whole number of round trips that comes closest to this many turns (but at least two round trips)
    var tradeRouteTargetTurns = 30
    // Gold for plundering a route. A route by sea is multiplied by the sea bonus
    var tradeRoutePlunderGold = 100
    // Routes within the own civilization: food or production in the destination city, base + this per era of the destination civilization
    var tradeRouteDomesticBaseYield = 3f
    var tradeRouteDomesticYieldPerEra = 0.5f

    // World Congress (CvVotingClasses.cpp, GlobalDefines.xml; the values are those of Brave New World)
    /** Civilizations that propose resolutions in a session (LEAGUE_PROPOSERS_PER_SESSION) */
    var leagueProposersPerSession = 2
    /** Resolutions each of them proposes */
    var leagueProposalsPerProposer = 1
    /** The turns ahead of a session that the player is warned */
    var leagueSessionSoonWarningTurns = 5
    /** Turns between sessions when no era has sessions, at Standard speed */
    var leagueFallbackSessionInterval = 12
    /** Delegates of each civilization when no era has sessions */
    var leagueFallbackDelegates = 1
    /** Delegates that the civilizations leading the vote get more when the World Leader vote fails */
    var leagueLeadersVoteBonusCount = 2
    /** The votes needed to elect the World Leader are the sum of a civilization part and a city-state part, each `max(0, coefficient * ln(count) + constant)` (CvGame::DoUpdateDiploVictory) */
    var leagueWorldLeaderCivCoefficient = 1.443f
    var leagueWorldLeaderCivConstant = 7f
    var leagueWorldLeaderCityStateCoefficient = 16.023f
    var leagueWorldLeaderCityStateConstant = -13.758f
    /** Proposals the host of the World Congress makes in a regular session */
    var leagueRegularHostProposals = 1
    /** League projects: the contribution tier 1 and 2 begin at these multiples of a civilization's share of the cost (LEAGUE_PROJECT_REWARD_TIER_1_THRESHOLD and _2_) */
    var leagueProjectTier1Threshold = 0.5f
    var leagueProjectTier2Threshold = 1f
    /** League projects: the civilizations are told when a project has reached this percent of its cost (PROJECT_PROGRESS_PERCENT_WARNING) */
    var leagueProjectProgressWarningPercent = 33

    // Great Works: culture that each one gives its city, before the bonuses of policies
    var greatWorkBaseCulture = 2

    // Tourism (CvCultureClasses.cpp and GlobalDefines.xml, the values are those of Brave New World)
    // Tourism each Great Work gives its city, before bonuses
    var greatWorkBaseTourism = 2
    // Percent more tourism towards civilizations that share our religion, have open borders with us or are connected to us by trade routes.
    // The original takes the same value for all three
    var tourismModifierSharedReligion = 25
    var tourismModifierOpenBorders = 25
    var tourismModifierTradeRoute = 25
    // A concert tour is worth at least this much tourism, before the game speed
    var concertTourMinimumTourism = 100
    // A civilization is Exotic, Familiar, Popular, Influential or Dominant over another once its tourism towards it, added up over the game,
    // is this percent of the culture the other has made in the whole game
    var influencePercentExotic = 10
    var influencePercentFamiliar = 30
    var influencePercentPopular = 60
    var influencePercentInfluential = 100
    var influencePercentDominant = 200

    // Ideologies (CvPolicyClasses.cpp and GlobalDefines.xml, Brave New World)
    // Changing the ideology costs this many tenets (those that are left become free tenets) and this many turns of anarchy
    var ideologyTenetsLostOnSwitch = 2
    var ideologyAnarchyTurns = 2
    // Percent less tourism between civilizations that follow different ideologies
    var tourismModifierDifferentIdeologies = -34

    // The public opinion gets worse (Dissidents, Civil Resistance, Revolutionary Wave) from these differences of the pressure of other ideologies on the own
    var publicOpinionCivilResistance = 3
    var publicOpinionRevolutionaryWave = 5

    // Archaeology (CvGame.cpp and GlobalDefines.xml, Brave New World). The sites appear once, with 5 to 8 for every major civilization;
    // this share of them is hidden until a policy is adopted, and this share of those holds a writing
    var archaeologySitesPerCivMin = 5
    var archaeologySitesPerCivMax = 8
    var archaeologyPercentHidden = 30
    var archaeologyPercentHiddenWriting = 30
    // The resources a site is, and the improvements that digging and a Landmark are
    var archaeologySiteResource = "Antiquity site"
    var archaeologyHiddenSiteResource = "Hidden Antiquity site"
    var archaeologicalDigImprovement = "Archaeological Dig"
    var landmarkImprovement = "Landmark"
    // The technology that makes the visible sites known, whose civilizations are told when they appear
    var archaeologySiteTech = "Archaeology"
    // Influence with a city-state when we build a Landmark on its land
    var landmarkInfluenceWithCityStates = 50

    // Great People from points (CvCityCitizens::GetSpecialistUpgradeThreshold, GREAT_PERSON_THRESHOLD_BASE and _INCREASE in GlobalDefines.xml):
    // a city needs base + increase × (Great People of the group gained so far) points, scaled by game speed and starting era
    var greatPersonThresholdBase = 100
    var greatPersonThresholdIncrease = 100

    // UI: If set >= 0, ImprovementPicker will silently skip improvements whose tech requirement is more advanced than your current Era + this
    var maxImprovementTechErasForward = -1

    fun merge(other: ModConstants) {
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            val value = field.get(other)
            if (field.get(defaults).equals(value)) continue
            field.set(this, value)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModConstants) return false
        return equalsReflected(other)
    }

    override fun hashCode(): Int {
        // Note: This is of course heavily dependent on iteration order.
        // Java reflection uses declaration order, but its doc claims "The elements in the returned array are not sorted and are not in any particular order."
        // kotlin reflection is alphabetically sorted. Embarrassingly, the best documentation guarantee seems to be: https://youtrack.jetbrains.com/issue/KT-41042
        // But - let's rely on at least the order being deterministic over instances of the same class in both reflection engines, so which we use is moot.
        // A kotlin version of this (different result!): `this::class.declaredMemberProperties.fold(0) { a, b -> a * 31 + b.getter.call(this).hashCode() }`
        var result = 0
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            result = result.withHash(field.get(this).hashCode())
        }
        return result
    }

    private fun equalsReflected(other: ModConstants): Boolean {
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (!field.get(this).equals(field.get(other))) return false
        }
        return true
    }

    /** Debug only so far */
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        for (field in this::class.java.declaredFields) {
            if (field.modifiers and Modifier.STATIC != 0) continue
            if (field.get(this).equals(field.get(defaults))) continue
            sb.append(field.name)
            sb.append(':')
            sb.append(field.get(this))
            sb.append(',')
        }
        sb.deleteCharAt(sb.length - 1) // remove extra ',' with StringBuilder method
        sb.append('}')
        return sb.toString().takeUnless { it == "}" } ?: "defaults"
    }

    companion object {
        /** As merge will need a default instance repeatedly, store it as static */
        // Note Json will not use this but get a fresh instance every time. A fix, if possible, could get messy.
        val defaults = ModConstants()
    }
}
