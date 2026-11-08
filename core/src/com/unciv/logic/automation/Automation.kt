package com.unciv.logic.automation

import com.unciv.view.GameView
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.city.CityStats
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stats
import com.unciv.ui.screens.victoryscreen.RankingType
import yairm210.purity.annotations.Readonly


object Automation {

    /** Determines whether [civInfo] should be allocating military to fending off barbarians */
    fun afraidOfBarbarians(civView: CivView): Boolean {
        val civInfo = civView.getCiv()
        if (civInfo.isCityState || civInfo.isBarbarian)
            return false

        if (civInfo.gameInfo.gameParameters.noBarbarians)
            return false // If there are no barbarians we are not afraid

        val speed = civInfo.gameInfo.speed
        if (civInfo.gameInfo.turns > 200 * speed.barbarianModifier)
            return false // Very late in the game we are not afraid

        var multiplier = if (civInfo.gameInfo.gameParameters.ragingBarbarians) 1.3f
        else 1f // We're slightly more afraid of raging barbs

        // Past the early game we are less afraid
        if (civInfo.gameInfo.turns > 120 * speed.barbarianModifier * multiplier)
            multiplier /= 2

        // If we have no cities or a lot of units we are not afraid
        if (civInfo.cities.isEmpty() || civInfo.units.getCivUnits().count() >= 4 * multiplier)
            return false

        // If we have vision of our entire starting continent (ish) we are not afraid
        civInfo.gameInfo.tileMap.assignContinents(TileMap.AssignContinentsMode.Ensure)
        val startingContinent = civInfo.getCapital()!!.getCenterTile().getContinent()
        val startingContinentSize = civInfo.gameInfo.tileMap.continentSizes[startingContinent]
        if (startingContinentSize != null && startingContinentSize < civInfo.viewableTiles.size * multiplier)
            return false

        // Otherwise we're afraid
        return true
    }

    /** Determines whether the AI should be willing to spend strategic resources to build
     *  [construction] for [civInfo], assumes that we are actually able to do so. */
    @Readonly
    fun allowSpendingResource(civView: CivView, construction: INonPerpetualConstruction, cityInfo: City? = null): Boolean {
        val civInfo = civView.getCiv()
        // City states do whatever they want
        if (civInfo.isCityState)
            return true

        // Spaceships are always allowed
        if (construction.name in civInfo.gameInfo.spaceResources)
            return true

        val requiredResources = if (construction is BaseUnit)
            construction.getResourceRequirementsPerTurn(civInfo.state)
        else construction.getResourceRequirementsPerTurn(cityInfo?.state ?: civInfo.state)
        // Does it even require any resources?
        if (requiredResources.isEmpty())
            return true

        val civResources = civInfo.getCivResourcesByName()

        // Rule of thumb: reserve 2-3 for spaceship, then reserve half each for buildings and units
        // Assume that no buildings provide any resources
        for ((resource, amount) in requiredResources) {

            // Also count things under construction
            var futureForUnits = 0
            var futureForBuildings = 0

            for (city in civInfo.cities) {
                val otherConstruction = city.cityConstructions.getCurrentConstruction()
                if (otherConstruction is Building)
                    futureForBuildings += otherConstruction.getResourceRequirementsPerTurn(city.state)[resource]
                else
                    futureForUnits += otherConstruction.getResourceRequirementsPerTurn(civInfo.state)[resource]
            }

            // Make sure we have some for space
            if (resource in civInfo.gameInfo.spaceResources && civResources[resource]!! - amount - futureForBuildings - futureForUnits < 2) {
                return false
            }

            // Assume buildings remain useful
            val neededForBuilding = civInfo.cache.lastEraResourceUsedForBuilding[resource] != null
            // Don't care about old units
            val neededForUnits = civInfo.cache.lastEraResourceUsedForUnit[resource] != null
                    && civInfo.cache.lastEraResourceUsedForUnit[resource]!! >= civInfo.getEraNumber()

            // No need to save for both
            if (!neededForBuilding || !neededForUnits) {
                continue
            }

            val usedForUnits = civInfo.detailedCivResources.filter { it.resource.name == resource && it.origin == "Units" }.sumOf { -it.amount }
            val usedForBuildings = civInfo.detailedCivResources.filter { it.resource.name == resource && it.origin == "Buildings" }.sumOf { -it.amount }

            if (construction is Building) {
                // Will more than half the total resources be used for buildings after this construction?
                if (civResources[resource]!! + usedForUnits < usedForBuildings + amount + futureForBuildings) {
                    return false
                }
            } else {
                // Will more than half the total resources be used for units after this construction?
                if (civResources[resource]!! + usedForBuildings < usedForUnits + amount + futureForUnits) {
                    return false
                }
            }
        }
        return true
    }

