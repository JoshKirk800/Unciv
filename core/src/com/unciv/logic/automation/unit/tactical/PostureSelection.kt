package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  Which posture the units around each dominance zone take, and what the tactical AI remembers of it: CvTacticalAI::UpdatePostures,
 *  SelectPosture, UseThisDominanceZone (FindPosture is [TacticalState.findPosture]). This chooses and keeps the posture; what the units do in
 *  a posture is the posture moves'.
 */
object PostureSelection {

    /** CvTacticalAI::UseThisDominanceZone: temporary zones, the capital's zone, zones where we have units near the enemy's city, where they have units near us, or both have units */
    fun useThisZone(civ: Civilization, state: TacticalState, zone: DominanceZone): Boolean {
        val city = zone.closestCity
        val isOurCapital = city != null && city == civ.getCapital()
        val isTempZone = city != null && state.isTemporaryZoneCity(city)
        val weHaveUnitsNearEnemy = zone.friendlyUnitCount > 0 && zone.territory == TacticalTerritory.Enemy
        val theyHaveUnitsNearUs = zone.dominance != TacticalDominance.NoUnitsVisible && zone.territory == TacticalTerritory.Friendly
        val bothHaveUnits = zone.friendlyUnitCount > 0 && zone.enemyUnitCount > 0
        return isTempZone || isOurCapital || weHaveUnitsNearEnemy || theyHaveUnitsNearUs || bothHaveUnits
    }

    /**
     *  CvTacticalAI::UpdatePostures: the posture of every zone worth using ([useThisZone]), chosen with last turn's as the zone's memory
     *  (a posture is kept under less stringent conditions than it is taken up with); the new postures replace the remembered ones.
     */
    fun updatePostures(civ: Civilization, state: TacticalState, map: TacticalMap) {
        val newPostures = ArrayList<ZonePosture>()
        for (zone in map.zones) {
            if (!useThisZone(civ, state, zone)) continue
            val ownerId = zone.owner?.civName
            val cityId = zone.closestCity?.id
            val last = state.findPosture(ownerId, zone.isWater, cityId)
            val entry = ZonePosture()
            entry.ownerId = ownerId
            entry.isWater = zone.isWater
            entry.cityId = cityId
            entry.posture = selectPosture(civ, state, zone, last)
            newPostures.add(entry)
        }
        state.postures = newPostures
    }

    /** CvTacticalAI::SelectPosture */
    fun selectPosture(civ: Civilization, state: TacticalState, zone: DominanceZone, last: TacticalPosture): TacticalPosture {
        val dominancePercentage = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_DOMINANCE_PERCENTAGE)
        val rangedDominance = when {
            zone.enemyRangedStrength <= 0 -> TacticalDominance.Friendly
            else -> ratioDominance(zone.friendlyRangedStrength.toLong() * 100 / zone.enemyRangedStrength, dominancePercentage)
        }
        val unitCountDominance = when {
            zone.enemyUnitCount <= 0 -> TacticalDominance.Friendly
            else -> ratioDominance(zone.friendlyUnitCount.toLong() * 100 / zone.enemyUnitCount, dominancePercentage)
        }

