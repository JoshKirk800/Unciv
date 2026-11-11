package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.sqrt

/**
 *  One dominance zone of the tactical analysis map (CvTacticalDominanceZone): the tiles of one kind of land around one city (or one
 *  landmass of unclaimed land, or one temporary zone) and how strong each side is in it.
 *
 *  Strengths are the original's integers: a unit counts its strength considering damage x (tactical range + 4 - distance to the zone's
 *  city) x AI_TACTICAL_MAP_UNIT_STRENGTH_MULTIPLIER x tactical range, a city its strength x100 x range x hit points / MAX_CITY_HIT_POINTS.
 */
class DominanceZone(
    /** CvTacticalDominanceZone::GetDominanceZoneID: the index at the time of creation, what [TacticalMap.zoneOf] and the cells refer to */
    val id: Int,
    val territory: TacticalTerritory,
    /** The owner of the first tile of the zone, null for none (and always for temporary zones) */
    val owner: Civilization?,
    /** The landmass or body of water of the zone (CvPlot::getArea) */
    val area: Int,
    val isWater: Boolean,
    /** The nearest city of [owner] to the first tile of the zone, null if unowned (CvTacticalDominanceZone::GetClosestCity) */
    val closestCity: City?,
    /** The center of a [TacticalTerritory.TempZone] */
    val tempZoneCenter: Tile? = null,
    val isNavalInvasion: Boolean = false,
) {
    var friendlyStrength = 0
    var enemyStrength = 0
    var friendlyRangedStrength = 0
    var enemyRangedStrength = 0
    var friendlyUnitCount = 0
    var enemyUnitCount = 0
    var friendlyRangedUnitCount = 0
    var enemyRangedUnitCount = 0
    var enemyNavalUnitCount = 0
    /** Distance in tiles from the zone's city to the closest visible enemy unit, [Int.MAX_VALUE] if none (CvTacticalDominanceZone::GetRangeClosestEnemyUnit) */
    var rangeClosestEnemyUnit = Int.MAX_VALUE
    var dominance = TacticalDominance.NoUnitsVisible
    /** CvTacticalDominanceZone::GetDominanceZoneValue, set by [TacticalMap]'s PrioritizeZones */
    var value = 0

    /** CvTacticalDominanceZone::GetZoneType is not needed: [territory] and [dominance] say the same */
    override fun toString() = "Zone $id $territory ${closestCity?.name ?: "-"} ${if (isWater) "water" else "land"} $dominance " +
        "friendly $friendlyStrength ($friendlyUnitCount) enemy $enemyStrength ($enemyUnitCount) value $value"
}

/**
 *  CvTacticalAnalysisMap for one civilization and turn ([TacticalState.map]): per tile revealed/visible, impassable terrain or territory,
 *  water/ocean, cities, ownership, defense modifier and the military and civilian units standing on it (cells, read by tile), and the
 *  dominance zones ([zones], most valuable first) with their strengths, dominance flag and value. Also, per tile, whether an enemy can
 *  attack it next turn ([isSubjectToAttack]) or move past it ([enemyCanMovePast]), worked out on first use.
 *
 *  Source: CvTacticalAnalysisMap::RefreshDataForNextPlayer, PopulateCell, AddToDominanceZones, FindExistingZone, CalculateMilitaryStrengths,
 *  PrioritizeZones, ComputeDominance, MarkCellsNearEnemy.
 *
 *  Deviations:
 *  - units of the cells are only recorded on visible tiles (the original reads every tile); the strength sums of city zones see
 *    all at-war units, half the strength for those we cannot see, as the original does;
 *  - a unit's strength is its base strength (see [PlotDanger]), its ranged strength the unit type's x100;
 *  - the game's era is the average of the living civilizations' eras (CvGame::getCurrentEra);
 *  - enemy territory is where the owner is at war with us (the original compares the plot's team with itself, so never sets it);
 *  - "an enemy can reach the plot" is the hex distance within the unit's base moves on its landmass or body of water instead of the
 *    ignore-units path finder (as in [PlotDanger]); the not-visible-to-enemy flag is not kept;
 *  - the dynamic flags of the attack routines (friendly turn end tile, within range of target, flank bonus) are here; the deployment
 *    ones (safe for deployment, deployment score) belong to the deployment code and are not.
 */
