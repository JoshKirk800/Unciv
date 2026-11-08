package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.view.MapUnitView

/**
 *  What air units do outside the tactical and operation moves ([AirMoves], [com.unciv.logic.automation.strategy.NavalOperationLogic] for nukes):
 *  an aircraft that will rebase ([AirMoves.willAirUnitRebase]: its base is in danger, or a fighter has nothing to do where it is) goes to the
 *  base with the highest plot danger (CvHomelandAI::ExecuteAircraftMoves); aircraft that stay attack what is in their range
 *  ([BattleHelper.tryAttackNearbyEnemy]: the original's air strikes are tactical attacks, which do not take air units here). Fighters that
 *  intercept or sweep are commanded by the tactical AI before this and skipped. The old relocation heuristics (hopping from city to city towards
 *  enemies, missiles to cities with targets, nukes at the best tile in range) are replaced by the original's rules: a nuke goes off in a
 *  [com.unciv.logic.automation.strategy.AiOperationType.NUKE_ATTACK] operation, or waits in its base.
 */
object AirUnitAutomation {

    /** Rebases the aircraft if it wants to; true if it did (its turn is over) */
    private fun rebase(unitView: MapUnitView): Boolean = AirMoves.plotAircraftMove(unitView.getUnit())

    fun automateFighter(unitView: MapUnitView, @Suppress("UNUSED_PARAMETER") cache: AutomationCache) {
        if (unitView.unitHealth < 75) return // Wait and heal
        if (rebase(unitView)) return
        if (unitView.unitHealth < 80) return // Wait and heal up
        BattleHelper.tryAttackNearbyEnemy(unitView)
    }

    fun automateBomber(unitView: MapUnitView) {
        if (unitView.unitHealth < 75) return // Wait and heal
        if (rebase(unitView)) return
        BattleHelper.tryAttackNearbyEnemy(unitView)
    }

    /** Missiles that self-destruct on the target (the original's UNITAI_MISSILE_AIR) */
    fun automateMissile(unitView: MapUnitView) {
        if (rebase(unitView)) return
        BattleHelper.tryAttackNearbyEnemy(unitView)
    }

    /** Nukes: the nuke attack operation sets them off; until then they rebase when their base is in danger */
    fun automateNukes(unitView: MapUnitView) {
        rebase(unitView)
    }
}
