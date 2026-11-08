package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/** CvTemporaryZone (CvTacticalAI.h): a short-lived dominance zone around a target an operation is deploying in front of */
class TemporaryZone : IsPartOfGameInfoSerialization {
    var position = HexCoord()
    var targetType = TacticalTargetType.None
    /** The last game turn the zone lasts through (CvTemporaryZone::m_iLastTurn) */
    var lastTurn = 0
    var navalInvasion = false

    fun clone() = TemporaryZone().also {
        it.position = position
        it.targetType = targetType
        it.lastTurn = lastTurn
        it.navalInvasion = navalInvasion
    }
}

/** CvTacticalPosture (CvTacticalAI.h): the posture chosen for the zone of ([ownerId], [isWater], [cityId]) the last time it was used */
class ZonePosture : IsPartOfGameInfoSerialization {
    /** [Civilization.civName] of the zone's owner, null for no owner */
    var ownerId: String? = null
    var isWater = false
    /** [City.id] of the zone's closest city, null for none */
    var cityId: String? = null
    var posture = TacticalPosture.None

    fun clone() = ZonePosture().also {
        it.ownerId = ownerId
        it.isWater = isWater
        it.cityId = cityId
        it.posture = posture
    }
}

/**
 *  The tactical AI state of a civilization (`civ.ai.tactical`): the part of CvTacticalAI that lives across turns - the postures chosen
 *  for each dominance zone (m_Postures, remembered so that a posture is kept while it is only "less stringent" true) and the temporary zones
 *  operations register (m_TempZones) - and the per-turn [map] (CvTacticalAnalysisMap::RefreshDataForNextPlayer).
 *
 *  Old saves have neither postures nor zones.
 */
class TacticalState : IsPartOfGameInfoSerialization {
    var postures = ArrayList<ZonePosture>()
    var temporaryZones = ArrayList<TemporaryZone>()
    /** CvUnit::GetTacticalAIPlot: the destination a barbarian ship keeps heading for over several turns, by [com.unciv.logic.map.mapunit.MapUnit.id] */
    var unitDestinations = HashMap<Int, HexCoord>()

    @Transient private lateinit var civ: Civilization
    @Transient private var cachedMap: TacticalMap? = null
    @Transient private var cachedMapTurn = -1
    @Transient private val garrisonAssignedTurns = HashMap<String, Int>()

    fun clone(): TacticalState {
        val toReturn = TacticalState()
        for (posture in postures) toReturn.postures.add(posture.clone())
        for (zone in temporaryZones) toReturn.temporaryZones.add(zone.clone())
        toReturn.unitDestinations.putAll(unitDestinations)
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        cachedMap = null
    }

    /**
     *  The analysis map of this civilization for the current turn (CvTacticalAnalysisMap, built once per turn and player): cells, dominance
     *  zones with their strengths, dominance and value. Obsolete temporary zones are dropped first. Code that changed units or ownership
     *  since the map was built (tests) calls [invalidateMap].
     */
    val map: TacticalMap
        get() {
            val turn = civ.gameInfo.turns
            cachedMap?.let { if (cachedMapTurn == turn) return it }
            dropObsoleteZones()
            val built = TacticalMap(civ, this)
            cachedMap = built
            cachedMapTurn = turn
            return built
        }

    fun invalidateMap() {
        cachedMap = null
    }

    /**
     *  CvTacticalAI::AddTemporaryZone: operations register one around the target they deploy in front of ([TacticalTargetType.City] gives a
     *  boost to the city's zone, any other type makes a zone of its own around [tile]); it lasts [turns] more turns
     *  (AI_TACTICAL_MAP_TEMP_ZONE_TURNS, bombardments AI_TACTICAL_MAP_BOMBARDMENT_ZONE_TURNS).
     */
    fun addTemporaryZone(
        tile: Tile,
        targetType: TacticalTargetType,
        navalInvasion: Boolean = false,
        turns: Int = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
    ) {
        val zone = TemporaryZone()
        zone.position = tile.position
        zone.targetType = targetType
        zone.lastTurn = civ.gameInfo.turns + turns
        zone.navalInvasion = navalInvasion
        temporaryZones.add(zone)
        cachedMap = null
    }

    /** CvTacticalAI::DeleteTemporaryZone */
    fun deleteTemporaryZone(tile: Tile) {
        temporaryZones.removeAll { it.position == tile.position }
        cachedMap = null
    }

    /** CvTacticalAI::DropObsoleteZones */
    private fun dropObsoleteZones() {
        val turn = civ.gameInfo.turns
        temporaryZones.removeAll { it.lastTurn < turn }
    }

    /** CvTacticalAI::IsTemporaryZoneCity: an operation just deployed in front of [city] */
    fun isTemporaryZoneCity(city: City): Boolean =
        temporaryZones.any { it.targetType == TacticalTargetType.City && it.position == city.getCenterTile().position }

    /** CvTacticalAI::FindPosture: the posture last chosen for the zone of this owner, water flag and city, [TacticalPosture.None] if none */
    fun findPosture(ownerId: String?, isWater: Boolean, cityId: String?): TacticalPosture =
        postures.firstOrNull { it.ownerId == ownerId && it.isWater == isWater && it.cityId == cityId }?.posture ?: TacticalPosture.None

    /** CvCity::GetLastTurnGarrisonAssigned: the turn a unit was last sent to garrison the city (tactical or homeland), -1 if not this session */
    fun lastTurnGarrisonAssigned(city: City): Int = garrisonAssignedTurns[city.id] ?: -1

    /** CvCity::SetLastTurnGarrisonAssigned. It is only compared with the current turn, so it is not saved. */
    fun setLastTurnGarrisonAssigned(city: City, turn: Int) {
        garrisonAssignedTurns[city.id] = turn
    }
}