class TacticalMap(private val civ: Civilization, state: TacticalState) {
    private val rules = civ.gameInfo.ruleset.ai
    private val tileList = civ.gameInfo.tileMap.tileList
    private val numTiles = tileList.size

    /** The radius around its city in which a zone counts units: ((AI_TACTICAL_RECRUIT_RANGE + game era) x 2) / 3, growing as the game goes on */
    val tacticalRange: Int
    private val unitStrengthMultiplier: Int
    private val dominancePercentage = rules.define(AiDefine.AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE)

    /** The longest range of our units that count in a zone (CvTacticalAnalysisMap::GetBestFriendlyRange) */
    var bestFriendlyRange = 0
        private set
    /** Whether one of our units that count in a zone ignores line of sight (CvTacticalAnalysisMap::IsIgnoreLOS) */
    var ignoreLineOfSight = false
        private set

    private val flags = IntArray(numTiles)
    private val defenseModifiers = IntArray(numTiles)
    private val zoneIds = IntArray(numTiles) { -1 }
    private val areas = IntArray(numTiles)
    private val reach = ByteArray(numTiles)
    private val turnEndTiles = BooleanArray(numTiles)
    private val withinRangeOfTarget = BooleanArray(numTiles)
    private val flankBonus = BooleanArray(numTiles)
    private val friendlyMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val friendlyCivilian = arrayOfNulls<MapUnit>(numTiles)
    private val enemyMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val enemyCivilian = arrayOfNulls<MapUnit>(numTiles)
    private val neutralMilitary = arrayOfNulls<MapUnit>(numTiles)
    private val neutralCivilian = arrayOfNulls<MapUnit>(numTiles)

    private val atWar = HashMap<Civilization, Boolean>()
    private val zonesById = ArrayList<DominanceZone>()
    /** The dominance zones, the most valuable first (CvTacticalAnalysisMap::m_DominanceZones after PrioritizeZones) */
    val zones: List<DominanceZone> get() = sortedZones
    private var sortedZones: List<DominanceZone> = emptyList()
    /** The units of players at war with us that can attack (CvTacticalAnalysisMap::BuildEnemyUnitList) */
    private val enemyUnits = ArrayList<MapUnit>()

    private val tempZones = ArrayList<DominanceZone>()
    private val lumpedZones = HashMap<Int, DominanceZone>()
    private val exactZones = HashMap<ZoneKey, DominanceZone>()
    private val state = state

    private data class ZoneKey(val territory: TacticalTerritory, val owner: String?, val area: Int, val cityId: String?)

