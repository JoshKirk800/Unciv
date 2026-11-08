package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.view.hasMovement
import com.unciv.view.isAirUnit
import com.unciv.view.isCivilian
import com.unciv.view.isNuclearWeapon
import com.unciv.view.TileView
import com.unciv.logic.automation.civilization.ThreatManager
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.automation.Automation
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.mapunit.actions.UnitActionsUpgrade
import kotlin.math.ceil
import com.unciv.view.GameView
import com.unciv.view.ForeignCityView
import com.unciv.view.isAtWar
import com.unciv.view.stateBasedRandom
import com.unciv.view.MapUnitView
import com.unciv.view.ForeignCivView
import com.unciv.view.CityView
import com.unciv.view.MapUnitCombatantView
import yairm210.purity.annotations.Readonly
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.NextTurnAutomation

object UnitAutomation {

    private const val CLOSE_ENEMY_TILES_AWAY_LIMIT = 5
    private const val CLOSE_ENEMY_TURNS_AWAY_LIMIT = 3f


    fun automateUnitMoves(unitView: MapUnitView, cache: AutomationCache = AutomationCache()):Unit = timeThis("automateUnitMoves") {
        val unit = unitView.getUnit()
        check(!unit.civ.isBarbarian) { "Barbarians is not allowed here." }
        // CvUnit::TurnProcessed: a routine that ran before (the tactical AI) already decided this unit's moves
        if (unit in cache.processedUnits) return
        // Might die next turn - move!
        if (unit.getDamageFromTerrain() > 0 && tryHealUnit(unitView, cache)) return


        if (unitView.isCivilian()) {
            CivilianUnitAutomation.automateCivilianUnit(unitView, cache)
            return
        }

        val isHumanAndNotAutoplaying =
            unit.civ.isHuman() && GUI.getWorldScreenIfActive()?.autoPlay?.isAutoPlaying() != true
        // AI promotes units via NextTurnAutomation.automateUnits
        if (isHumanAndNotAutoplaying) NextTurnAutomation.applyPromotions(unitView)
        // AI upgrades units via UseGoldAutomation in NextTurnAutomation
        if (isHumanAndNotAutoplaying && tryUpgradeUnit(unitView)) return

        //This allows for military units with certain civilian abilities to behave as civilians in peace and soldiers in war
        if ((unitView.hasUnique(UniqueType.BuildImprovements) || unitView.hasUnique(UniqueType.FoundCity) || 
                    unitView.hasUnique(UniqueType.FoundPuppetCity) ||
                    unitView.hasUnique(UniqueType.ReligiousUnit) || unitView.hasUnique(UniqueType.CreateWaterImprovements))
            && !unit.civ.isAtWar()){
            CivilianUnitAutomation.automateCivilianUnit(unitView, cache)
            return
        }

        // Note that not all nukes have to be air units
        if (unitView.isNuclearWeapon()) {
            return AirUnitAutomation.automateNukes(unitView)
        }

        if (unitView.isAirUnit()) {
            if (unit.canIntercept())
                return AirUnitAutomation.automateFighter(unitView, cache)

            if (unitView.hasUnique(UniqueType.SelfDestructs))
                return AirUnitAutomation.automateMissile(unitView)

            return AirUnitAutomation.automateBomber(unitView)
        }

        // Settlers get their escort from their operation (OperationMoves); Great People are accompanied here
        if (tryAccompanyGreatPerson(unitView)) return

        if (tryHeadTowardsOurSiegedCity(unitView)) return

        // if a embarked melee unit can land and attack next turn, do not attack from water.
        if (BattleHelper.tryDisembarkUnitToAttackPosition(unitView)) return

        // if there is an attackable unit in the vicinity, attack!
        if (tryAttacking(unitView, cache)) return

        if (tryTakeBackCapturedCity(unitView)) return

        // Focus all units without a specific target on the enemy city closest to one of our cities
        if (HeadTowardsEnemyCityAutomation.tryHeadTowardsEnemyCity(unitView)) return

        if (tryHeadTowardsEncampment(unitView)) return

        // move towards the closest reasonably attackable enemy unit within 3 turns of movement (and 5 tiles range)
        if (tryAdvanceTowardsCloseEnemy(unitView)) return

        if (tryPrepare(unitView)) return

        // What is left of the unit's turn is CvHomelandAI's: explore, garrison, sentry, patrol, ... (the civilization's whole military goes through
        // it in NextTurnAutomation.automateUnits; this is for a single automated unit)
        HomelandAi.automateUnit(unitView, cache)
    }

