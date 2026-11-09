package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.CitySpecializationDef
import com.unciv.models.ruleset.ai.Flavor
import kotlin.math.max

/**
 *  The strategic AI state of a city (`city.ai`), serialized with it: the original's CvCityStrategyAI - its active city strategies,
 *  its specialization, and its flavor vector (a CvFlavorRecipient of the owner's flavor manager).
 *  A city that changes owner starts over, as the original creates a new city object for the new owner ([reset]).
 */
class CityAi : IsPartOfGameInfoSerialization {
    /** Active city strategies: name -> game turn adopted */
    var cityStrategies = LinkedHashMap<String, Int>()
    /** The city specialization (CITYSPECIALIZATION_* name), set by the city specialization port; null = none */
    var specialization: String? = null
    /** CvCityStrategyAI::m_eDefaultSpecialization: the owner's next desired specialization when the city was founded or acquired */
    var defaultSpecialization: String? = null
    /** Whether [defaultSpecialization] has been set since the city was founded or acquired (null is a valid default) */
    var defaultSpecializationAssigned = false

    @Transient lateinit var city: City
    @Transient private var flavorVector: IntArray? = null
    /** The owner's [CivAi.strategyVersion] [flavorVector] was built from */
    @Transient private var builtFromVersion = -1

    fun clone(): CityAi {
        val toReturn = CityAi()
        toReturn.cityStrategies.putAll(cityStrategies)
        toReturn.specialization = specialization
        toReturn.defaultSpecialization = defaultSpecialization
        toReturn.defaultSpecializationAssigned = defaultSpecializationAssigned
        return toReturn
    }

    fun setTransients(city: City) {
        this.city = city
        flavorVector = null
    }

    /** Forgets all strategies and the specialization - for a city taken over by another civilization */
    fun reset() {
        cityStrategies.clear()
        specialization = null
        defaultSpecialization = null
        defaultSpecializationAssigned = false
        flavorVector = null
    }

    /**
     *  The city's flavors by [Flavor.ordinal] (CvCityStrategyAI's m_piLatestFlavorValues): the owner's personality, plus the city flavors of
     *  the owner's active economic and military strategies, plus the flavors of this city's active city strategies and of its specialization,
     *  each step kept within FLAVOR_MIN_VALUE..FLAVOR_MAX_VALUE. Can be negative; see [flavor]. Shared array, do not modify.
     */
    fun flavors(): IntArray {
        val ownerAi = city.civ.ai
        val cached = flavorVector
        if (cached != null && builtFromVersion == ownerAi.strategyVersion) return cached
        val rules = ownerAi.rules
        val min = rules.define(AiDefine.FLAVOR_MIN_VALUE)
        val max = rules.define(AiDefine.FLAVOR_MAX_VALUE)
        val vector = ownerAi.personalityFlavorVector().copyOf()
        for (def in ownerAi.activeEconomicStrategyDefs()) addFlavorDelta(vector, def.cityFlavorVector, min, max)
        for (def in ownerAi.activeMilitaryStrategyDefs()) addFlavorDelta(vector, def.cityFlavorVector, min, max)
        for (def in rules.cityStrategies) if (def.name in cityStrategies) addFlavorDelta(vector, def.flavorVector, min, max)
        specializationDef()?.let { addFlavorDelta(vector, it.flavorVector, min, max) }
        flavorVector = vector
        builtFromVersion = ownerAi.strategyVersion
        return vector
    }

    /** CvFlavorRecipient::GetLatestFlavorValue: the city's flavor, negative values shown as 0 unless [allowNegative] */
    fun flavor(flavor: Flavor, allowNegative: Boolean = false): Int {
        val value = flavors()[flavor.ordinal]
        return if (allowNegative) value else max(0, value)
    }

    /** CvCityStrategyAI::IsUsingCityStrategy */
    fun isCityStrategyActive(name: String) = name in cityStrategies

    fun specializationDef(): CitySpecializationDef? =
        specialization?.let { name -> city.civ.gameInfo.ruleset.ai.citySpecializations.firstOrNull { it.name == name } }

    /** CvCityStrategyAI::SetSpecialization: the specialization's flavors replace the previous one's */
    fun changeSpecialization(name: String?) {
        if (name == specialization) return
        specialization = name
        flavorVector = null
    }

    /** CvCityStrategyAI::DoTurn: the city strategy loop (see [runStrategyLoop]; city strategies have no FirstTurnExecuted) */
    fun doTurn() {
        val civ = city.civ
        if (runStrategyLoop(civ.gameInfo.ruleset.ai.cityStrategies, cityStrategies, civ, checkFirstTurn = false) { StrategyTests.test(city, it) })
            flavorVector = null
    }
}