    init {
        tacticalRange = ((rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE) + gameEra()) * 2) / 3
        unitStrengthMultiplier = rules.define(AiDefine.AI_TACTICAL_MAP_UNIT_STRENGTH_MULTIPLIER) * tacticalRange
        timeThis("AI.TacticalMap") { build() }
    }

    private fun build() {
        computeAreas()
        addTemporaryZones()
        val visible = BooleanArray(numTiles)
        for (tile in civ.viewableTiles) visible[tile.zeroBasedIndex] = true
        for (tile in tileList) if (populateCell(tile, visible[tile.zeroBasedIndex])) addToDominanceZones(tile)
        calculateMilitaryStrengths()
        prioritizeZones()
        buildEnemyUnitList()
    }

    //region Cell access

    private fun has(tile: Tile, flag: Int) = flags[tile.zeroBasedIndex] and flag != 0

    fun isRevealed(tile: Tile) = has(tile, REVEALED)
    fun isVisible(tile: Tile) = has(tile, VISIBLE)
    fun isImpassableTerrain(tile: Tile) = has(tile, IMPASSABLE_TERRAIN)
    /** Owned by someone we are not at war with and who does not allow us in */
    fun isImpassableTerritory(tile: Tile) = has(tile, IMPASSABLE_TERRITORY)
    fun isWater(tile: Tile) = has(tile, WATER)
    fun isOcean(tile: Tile) = has(tile, OCEAN)
    fun isFriendlyCity(tile: Tile) = has(tile, FRIENDLY_CITY)
    fun isEnemyCity(tile: Tile) = has(tile, ENEMY_CITY)
    fun isNeutralCity(tile: Tile) = has(tile, NEUTRAL_CITY)
    fun isOwnTerritory(tile: Tile) = has(tile, OWN_TERRITORY)
    fun isFriendlyTerritory(tile: Tile) = has(tile, FRIENDLY_TERRITORY)
    fun isEnemyTerritory(tile: Tile) = has(tile, ENEMY_TERRITORY)
    fun isUnclaimedTerritory(tile: Tile) = has(tile, UNCLAIMED_TERRITORY)

    /** CvPlot::defenseModifier(NO_TEAM, true): the terrain's defense bonus in percent */
    fun defenseModifier(tile: Tile) = defenseModifiers[tile.zeroBasedIndex]

    fun friendlyMilitaryUnit(tile: Tile): MapUnit? = friendlyMilitary[tile.zeroBasedIndex]
    fun friendlyCivilianUnit(tile: Tile): MapUnit? = friendlyCivilian[tile.zeroBasedIndex]
    fun enemyMilitaryUnit(tile: Tile): MapUnit? = enemyMilitary[tile.zeroBasedIndex]
    fun enemyCivilianUnit(tile: Tile): MapUnit? = enemyCivilian[tile.zeroBasedIndex]
    fun neutralMilitaryUnit(tile: Tile): MapUnit? = neutralMilitary[tile.zeroBasedIndex]
    fun neutralCivilianUnit(tile: Tile): MapUnit? = neutralCivilian[tile.zeroBasedIndex]

    /** CvTacticalAnalysisCell::CanUseForOperationGathering */
    fun canUseForOperationGathering(tile: Tile): Boolean = !(isImpassableTerrain(tile) || isImpassableTerritory(tile)
        || enemyMilitaryUnit(tile) != null || neutralMilitaryUnit(tile) != null || neutralCivilianUnit(tile) != null
        || isFriendlyTurnEndTile(tile) || isEnemyCity(tile) || isNeutralCity(tile))

    /** CvTacticalAnalysisCell::CanUseForOperationGatheringCheckWater: as [canUseForOperationGathering], and the tile is water exactly if [water] */
    fun canUseForOperationGatheringCheckWater(tile: Tile, water: Boolean): Boolean = water == isWater(tile) && canUseForOperationGathering(tile)

    /** The landmass or body of water of the tile (CvPlot::getArea): land tiles by continent, water tiles by connected body of water */
    fun areaOf(tile: Tile) = areas[tile.zeroBasedIndex]

    /** The zone a tile belongs to (CvTacticalAnalysisCell::GetDominanceZone), null for unrevealed or impassable tiles */
    fun zoneOf(tile: Tile): DominanceZone? = zoneIds[tile.zeroBasedIndex].let { if (it < 0) null else zonesById[it] }

    /** CvTacticalAnalysisMap::IsInEnemyDominatedZone */
    fun isInEnemyDominatedZone(tile: Tile) = zoneOf(tile)?.dominance == TacticalDominance.Enemy

    /** CvTacticalAnalysisMap::GetZoneByCity: the zone [city] is the closest city of, on land or on water */
    fun zoneByCity(city: City, isWater: Boolean): DominanceZone? = sortedZones.firstOrNull { it.closestCity == city && it.isWater == isWater }

    /** CvTacticalAnalysisCell::IsSubjectToAttack: an enemy unit on the same landmass or sea can reach the tile in a turn, or a hostile citadel is next to it. Never in our cities. */
    fun isSubjectToAttack(tile: Tile): Boolean = reachOf(tile) and SUBJECT_TO_ATTACK != 0

    /** CvTacticalAnalysisCell::IsEnemyCanMovePast: an enemy unit can reach the tile and still have movement left (or is on it) */
    fun enemyCanMovePast(tile: Tile): Boolean = reachOf(tile) and CAN_MOVE_PAST != 0

    //endregion
    //region Dynamic cell data of the attack routines

    /** CvTacticalAnalysisCell::IsFriendlyTurnEndTile: one of our units finished its moves here this turn (set by [markFriendlyTurnEnd]) */
    fun isFriendlyTurnEndTile(tile: Tile) = turnEndTiles[tile.zeroBasedIndex]

    /** CvTacticalAI::UnitProcessed's marking of the cell, so that later routines do not plan onto a tile a unit has settled on */
    fun markFriendlyTurnEnd(tile: Tile) {
        turnEndTiles[tile.zeroBasedIndex] = true
    }

    /** CvTacticalAnalysisCell::IsWithinRangeOfTarget, set by [setTargetBombardCells] */
    fun isWithinRangeOfTarget(tile: Tile) = withinRangeOfTarget[tile.zeroBasedIndex]

    /** CvTacticalAnalysisCell::IsHelpsProvidesFlankBonus, set by [setTargetFlankBonusCells] */
    fun helpsProvideFlankBonus(tile: Tile) = flankBonus[tile.zeroBasedIndex]

    /** CvTacticalAnalysisMap::ClearDynamicFlags (the safe-for-deployment and deployment-score flags belong to the deployment code) */
    fun clearDynamicFlags() {
        withinRangeOfTarget.fill(false)
        flankBonus.fill(false)
    }

    /**
     *  CvTacticalAnalysisMap::SetTargetBombardCells: the revealed, passable tiles within [range] of [target], not enemy or neutral cities,
     *  with a line of sight to it (unless [ignoreLineOfSight]) from where a ranged unit could shoot at it.
     */
    fun setTargetBombardCells(target: Tile, range: Int, ignoreLineOfSight: Boolean) {
        for (tile in target.getTilesInDistance(range)) {
            if (tile == target || !isRevealed(tile) || isImpassableTerrain(tile) || isImpassableTerritory(tile)) continue
            if (isEnemyCity(tile) || isNeutralCity(tile)) continue
            if (ignoreLineOfSight || target in tile.tileMap.getViewableTiles(tile.position, range, true))
                withinRangeOfTarget[tile.zeroBasedIndex] = true
        }
    }

    /** CvTacticalAnalysisMap::SetTargetFlankBonusCells: the free tiles around a land [target] where a unit of ours would add a flank bonus */
    fun setTargetFlankBonusCells(target: Tile) {
        if (target.isWater) return
        for (tile in target.neighbors) {
            if (!isRevealed(tile) || isImpassableTerrain(tile) || isImpassableTerritory(tile)) continue
            if (isFriendlyCity(tile) || isEnemyCity(tile) || isNeutralCity(tile)) continue
            if (!isFriendlyTurnEndTile(tile) && enemyMilitaryUnit(tile) == null) flankBonus[tile.zeroBasedIndex] = true
        }
    }

    //endregion

    private fun gameEra(): Int {
        var sum = 0
        var count = 0
        for (other in civ.gameInfo.civilizations) {
            if (other.isBarbarian || other.isSpectator || !other.isAlive()) continue
            sum += other.getAiEraNumber()
            count++
        }
        return if (count == 0) 0 else sum / count
    }

    private fun isAtWarWith(other: Civilization) = atWar.getOrPut(other) { civ.isAtWarWith(other) }

    /** Land tiles by [Tile.getContinent], water tiles by their connected body of water (numbered above all continents) */
    private fun computeAreas() {
        var nextWaterArea = Int.MAX_VALUE / 2
        val queue = ArrayDeque<Tile>()
        for (tile in tileList) {
            if (!tile.isWater) {
                areas[tile.zeroBasedIndex] = tile.getContinent()
                continue
            }
            if (areas[tile.zeroBasedIndex] != 0) continue
            val area = nextWaterArea++
            areas[tile.zeroBasedIndex] = area
            queue.add(tile)
            while (queue.isNotEmpty()) {
                val current = queue.removeLast()
                for (neighbor in current.neighbors) {
                    if (!neighbor.isWater || areas[neighbor.zeroBasedIndex] != 0) continue
                    areas[neighbor.zeroBasedIndex] = area
                    queue.add(neighbor)
                }
            }
        }
    }

    /** CvTacticalAnalysisMap::AddTemporaryZones: temporary zones come first, so they take the unclaimed tiles near their center */
    private fun addTemporaryZones() {
        for (temporary in state.temporaryZones) {
            if (temporary.targetType == TacticalTargetType.City) continue // only boosts the priority of the city's zone
            val tile = civ.gameInfo.tileMap[temporary.position]
            val zone = DominanceZone(zonesById.size, TacticalTerritory.TempZone, null, areaOf(tile), tile.isWater, null, tile, temporary.navalInvasion)
            zonesById.add(zone)
            tempZones.add(zone)
        }
    }

    /** CvTacticalAnalysisMap::PopulateCell: false if the tile does not belong in a dominance zone */
    private fun populateCell(tile: Tile, visible: Boolean): Boolean {
        val index = tile.zeroBasedIndex
        var flag = 0
        val revealed = tile.isExplored(civ)
        if (revealed) flag = flag or REVEALED
        if (visible) flag = flag or VISIBLE
        val impassableTerrain = tile.isImpassible() || tile.baseTerrain == Constants.mountain
        if (impassableTerrain) flag = flag or IMPASSABLE_TERRAIN
        if (tile.isWater) flag = flag or WATER
        if (tile.isWater && tile.isOcean) flag = flag or OCEAN

        var impassableTerritory = false
        val owner = tile.getOwner()
        if (owner == null) flag = flag or UNCLAIMED_TERRITORY
        else {
            if (owner != civ && !owner.diplomacyFunctions.canPassThroughTiles(civ)) impassableTerritory = true
            else if (tile.isCityCenter()) flag = flag or when {
                owner == civ -> FRIENDLY_CITY
                isAtWarWith(owner) -> ENEMY_CITY
                else -> NEUTRAL_CITY
            }
            if (owner == civ) flag = flag or OWN_TERRITORY
            if (tile.isFriendlyTerritory(civ)) flag = flag or FRIENDLY_TERRITORY
            if (owner != civ && isAtWarWith(owner)) flag = flag or ENEMY_TERRITORY
        }
        if (impassableTerritory) flag = flag or IMPASSABLE_TERRITORY
        flags[index] = flag
        defenseModifiers[index] = (tile.getDefensiveBonus(includeImprovementBonus = true) * 100).toInt()

        if (visible) {
            tile.militaryUnit?.let { place(it, friendlyMilitary, enemyMilitary, neutralMilitary, index) }
            tile.civilianUnit?.let { place(it, friendlyCivilian, enemyCivilian, neutralCivilian, index) }
        }
        return !(impassableTerrain || impassableTerritory || !revealed)
    }

    private fun place(unit: MapUnit, friendly: Array<MapUnit?>, enemy: Array<MapUnit?>, neutral: Array<MapUnit?>, index: Int) {
        when {
            unit.civ == civ -> friendly[index] = unit
            isAtWarWith(unit.civ) -> enemy[index] = unit
            else -> neutral[index] = unit
        }
    }

    /** CvTacticalAnalysisMap::AddToDominanceZones */
    private fun addToDominanceZones(tile: Tile) {
        val index = tile.zeroBasedIndex
        val owner = tile.getOwner()
        val area = areas[index]
        val territory = when {
            owner == null -> TacticalTerritory.NoOwner
            owner == civ -> TacticalTerritory.Friendly
            isAtWarWith(owner) -> TacticalTerritory.Enemy
            else -> TacticalTerritory.Neutral
        }
        val closestCity = if (owner != null) closestCityOf(owner, tile) else null
        val isUnowned = territory == TacticalTerritory.NoOwner || territory == TacticalTerritory.Neutral

        val zone = findExistingZone(tile, territory, owner, area, closestCity, isUnowned)
            ?: DominanceZone(zonesById.size, territory, owner, area, tile.isWater, closestCity).also {
                zonesById.add(it)
                if (isUnowned) lumpedZones[area] = it
                else exactZones[ZoneKey(territory, owner?.civName, area, closestCity?.id)] = it
            }

        // Unclaimed land and temporary zones get the strength of the units standing in them
        if (zone.territory == TacticalTerritory.NoOwner || zone.territory == TacticalTerritory.TempZone) {
            friendlyMilitary[index]?.let { unit ->
                if (countsInZone(unit, zone)) {
                    zone.friendlyStrength += unitStrength(unit, zone) * unitStrengthMultiplier
                    zone.friendlyRangedStrength += rangedStrength(unit)
                    noteFriendlyRange(unit)
                    zone.friendlyUnitCount++
                    if (unit.baseUnit.isRanged()) zone.friendlyRangedUnitCount++
                }
            }
            enemyMilitary[index]?.let { unit ->
                if (countsInZone(unit, zone)) {
                    zone.enemyStrength += unitStrength(unit, zone) * unitStrengthMultiplier
                    zone.enemyRangedStrength += rangedStrength(unit)
                    zone.enemyUnitCount++
                    if (unit.baseUnit.isRanged()) zone.enemyRangedUnitCount++
                    if (unit.baseUnit.isWaterUnit) zone.enemyNavalUnitCount++
                }
            }
        }
        zoneIds[index] = zone.id
    }

    /** CvTacticalAnalysisMap::FindExistingZone: a temporary zone takes unclaimed tiles near its center, unclaimed and neutral land is one zone per area */
    private fun findExistingZone(tile: Tile, territory: TacticalTerritory, owner: Civilization?, area: Int, city: City?, isUnowned: Boolean): DominanceZone? {
        if (isUnowned) {
            for (zone in tempZones)
                if (tile.aerialDistanceTo(zone.tempZoneCenter!!) <= tacticalRange) return zone
            return lumpedZones[area]
        }
        return exactZones[ZoneKey(territory, owner?.civName, area, city?.id)]
    }

    private fun closestCityOf(owner: Civilization, tile: Tile): City? {
        var best: City? = null
        var bestDistance = Int.MAX_VALUE
        for (city in owner.cities) {
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            if (distance < bestDistance) {
                bestDistance = distance
                best = city
            }
        }
        return best
    }

    /** A unit counts in a zone if it is an air unit, or a land unit in a land zone, or a sea unit in a water zone */
    private fun countsInZone(unit: MapUnit, zone: DominanceZone): Boolean =
        unit.baseUnit.isAirUnit() || (unit.baseUnit.isLandUnit && !zone.isWater) || (unit.baseUnit.isWaterUnit && zone.isWater)

    private fun unitStrength(unit: MapUnit, zone: DominanceZone): Int {
        var strength = PlotDanger.strengthConsideringDamage(unit, rules)
        if (strength == 0 && unit.isEmbarked() && !zone.isWater) strength = unit.baseUnit.strength
        return strength
    }

    /** CvUnit::GetMaxRangedCombatStrength, in the original's x100 scale */
    private fun rangedStrength(unit: MapUnit) = unit.baseUnit.rangedStrength * 100

    private fun noteFriendlyRange(unit: MapUnit) {
        if (unit.getRange() > bestFriendlyRange) bestFriendlyRange = unit.getRange()
        if (unit.hasUnique(UniqueType.IndirectFire)) ignoreLineOfSight = true
    }

    /** CvTacticalAnalysisMap::CalculateMilitaryStrengths: the zones around a city add the city, our units and the at-war units within the tactical range of it */
    private fun calculateMilitaryStrengths() {
        val maxCityHitPoints = rules.define(AiDefine.MAX_CITY_HIT_POINTS)
        val ourUnits = civ.units.getCivUnits().filter { it.isMilitary() }.toList()
        val enemies = ArrayList<MapUnit>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) if (unit.isMilitary()) enemies.add(unit)
        }
        val visible = HashSet<Tile>(civ.viewableTiles)

        for (zone in zonesById) {
            if (zone.territory == TacticalTerritory.NoOwner) continue
            val city = zone.closestCity ?: continue
            val cityTile = city.getCenterTile()
            val cityStrength = tacticalRange * CityCombatant(city).getCityStrength() * 100 * city.health / maxCityHitPoints
            if (zone.territory == TacticalTerritory.Friendly) {
                zone.friendlyStrength += cityStrength
                zone.friendlyRangedStrength += CityCombatant(city).getCityStrength() * 100
            } else {
                zone.enemyStrength += cityStrength
                zone.enemyRangedStrength += CityCombatant(city).getCityStrength() * 100
            }

            for (unit in ourUnits) {
                if (!countsInZone(unit, zone)) continue
                val distance = unit.getTile().aerialDistanceTo(cityTile)
                if (distance > tacticalRange) continue
                val multiplier = tacticalRange + 4 - distance // "4" so unit strength isn't totally dominated by proximity to the city
                if (multiplier <= 0) continue
                zone.friendlyStrength += unitStrength(unit, zone) * multiplier * unitStrengthMultiplier
                zone.friendlyRangedStrength += rangedStrength(unit)
                noteFriendlyRange(unit)
                zone.friendlyUnitCount++
                if (unit.baseUnit.isRanged()) zone.friendlyRangedUnitCount++
            }

            for (unit in enemies) {
                if (!countsInZone(unit, zone)) continue
                val unitTile = unit.getTile()
                val distance = unitTile.aerialDistanceTo(cityTile)
                if (distance > tacticalRange) continue
                val multiplier = tacticalRange + 4 - distance
                if (multiplier <= 0) continue
                val isVisible = unitTile in visible || unitTile.neighbors.any { it in visible }
                var strength = unitStrength(unit, zone)
                var ranged = rangedStrength(unit)
                if (!isVisible) {
                    strength /= 2
                    ranged /= 2
                }
                zone.enemyStrength += strength * multiplier * unitStrengthMultiplier
                zone.enemyRangedStrength += ranged
                if (!isVisible) continue
                zone.enemyUnitCount++
                if (distance < zone.rangeClosestEnemyUnit) zone.rangeClosestEnemyUnit = distance
                if (unit.baseUnit.isRanged()) zone.enemyRangedUnitCount++
                if (unit.baseUnit.isWaterUnit) zone.enemyNavalUnitCount++
            }
        }
    }

    /** CvTacticalAnalysisMap::PrioritizeZones: dominance and value of every zone, then the zones sorted by value (stable) */
    private fun prioritizeZones() {
        val stateAllWars = if (civ.isCityState) StateAllWars.Neutral else WarStateAi.stateAllWars(civ)
        for (zone in zonesById) {
            val dominance = computeDominance(zone)
            var baseValue = 1
            val multiplier: Int
            if (zone.territory == TacticalTerritory.TempZone) multiplier = 1000
            else {
                val city = zone.closestCity
                if (city != null) {
                    baseValue += 1 + sqrt(city.population.population.toFloat()).toInt()
                    if (city.isCapital() && !city.civ.isCityState) baseValue *= 2
                    if (state.isTemporaryZoneCity(city)) baseValue *= 20
                    else if (isCityVisible(city)) {
                        baseValue *= 4
                        // How damaged is this visible city?
                        val maxHitPoints = city.getMaxHealth()
                        val damage = maxHitPoints - city.health
                        if (damage > maxHitPoints / MAX_DAMAGE_MULTIPLIER) baseValue *= (damage + 1) * 10 / maxHitPoints
                    }
                }
                if (!zone.isWater) baseValue *= 3

                var factor = 1
                when (dominance) {
                    TacticalDominance.Enemy -> factor = when (zone.territory) {
                        TacticalTerritory.Enemy -> 2
                        TacticalTerritory.Friendly -> 6
                        else -> 1
                    }
                    TacticalDominance.Even -> if (zone.territory == TacticalTerritory.Enemy || zone.territory == TacticalTerritory.Friendly) factor = 4
                    TacticalDominance.Friendly -> factor = if (zone.territory == TacticalTerritory.Enemy) 8 else 1
                    TacticalDominance.NoUnitsVisible -> {}
                }
                if (!civ.isCityState) {
                    if (stateAllWars == StateAllWars.Winning) {
                        if (zone.territory == TacticalTerritory.Enemy) factor *= 2
                    } else if (stateAllWars == StateAllWars.Losing) {
                        if (zone.territory == TacticalTerritory.Friendly) factor *= 4
                    }
                }
                multiplier = factor
            }
            zone.value = baseValue * multiplier
        }
        sortedZones = zonesById.sortedByDescending { it.value }
    }

    private fun isCityVisible(city: City): Boolean = city.civ == civ || city.getCenterTile() in civ.viewableTiles

    /** CvTacticalAnalysisMap::ComputeDominance: by the ratio of friendly to enemy strength against AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE */
    private fun computeDominance(zone: DominanceZone): TacticalDominance {
        zone.dominance = when {
            zone.territory != TacticalTerritory.Enemy && zone.enemyUnitCount <= 0 -> TacticalDominance.NoUnitsVisible
            zone.enemyStrength <= 0 -> TacticalDominance.Friendly
            else -> {
                val ratio = (zone.friendlyStrength.toLong() * 100 / zone.enemyStrength).toInt()
                when {
                    ratio > 100 + dominancePercentage -> TacticalDominance.Friendly
                    ratio < 100 - dominancePercentage -> TacticalDominance.Enemy
                    else -> TacticalDominance.Even
                }
            }
        }
        return zone.dominance
    }

    /** CvTacticalAnalysisMap::BuildEnemyUnitList: the units of players at war with us that can attack */
    private fun buildEnemyUnitList() {
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) if (unit.isMilitary()) enemyUnits.add(unit)
        }
    }

    /** CvTacticalAnalysisMap::MarkCellsNearEnemy for one tile, on first use */
    private fun reachOf(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        var value = reach[index].toInt()
        if (value == 0) {
            value = 1 or computeReach(tile)
            reach[index] = value.toByte()
        }
        return value
    }

    private fun computeReach(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        if (!has(tile, REVEALED) || has(tile, IMPASSABLE_TERRAIN) || has(tile, IMPASSABLE_TERRITORY) || has(tile, FRIENDLY_CITY)) return 0
        var result = 0
        val area = areas[index]
        for (enemy in enemyUnits) {
            val enemyTile = enemy.getTile()
            if (areas[enemyTile.zeroBasedIndex] != area) continue
            val distance = enemyTile.aerialDistanceTo(tile)
            if (distance == 0) return SUBJECT_TO_ATTACK or CAN_MOVE_PAST
            val baseMoves = enemy.baseUnit.movement
            if (distance > baseMoves) continue
            result = result or SUBJECT_TO_ATTACK
            if (distance < baseMoves) return SUBJECT_TO_ATTACK or CAN_MOVE_PAST
        }
        if (result == 0) for (neighbor in tile.neighbors) {
            val owner = neighbor.getOwner() ?: continue
            if (!isAtWarWith(owner)) continue
            if (neighbor.getUnpillagedTileImprovement()?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true) return SUBJECT_TO_ATTACK
        }
        return result
    }

    private companion object {
        const val REVEALED = 1
        const val VISIBLE = 2
        const val IMPASSABLE_TERRAIN = 4
        const val IMPASSABLE_TERRITORY = 8
        const val WATER = 16
        const val OCEAN = 32
        const val FRIENDLY_CITY = 64
        const val ENEMY_CITY = 128
        const val NEUTRAL_CITY = 256
        const val OWN_TERRITORY = 512
        const val FRIENDLY_TERRITORY = 1024
        const val ENEMY_TERRITORY = 2048
        const val UNCLAIMED_TERRITORY = 4096

        /** Bits of [reach]: 1 = computed */
        const val SUBJECT_TO_ATTACK = 2
        const val CAN_MOVE_PAST = 4

        /** The 10 of PrioritizeZones' iMaxDamageMultiplier: a city is only "damaged" above 1/10 of its hit points */
        const val MAX_DAMAGE_MULTIPLIER = 10
    }
}