    /** One random step to a tile next to the unit, for the barbarians' idle units and units that can heal nowhere (a major's idle units patrol, [HomelandAi]) */
    fun wander(unitView: MapUnitView, stayInTerritory: Boolean = false, tilesToAvoid: Set<TileView> = setOf()) {
        if (!unitView.hasMovement()) return // return in case we can't move anyways
        val unitTile = unitView.getTile()
        val unitDistanceToTiles = unitTile.getExploredTilesAtDistance(1)
        // We could walk further, but wander() is meant to let units not stay on the same tile permanently,
        // to avoid obstructing human scouts and workers, moving just one tile should be enough
        val reachableTiles = unitDistanceToTiles
                .filter {
                    it !in tilesToAvoid
                        && unitView.canMoveTo(it)
                        && unitView.getDistanceToTiles().contains(it) // Yes this is required despite the above line - see #14461
                        && unitView.getDamageFromTerrain(it) <= 0 // Don't end turn on damaging terrain for no good reason
                        && (!stayInTerritory || it.getOwner() == unitView.civ() || unitTile.getOwner() != unitView.civ())
                }
        val rng = unitView.stateBasedRandom("UnitAutomation.wander")
        if (reachableTiles.any()) unitView.tryMoveToTile(reachableTiles.toList().random(rng))
    }

    internal fun tryUpgradeUnit(unitView: MapUnitView): Boolean {
        val civ = unitView.civ()
        if (civ.isHuman() && !UncivGame.Current.settings.automatedUnitsCanUpgrade
            && UncivGame.Current.worldScreen?.autoPlay?.isAutoPlayingAndFullAutoPlayAI() == false) return false

        val upgrade = unitView.getPossibleUpgrades()
            .filter { it.availability == UpgradeAvailability.Available // for resource reasons, usually
                && !(civ.isBarbarian() && it.upgradedUnit.hasUnique(UniqueType.CannotBeBarbarian)) }
            .minByOrNull { it.upgradedUnit.cost } ?: return false

        // The upgrade requires new resource types, so check if we are willing to invest them
        if (upgrade.newResourceRequirements.any { it.value > 0 }
            && !Automation.allowSpendingResource(civ, upgrade.upgradedUnit)) return false

        return unitView.tryUpgradeTo(upgrade.upgradedUnit)
    }


    /** @return true only if the unit has 0 movement left */
    private fun tryAttacking(unitView: MapUnitView, cache: AutomationCache): Boolean {
        repeat(unitView.maxAttacksPerTurn() - unitView.attacksThisTurn) {
            if (BattleHelper.tryAttackNearbyEnemy(unitView)) return true
            // Calvary style tctic, attack and then retreat
            if (unitView.unitHealth < 50 && TacticalSafety.moveToSafety(unitView.getUnit(), unitView.civ().getCiv().ai.plotDanger)) return true
        }
        return false
    }

    /**
     *  The attacks of a unit under tactical control ([TacticalAi]) until the tactical attack moves are ported: the unit AI's own attack, for
     *  units that are not hurt (a unit below half health retreats or heals instead).
     *  @return whether the unit's turn is over: it attacked or has no movement left
     */
    fun tryTacticalAttack(unitView: MapUnitView, cache: AutomationCache): Boolean {
        if (unitView.unitHealth < 50 || unitView.isCivilian()) return false
        val attacksBefore = unitView.attacksThisTurn
        return tryAttacking(unitView, cache) || unitView.attacksThisTurn > attacksBefore || !unitView.hasMovement()
    }

    private fun tryHeadTowardsEncampment(unitView: MapUnitView): Boolean {
        if (unitView.hasUnique(UniqueType.SelfDestructs)) return false // don't use single-use units against barbarians...
        val knownEncampments = unitView.civ().cities().asSequence()
            .flatMap { it.getCenterTile().getExploredTilesInDistance(6) }
                .filter { it.isBarbarianEncampment() }
            .distinct()
        val encampmentsCloseToCities = knownEncampments.asSequence()
            .sortedBy { it.aerialDistanceTo(unitView.getTile()) }
        val encampmentToHeadTowards = encampmentsCloseToCities.firstOrNull { unitView.canReach(it) }
            ?: return false
        unitView.tryHeadTowards(encampmentToHeadTowards)
        return true
    }

