package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.unit.GreatPeopleDirective
import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.automation.unit.GreatPersonRole
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.PlayerStrategyDef

/**
 *  The economic strategies that launch the Great People operations (CvEconomicAI.cpp):
 *  EconomicAIHelpers::IsTestStrategy_TradeWithCityState and IsTestStrategy_ConcertTour. Never for humans; weight 10 per loose Great Person
 *  (not on a mission yet) whose last directive is USE_POWER (merchants) / TOURISM_BLAST (musicians), active at WeightThreshold +
 *  the personality modifier. The operations they launch are started by [GreatPersonAutomation] in the unit's own move; the strategies
 *  have no flavors.
 */
object GreatPeopleStrategyTests {
    internal fun registerAll(tests: StrategyTests) {
        tests.register("ECONOMICAISTRATEGY_TRADE_WITH_CITY_STATE") { civ, def ->
            looseGreatPeopleTest(civ, def, GreatPersonRole.Merchant, GreatPeopleDirective.UsePower)
        }
        tests.register("ECONOMICAISTRATEGY_CONCERT_TOUR") { civ, def ->
            looseGreatPeopleTest(civ, def, GreatPersonRole.Musician, GreatPeopleDirective.TourismBlast)
        }
    }

    private fun looseGreatPeopleTest(civ: Civilization, def: PlayerStrategyDef, role: GreatPersonRole, directive: GreatPeopleDirective): Boolean {
        if (civ.isHuman()) return false
        val ruleset = civ.gameInfo.ruleset
        val loose = civ.units.getCivGreatPeople().count {
            it.greatPeopleDirective == directive && it.greatPersonTarget == null && GreatPersonAutomation.roleOf(it.baseUnit, ruleset) == role
        }
        return loose * 10 >= def.weightThreshold + civ.ai.weightThresholdModifier(def)
    }
}