    @Readonly
    fun threatAssessment(assessorView: CivView, assessedView: ForeignCivView): ThreatLevel {
        val assessor = assessorView.getCiv()
        val assessed = assessedView.getCiv()
        val powerLevelComparison =
            assessed.getStatForRanking(RankingType.Force) / assessor.getStatForRanking(RankingType.Force).toFloat()
        return when {
            powerLevelComparison > 2 -> ThreatLevel.VeryHigh
            powerLevelComparison > 1.5f -> ThreatLevel.High
            powerLevelComparison < 0.5f -> ThreatLevel.VeryLow
            powerLevelComparison < (1 / 1.5f) -> ThreatLevel.Low
            else -> ThreatLevel.Medium
        }
    }
    @Readonly
    private fun improvementIsRemovable(city: City, tile: Tile): Boolean {
        val gameContext = GameContext(city.civ, city, tile = tile)
        val improvement = tile.tileImprovement ?: return false
        return !improvement.hasUnique(UniqueType.AutomatedUnitsWillNotReplace, gameContext) &&
            !improvement.hasUnique(UniqueType.Irremovable, gameContext)
    }

    /** Support [UniqueType.CreatesOneImprovement] unique - find best tile for placement automation */
    @Readonly
    fun getTileForConstructionImprovement(city: City, improvement: TileImprovement): Tile? {
        val civ = city.civ
        val civView = GameView(civ.gameInfo, civ).civView
        return city.getTiles().filter {
            (it.tileImprovement == null || improvementIsRemovable(city, it))
                && it.improvementFunctions.canBuildImprovement(improvement, city.state)
        }.maxByOrNull { 
            // Needs to take into account future improvement layouts, and better placement of citadel-like improvements
            rankStatsValue(it.stats.getStatDiffForImprovement(improvement, civ, city, it.stats.getTileStats(city, civ)), civView) + (
                if (improvement.hasUnique(UniqueType.DefensiveBonus)) { 
                    it.aerialDistanceTo(city.getCenterTile()) + it.getDefensiveBonus(false) } 
                else 0).toFloat()
        }
    }

    // Ranks a tile for any purpose except the expansion algorithm of cities
    @Readonly
    internal fun rankTile(tile: Tile?, civView: CivView): Float {
        val civInfo = civView.getCiv()
        if (tile == null) return 0f
        val tileOwner = tile.getOwner()
        if (tileOwner != null && tileOwner != civInfo) return 0f // Already belongs to another civilization, useless to us
        val stats = tile.stats.getTileStats(null, civInfo)
        var rank = rankStatsValue(stats, civView)
        if (tile.improvement == null) rank += 0.5f // improvement potential!
        if (tile.isPillaged()) rank += 0.6f
        val resource = tile.tileResource
        if (civInfo.canSeeResource(resource)) {
            if (resource.resourceType != ResourceType.Bonus) rank += 1f // for usage
            if (tile.improvement == null) rank += 1f // improvement potential - resources give lots when improved!
            if (tile.isPillaged()) rank += 1.1f // even better, repair is faster
        }
        return rank
    }

    @Readonly
    fun rankStatsValue(stats: Stats, civView: CivView): Float {
        val civInfo = civView.getCiv()
        var rank = 0.0f
        rank += stats.food * 1.2f //food get more value to keep city growing

        rank += if (civInfo.gold < 0 && civInfo.stats.statsForNextTurn.gold <= 0)
                    stats.gold //build more gold infrastructure if in serious gold problems
        // This could lead to oscilliatory behaviour however: gold problem -> build trade post -> no gold problem -> replace trade posts -> gold problem
                else
                    stats.gold / 3 // Gold is valued less than is the case for citizen assignment,
        //otherwise the AI would replace tiles with trade posts upon entering a golden age,
        //and replace the trade post again when the golden age ends.
        // We need a way to take golden age gold into account before the GA actually takes place
        rank += stats.happiness
        rank += stats.production
        rank += stats.science
        rank += stats.culture
        rank += stats.faith
        return rank
    }
}

enum class ThreatLevel {
    VeryLow,
    Low,
    Medium,
    High,
    VeryHigh
}