    private fun tryHealUnit(unitView: MapUnitView, cache: AutomationCache): Boolean {
        if (unitView.getBaseUnit().isRanged() && unitView.hasUnique(UniqueType.HealsEvenAfterAction))
            return false // will heal anyway, and attacks don't hurt

        // Try pillage improvements until healed
        while (tryPillageImprovement(unitView, true)) {
            // If we are fully healed and can still do things, lets keep on going by returning false
            if (!unitView.hasMovement() || unitView.unitHealth == 100) return !unitView.hasMovement()
        }

        val unitDistanceToTiles = unitView.getDistanceToTiles()
        if (unitDistanceToTiles.isEmpty()) return true // can't move, so...

        val danger = unitView.civ().getCiv().ai.plotDanger
        // The original's PlotDanger, and the tiles that would hurt the unit by their terrain
        fun isDangerous(tile: TileView) = danger.get(tile) > 0 || unitView.getDamageFromTerrain(tile) > 0

        // If the unit can heal on this tile in two turns, just heal here
        if (!isDangerous(unitView.getTile())
            && canUnitHealInTurnsOnCurrentTile(unitView, cache, 3)) return true

        val currentUnitTile = unitView.getTile()

        val viableTilesForHealing = unitDistanceToTiles.keys
                .filter { !isDangerous(it) && unitView.canMoveTo(it) }
        val tilesByHealingRate = viableTilesForHealing.groupBy { unitView.healingAmountForTile(it) }

        if (tilesByHealingRate.keys.all { it == 0 }) { // We can't heal here at all! We're probably embarked
            if (!unitView.isAirUnit()) {
                val reachableCityTile = unitView.civ().cities().asSequence()
                    .map { it.getCenterTile() }
                    .sortedBy { it.aerialDistanceTo(currentUnitTile) }
                    .firstOrNull { unitView.canReach(it) }
                if (reachableCityTile != null) unitView.tryHeadTowards(reachableCityTile)
                else wander(unitView)
                return true
            }
            // Try to get closer to an empty city
            val emptyCities = unitView.civ().cities().asSequence()
                .map { it.getCenterTile() }
                .filter { unitView.canMoveTo(it) }
            if (emptyCities.none()) return false // Nowhere to move to heal

            val nextTileToMove = unitDistanceToTiles.keys
                .filter { unitView.canMoveTo(it) }
                .minByOrNull { tile ->
                    emptyCities.minOf { city ->
                        city.aerialDistanceTo(tile)
                    }
                } ?: return false

            return unitView.tryMoveToTile(nextTileToMove)
        }

        val bestTilesForHealing = tilesByHealingRate.maxByOrNull { it.key }!!.value
        val bestTileForHealing = bestTilesForHealing.maxByOrNull { unitView.getDefensiveBonusOn(it) }!!
        val bestTileForHealingRank = unitView.healingAmountForTile(bestTileForHealing)

        if (currentUnitTile != bestTileForHealing
                && bestTileForHealingRank > unitView.healingAmountForTile(currentUnitTile) - unitView.getDamageFromTerrain(currentUnitTile))
            unitView.tryMoveToTile(bestTileForHealing)

        unitView.tryFortifyIfCan()
        return true
    }

    /**
     * @return true if the tile is safe and the unit can heal to full within [turns]
     */
    @Readonly
    private fun canUnitHealInTurnsOnCurrentTile(unitView: MapUnitView, cache: AutomationCache, turns: Int, noEnemyDistance: Int = 3): Boolean {
        if (unitView.hasUnique(UniqueType.HealsEvenAfterAction)) return false // We can keep on moving
        val unitTile = unitView.getTile()
        // Check if we are not in a safe city and there is an enemy nearby this isn't a good tile to heal on
        if (!(unitTile.isCityCenter() && unitTile.getCity()!!.getHealth() > 50)
            && ThreatManager.getDistanceToClosestEnemyUnit(unitView.civ(), cache, unitView.getTile(), noEnemyDistance) <= noEnemyDistance) return false

        // Round up, otherwise e.g. 99 health over 2 turns would give 0 required healing per turn
        val healthRequiredPerTurn = ceil((100 - unitView.unitHealth).toFloat() / turns).toInt()
        return healthRequiredPerTurn <= unitView.healingAmountForTile(unitTile)
    }

    /**
     * @return true if the unit was able to pillage a tile, false otherwise
     */
    fun tryPillageImprovement(unitView: MapUnitView, onlyPillageToHeal: Boolean = false): Boolean {
        if (unitView.isCivilian()) return false
        val civ = unitView.civ()
        val tilesThatCanWalkToAndThenPillage = unitView.getDistanceToTiles()
            .filter { it.value < unitView.currentMovement }.keys
            .filter { unitView.canMoveTo(it) && unitView.canPillage(it)
                    && (it.canPillageTileImprovement()
                    || (!onlyPillageToHeal && it.canPillageRoad() && it.getRoadOwner() != null && civ.isAtWarWith(it.getRoadOwner()!!))) }

        if (tilesThatCanWalkToAndThenPillage.isEmpty()) return false
        val tileToPillage = tilesThatCanWalkToAndThenPillage.maxBy { unitView.getDefensiveBonusOn(it, includeImprovementBonus = false) }
        if (unitView.getTile() != tileToPillage)
            unitView.tryMoveToTile(tileToPillage)

        if (unitView.getTile() != tileToPillage) return false

        return unitView.tryInvokeUnitAction(UnitActionType.Pillage)
    }

