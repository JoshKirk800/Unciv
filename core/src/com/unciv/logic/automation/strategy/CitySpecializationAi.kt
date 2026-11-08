package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization

/**
 *  The persisted state of a civilization's city specialization AI (CvCitySpecializationAI's serialized members), `civ.ai.citySpecialization`.
 *  Specializations are names of [com.unciv.models.ruleset.ai.CitySpecializationDef]s (CITYSPECIALIZATION_*), null = NO_CITY_SPECIALIZATION.
 */
class CitySpecializationState : IsPartOfGameInfoSerialization {
    /** m_bSpecializationsDirty */
    var specializationsDirty = false
    /** m_bInterruptWonders: a new war or a lost city - every city chooses its production again at the next evaluation */
    var interruptWonders = false
    /** m_eNextSpecializationDesired: the specialization the next city founded gets as its default */
    var nextSpecializationDesired: String? = null
    /** m_eNextWonderDesired: building name, null = none */
    var nextWonderDesired: String? = null
    /** m_iWonderCityID: City.id of the wonder city, null = none */
    var wonderCityId: String? = null
    /** m_iNextWonderWeight: total weight of the wonders ChooseWonder considered */
    var nextWonderWeight = 0
    /** m_iLastTurnEvaluated */
    var lastTurnEvaluated = 0
    /** The civilizations this one was at war with at the last evaluation (to see a new war, which sets [interruptWonders]) */
    var warsAtLastEvaluation = HashSet<String>()
    /** The ids of the cities this one owned at the last evaluation (to see a lost city, which sets [interruptWonders]) */
    var citiesAtLastEvaluation = HashSet<String>()

    fun clone(): CitySpecializationState {
        val toReturn = CitySpecializationState()
        toReturn.specializationsDirty = specializationsDirty
        toReturn.interruptWonders = interruptWonders
        toReturn.nextSpecializationDesired = nextSpecializationDesired
        toReturn.nextWonderDesired = nextWonderDesired
        toReturn.wonderCityId = wonderCityId
        toReturn.nextWonderWeight = nextWonderWeight
        toReturn.lastTurnEvaluated = lastTurnEvaluated
        toReturn.warsAtLastEvaluation.addAll(warsAtLastEvaluation)
        toReturn.citiesAtLastEvaluation.addAll(citiesAtLastEvaluation)
        return toReturn
    }
}
