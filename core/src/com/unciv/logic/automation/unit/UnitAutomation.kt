package com.unciv.logic.automation.unit

import com.unciv.logic.automation.unit.tactical.TacticalAi
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.view.isAirUnit
import com.unciv.view.isCivilian
import com.unciv.view.isNuclearWeapon
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.automation.Automation
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.UpgradeAvailability
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.mapunit.actions.UnitActionsUpgrade
import com.unciv.view.GameView
import com.unciv.view.ForeignCityView
import com.unciv.view.isAtWar
import com.unciv.view.MapUnitView
import com.unciv.view.CityView
import com.unciv.view.MapUnitCombatantView
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.NextTurnAutomation

object UnitAutomation {

    fun automateUnitMoves(unitView: MapUnitView, cache: AutomationCache = AutomationCache()):Unit = timeThis("automateUnitMoves") {
        val unit = unitView.getUnit()
        check(!unit.civ.isBarbarian) { "Barbarians is not allowed here." }
        // CvUnit::TurnProcessed: a routine that ran before (the tactical AI) already decided this unit's moves
        if (unit in cache.processedUnits) return

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

        // A human's automated unit has no tactical turn of its own before this (NextTurnAutomation.automateUnits gives AI and auto play one):
        // the tactical AI commands it on its own, for its attacks, safety and garrison duty
        if (isHumanAndNotAutoplaying && unit.isMilitary()) {
            TacticalAi.automate(unit.civ, listOf(unit), cache)
            if (unit in cache.processedUnits) return
        }

        // What is left of the unit's turn is CvHomelandAI's: explore, garrison, sentry, patrol, ... (the civilization's whole military goes through
        // it in NextTurnAutomation.automateUnits; this is for a single automated unit)
        HomelandAi.automateUnit(unitView, cache)
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

    /** This is what a unit with the 'explore' action does: CvHomelandAI::ExecuteExplorerMoves for the one unit, then the 'finished exploring' notice. */
    fun automatedExplore(unitView: MapUnitView, cache: AutomationCache = AutomationCache()) {
        val unit = unitView.getUnit()
        if (HomelandAi.exploreUnit(unit, cache)) return
        unit.civ.addNotification("${unit.shortDisplayName()} finished exploring.", MapUnitAction(unit), NotificationCategory.Units, unit.name, "OtherIcons/Sleep")
        unit.action = null
    }


}