    /** Move towards the closest attackable enemy of the [unit].
     *
     *  Limited by [CLOSE_ENEMY_TURNS_AWAY_LIMIT] and [CLOSE_ENEMY_TILES_AWAY_LIMIT].
     *  Tiles attack from which would result in instant death of the [unit] are ignored. */
    private fun tryAdvanceTowardsCloseEnemy(unitView: MapUnitView): Boolean {
        val unit = unitView.getUnit()
        // this can be sped up if we check each layer separately
        val unitDistanceToTiles = unit.movement.getMovementToTilesAtPosition(
                unit.getTile().position,
                unit.getMaxMovement() * CLOSE_ENEMY_TURNS_AWAY_LIMIT
        )
        val closeEnemy = TargetHelper.getAttackableEnemies(unit, unitDistanceToTiles, tilesToCheck = unit.getTile().getTilesInDistance(CLOSE_ENEMY_TILES_AWAY_LIMIT).toList())
            .filterNot { unitView.getBaseUnit().isRanged() && it.tileToAttack.isCityCenter() && it.tileToAttack.getCity()!!.health == 1 } // occurs fairly often probably because AI dumb
            .filter { unit.getDamageFromTerrain(it.tileToAttackFrom) <= 0 }  // Don't attack from a mountain or near enemy citadels
            .filter {
            // Ignore units that would 1-shot you if you attacked - unless the unit takes the city, which it survives
            BattleDamage.attackerSurvives(MapUnitCombatant(unit), Battle.getMapCombatantOfTile(it.tileToAttack)!!) }
            .minByOrNull { it.tileToAttack.aerialDistanceTo(unit.getTile()) }

        if (closeEnemy != null) {
            unit.movement.headTowards(closeEnemy.tileToAttackFrom)
            return true
        }
        return false
    }

    /**
     * Tries to move the unit to the closest city that is close to a target civilization
     */
    private fun tryPrepare(unitView: MapUnitView): Boolean {
        val civ = unitView.civ()

        // Only our own side of the diplomacy is known to us - we can't see their flags,
        //  but if they denounced us we'll have the resulting opinion modifier
        @Readonly
        fun hasPreparationFlag(targetCiv: ForeignCivView): Boolean {
            val diploManager = civ.getDiplomacyManagerWith(targetCiv)!!
            if (diploManager.hasFlag(DiplomacyFlags.Denunciation)
                || diploManager.hasModifier(DiplomaticModifiers.Denunciation)) return true
            // CvDiplomacyAI war goal PREPARE: we are mustering an attack on them
            return civ.getCiv().ai.diplomacy.warGoal(targetCiv.getCiv()) == WarGoal.Prepare
        }

        // Prepare against the civs we're actually at war with - only if there are none, against the ones we might go to war with
        val knownCivs = civ.getKnownCivs()
        val hostileCivs = knownCivs.filter { civ.isAtWarWith(it) }.toSet()
            .ifEmpty { knownCivs.filter { hasPreparationFlag(it) }.toSet() }
        val closeCities = ThreatManager.getNeighboringCitiesOfOtherCivs(unitView.civ()).filter { it.second.owningCiv() in hostileCivs }
        val closestDistance = closeCities.minOfOrNull { it.first.getCenterTile().aerialDistanceTo(it.second.getCenterTile()) }
            ?: return false
        val citiesToDefend = closeCities
            .filter { it.first.getCenterTile().aerialDistanceTo(it.second.getCenterTile()) <= closestDistance + 2 }
            .map { it.first }
            .distinct() // Remove duplicate cities
            .sortedBy { unitView.getTile().aerialDistanceTo(it.getCenterTile()) }

        // Move to the closest city with a tile we can enter nearby
        for (city in citiesToDefend) {
            if (unitView.getTile().aerialDistanceTo(city.getCenterTile()) <= 2) return true
            val tileToMoveTo = city.getCenterTile().getExploredTilesInDistance(2)
                .firstOrNull { unitView.canMoveTo(it) && unitView.canReach(it) } ?: continue
            unitView.tryHeadTowards(tileToMoveTo)
            return true
        }
        return false
    }