        return when (zone.territory) {
            TacticalTerritory.Enemy -> {
                val isTemporaryZone = zone.closestCity?.let { state.isTemporaryZoneCity(it) } ?: false
                when {
                    // Withdraw if the enemy is dominant overall or we have nothing but ranged left
                    !isTemporaryZone && (zone.dominance == TacticalDominance.Enemy || zone.friendlyRangedUnitCount == zone.friendlyUnitCount) ->
                        TacticalPosture.Withdraw
                    // A temporary zone wants us to close in on the city
                    isTemporaryZone ->
                        if (zone.dominance != TacticalDominance.Enemy) TacticalPosture.Steamroll else TacticalPosture.SurgicalCityStrike
                    // Destroy units then assault: dominance in total strength but not the enemy's in ranged units, or double the total strength
                    zone.enemyUnitCount > 0 && zone.dominance == TacticalDominance.Friendly
                        && (rangedDominance != TacticalDominance.Enemy || zone.friendlyStrength.toLong() > zone.enemyStrength.toLong() * 2) ->
                        TacticalPosture.Steamroll
                    last == TacticalPosture.Steamroll && zone.dominance == TacticalDominance.Friendly && zone.enemyUnitCount > 0 ->
                        TacticalPosture.Steamroll
                    // Sit and bombard: dominance in ranged strength and total unit count
                    rangedDominance == TacticalDominance.Friendly && unitCountDominance != TacticalDominance.Enemy ->
                        TacticalPosture.SitAndBombard
                    last == TacticalPosture.SitAndBombard && rangedDominance != TacticalDominance.Enemy && unitCountDominance != TacticalDominance.Enemy ->
                        TacticalPosture.SitAndBombard
                    // Go right after the city: tactical dominance
                    zone.dominance == TacticalDominance.Friendly -> TacticalPosture.SurgicalCityStrike
                    // Exploit flanks: dominance in unit count
                    unitCountDominance == TacticalDominance.Friendly && zone.enemyUnitCount > 1 -> TacticalPosture.ExploitFlanks
                    last == TacticalPosture.ExploitFlanks && unitCountDominance != TacticalDominance.Enemy && zone.enemyUnitCount > 1 ->
                        TacticalPosture.ExploitFlanks
                    else -> TacticalPosture.SurgicalCityStrike
                }
            }
            TacticalTerritory.Neutral, TacticalTerritory.NoOwner -> when {
                rangedDominance == TacticalDominance.Friendly && unitCountDominance != TacticalDominance.Enemy -> TacticalPosture.AttritFromRange
                last == TacticalPosture.AttritFromRange && rangedDominance != TacticalDominance.Enemy -> TacticalPosture.AttritFromRange
                else -> TacticalPosture.ExploitFlanks
            }
            TacticalTerritory.Friendly -> when {
                rangedDominance == TacticalDominance.Friendly && zone.friendlyRangedUnitCount > 1 -> TacticalPosture.AttritFromRange
                last == TacticalPosture.AttritFromRange && zone.friendlyRangedUnitCount > 1 && rangedDominance != TacticalDominance.Enemy ->
                    TacticalPosture.AttritFromRange
                unitCountDominance == TacticalDominance.Friendly && zone.enemyUnitCount > 0 -> TacticalPosture.ExploitFlanks
                last == TacticalPosture.ExploitFlanks && unitCountDominance != TacticalDominance.Enemy && zone.enemyUnitCount > 0 ->
                    TacticalPosture.ExploitFlanks
                // Counterattack: stronger, or even with the enemy having a ranged advantage
                zone.dominance == TacticalDominance.Friendly || (zone.dominance == TacticalDominance.Even && rangedDominance == TacticalDominance.Enemy) ->
                    TacticalPosture.Counterattack
                last == TacticalPosture.Counterattack && zone.dominance != TacticalDominance.Enemy -> TacticalPosture.Counterattack
                else -> TacticalPosture.Hedgehog
            }
            // A temporary zone on water is bombarded from the sea; on land it should be a barbarian camp
            TacticalTerritory.TempZone -> if (zone.isWater) TacticalPosture.ShoreBombardment else TacticalPosture.ExploitFlanks
            TacticalTerritory.None -> TacticalPosture.None
        }
    }

    /** The ratio of friendly to enemy [percent] against 100 +- the dominance percentage */
    private fun ratioDominance(percent: Long, dominancePercentage: Int): TacticalDominance = when {
        percent > 100 + dominancePercentage -> TacticalDominance.Friendly
        percent < 100 - dominancePercentage -> TacticalDominance.Enemy
        else -> TacticalDominance.Even
    }
}
