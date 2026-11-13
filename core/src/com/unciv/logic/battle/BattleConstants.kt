package com.unciv.logic.battle

object BattleConstants {
    //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
    const val LANDING_MALUS = -50
    const val BOARDING_MALUS = -50
    const val ATTACKING_ACROSS_RIVER_MALUS = -20
    const val BASE_FLANKING_BONUS = 10f
    /** STRATEGIC_RESOURCE_EXHAUSTED_PENALTY: the most a unit loses for missing strategic resources (CvUnit::GetStrategicResourceCombatPenalty) */
    const val MISSING_RESOURCES_MALUS = -50
    const val EMBARKED_DEFENCE_BONUS = 100
    const val FORTIFICATION_BONUS = 20
    /** WOUNDED_DAMAGE_MULTIPLIER in the G&K GlobalDefines: a wounded unit loses this percentage of the damage it lost in health from its own damage */
    const val WOUNDED_DAMAGE_MULTIPLIER = 33
    const val DAMAGE_TO_CIVILIAN_UNIT = 40

}