    private fun tryAccompanyGreatPerson(unitView: MapUnitView): Boolean {
        val distanceToTiles = unitView.getDistanceToTiles()
        val settlerOrGreatPersonToAccompany = unitView.civ().getUnits()
            .firstOrNull {
                val tile = it.getTile()
                it.isCivilian() &&
                        it.getMatchingUniques(UniqueType.GreatPerson).any()
                        && !it.hasUnique(UniqueType.StrengthBonusInRadius) // Exlude great generals, as they move independently after all military units
                        && (tile == unitView.getTile() || tile.militaryUnit == null && unitView.canMoveTo(tile))
                        && distanceToTiles.containsKey(tile)
            } ?: return false
        unitView.tryHeadTowards(settlerOrGreatPersonToAccompany.getTile())
        return true
    }

    private fun tryHeadTowardsOurSiegedCity(unitView: MapUnitView): Boolean {
        val siegedCities = unitView.civ().cities()
                .asSequence()
                .filter { it.getHealth() < it.getMaxHealth() }

        if (siegedCities.any { it.getCenterTile().aerialDistanceTo(unitView.getTile()) <= 2 })
            return false

        val reachableTileNearSiegedCity = siegedCities
                .flatMap { it.getCenterTile().getExploredTilesAtDistance(2) }
                .sortedBy { it.aerialDistanceTo(unitView.getTile()) }
                .firstOrNull { unitView.canMoveTo(it) && unitView.canReach(it)
                        && unitView.getDamageFromTerrain(it) <= 0 } // Avoid ending up on damaging terrain

        if (reachableTileNearSiegedCity != null) {
            unitView.tryHeadTowards(reachableTileNearSiegedCity)
        }
        return !unitView.hasMovement()
    }


    fun tryEnterOwnClosestCity(unitView: MapUnitView): Boolean {
        val closestCity = unitView.civ().cities()
            .asSequence()
            .sortedBy { it.getCenterTile().aerialDistanceTo(unitView.getTile()) }
            .firstOrNull { unitView.canReach(it.getCenterTile()) }
          ?: return false // Panic!

        unitView.tryHeadTowards(closestCity.getCenterTile())
        return true
    }

    fun tryBombardEnemy(city: CityView): Boolean {
        if (!city.canBombard()) return false
        val cityCombatant = city.asCombatant()
        val targets = city.getBombardableTiles().mapNotNull { it.getCombatant() }
            // Don't bombard civilians (they're more efficiently captured/killed by military units if they're indeed this close)
            .filterNot { it is MapUnitCombatantView && it.getUnitView().isCivilian() }
        val target = targets.maxByOrNull { cityCombatant.calculateDamageToDefender(it) }
            ?: return false
        city.tryBombard(cityCombatant.buildBombardAttackableTile(target.getTile(), target))
        return true
    }

    private fun tryTakeBackCapturedCity(unitView: MapUnitView): Boolean {
        val unit = unitView.getUnit()
        var capturedCities = unit.civ.getKnownCivs() // This is a Sequence
                .flatMap { it.cities.asSequence() }
                .filter {
                    unit.civ.isAtWarWith(it.civ) &&
                            unit.civ == it.foundingCivObject &&
                            it.isInResistance() &&
                            it.health < it.getMaxHealth()
                } //Most likely just been captured


        if (unitView.getBaseUnit().isRanged()) // ranged units don't harm capturable cities, waste of a turn
            capturedCities = capturedCities.filterNot { it.health == 1 }

        val closestReachableCapturedCity = capturedCities
                .map { it.getCenterTile() }
                .sortedBy { it.aerialDistanceTo(unit.getTile()) }
                .firstOrNull { unit.movement.canReach(it) }

        if (closestReachableCapturedCity != null) {
            return HeadTowardsEnemyCityAutomation.headTowardsEnemyCity(
                unitView,
                closestReachableCapturedCity,
                // This should be cached after the `canReach` call above.
                unit.movement.getShortestPath(closestReachableCapturedCity)
            )
        }
        return false
    }

    /** This is what a unit with the 'explore' action does.
    It also explores, but also has other functions, like healing if necessary. */
    fun automatedExplore(unitView: MapUnitView, cache: AutomationCache = AutomationCache()) {
        val unit = unitView.getUnit()
        if (unitView.unitHealth < 80 && tryHealUnit(unitView, cache)) return
        if (HomelandAi.exploreUnit(unit, cache)) return
        unit.civ.addNotification("${unit.shortDisplayName()} finished exploring.", MapUnitAction(unit), NotificationCategory.Units, unit.name, "OtherIcons/Sleep")
        unit.action = null
    }


}
