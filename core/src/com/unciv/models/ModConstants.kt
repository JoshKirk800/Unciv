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
    /** How the map generator widens the coast. Empty: the generic way (`maxCoastExtension` passes, an ocean tile next to a coast tile
     *  becomes coast with 1 in 2). Otherwise as GenerateCoasts of MapGenerator.lua: ocean next to land becomes coast, then one pass
     *  for each entry in which an ocean tile next to a coast tile becomes coast with 1 in that number (Civ V: 4 and 4). */
    var coastExpansionDice: List<Int> = emptyList()

    // The culture a city needs for its next tile. false: 6 x (tiles claimed + 1.4813)^1.3 with the percentages multiplied.
    // true: the formula of Civ V (CvCity::GetJONSCultureThreshold), 15 + (10 x tiles gained by culture)^1.1, x 1.5 for city-states,
    // the percentages added and at least -85, x game speed, rounded down to 5
    var cultureCostLikeCivV = false

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

    // NaturalWonderGenerator uses these to determine the number of Natural Wonders to spawn for a given map size.
    // With these values, radius * mul + add gives a 1-2-3-4-5 progression for Unciv predefined map sizes and a 2-3-4-5-6-7 progression for the original Civ5 map sizes.
    // 0.124 = (Civ5.Huge.getHexagonalRadiusForArea(w*h) - Civ5.Duel.getHexagonalRadiusForArea(w*h)) / 5 (if you do not round in the radius function)
    // The other constant is empiric to avoid an ugly jump in the progression.
    var naturalWonderCountMultiplier = 0.124f
    var naturalWonderCountAddedConstant = 0.1f

    // MapGenerator.spreadAncientRuins: number of ruins = suitable tile count * this
    var ancientRuinCountMultiplier = 0.025f
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
