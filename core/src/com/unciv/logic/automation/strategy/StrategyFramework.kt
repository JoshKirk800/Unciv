package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.CityStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MilitaryStrategyDef
import com.unciv.models.ruleset.ai.PlayerStrategyDef
import com.unciv.models.ruleset.ai.StrategyDef

/**
 *  The trigger tests of the economic, military and city strategies, by strategy name (the XML `Type`).
 *  The original hard-codes one `IsTestStrategy_*` helper per strategy in CvEconomicAI::DoTurn, CvMilitaryAI::UpdateMilitaryStrategies and
 *  CvCityStrategyAI::DoTurn; here each port registers its tests. A strategy without a registered test is never active
 *  (the original turns unknown economic/military strategies on, treating them as scenario strategies; Unciv has no Lua hook to turn them off).
 *
 *  A slice registers its tests by adding one line to the `init` block below, calling its own `register...(this)` function.
 */
object StrategyTests {
    private val playerTests = HashMap<String, (Civilization, PlayerStrategyDef) -> Boolean>()
    private val cityTests = HashMap<String, (City, CityStrategyDef) -> Boolean>()

    init {
        GrandStrategyAi.registerStrategyTests(this)
        GreatPeopleStrategyTests.registerAll(this)
    }

    /** Registers the test of an economic or military strategy: returns whether [Civilization] should be using it */
    fun register(strategyName: String, test: (Civilization, PlayerStrategyDef) -> Boolean) {
        playerTests[strategyName] = test
    }

    /** Registers the test of a city strategy: returns whether [City] should be using it */
    fun registerCity(strategyName: String, test: (City, CityStrategyDef) -> Boolean) {
        cityTests[strategyName] = test
    }

    fun isRegistered(strategyName: String) = strategyName in playerTests || strategyName in cityTests

    internal fun test(civ: Civilization, def: PlayerStrategyDef): Boolean = playerTests[def.name]?.invoke(civ, def) ?: false
    internal fun test(city: City, def: CityStrategyDef): Boolean = cityTests[def.name]?.invoke(city, def) ?: false
}

/**
 *  The common strategy loop of CvEconomicAI::DoTurn, CvMilitaryAI::UpdateMilitaryStrategies and CvCityStrategyAI::DoTurn.
 *
 *  For each definition, in XML order:
 *  - skipped for minor civs if NoMinorCivs; military strategies with OnlyMinorCivs are skipped for majors;
 *  - not active: tested for adoption unless the TechPrereq is missing, the TechObsolete is known, or (economic and military only) the game turn
 *    is before FirstTurnExecuted;
 *  - active: tested for being dropped only on turns where (turn - turn adopted) is a multiple of CheckTriggerTurnCount (0: never),
 *    and only once MinimumNumTurnsExecuted turns have passed; Permanent city strategies are never dropped;
 *  - when tested, a known TechObsolete makes it inactive regardless of its test.
 *  Adopting stores the game turn; the flavor deltas of active strategies are applied by [CivAi.flavors] and [CityAi.flavors].
 *
 *  @param active the strategies in use, by name, with the game turn they were adopted (changed in place)
 *  @return whether [active] changed
 */
internal fun <D : StrategyDef> runStrategyLoop(
    definitions: List<D>,
    active: LinkedHashMap<String, Int>,
    civ: Civilization,
    checkFirstTurn: Boolean,
    test: (D) -> Boolean,
): Boolean {
    val turn = civ.gameInfo.turns
    var changed = false
    for (def in definitions) {
        if (civ.isCityState && def.noMinorCivs) continue
        if (def is MilitaryStrategyDef && def.onlyMinorCivs && !civ.isCityState) continue

        val adoptedTurn = active[def.name]
        val isActive = adoptedTurn != null
        val techObsolete = def.techObsolete
        val isObsolete = techObsolete != null && civ.tech.isResearched(techObsolete)

        var testStart = !isActive
        if (testStart) {
            val techPrereq = def.techPrereq
            if (techPrereq != null && !civ.tech.isResearched(techPrereq)) testStart = false
            if (isObsolete) testStart = false
            if (checkFirstTurn && turn < def.firstTurnExecuted) testStart = false
        }

        var testEnd = false
        if (adoptedTurn != null && !(def is CityStrategyDef && def.permanent)) {
            if (def.checkTriggerTurnCount > 0 && (turn - adoptedTurn) % def.checkTriggerTurnCount == 0) testEnd = true
            if (testEnd && def.minimumNumTurnsExecuted > 0 && turn < adoptedTurn + def.minimumNumTurnsExecuted) testEnd = false
        }

        if (!testStart && !testEnd) continue
        val shouldBeActive = !isObsolete && test(def)
        if (testStart && shouldBeActive) {
            active[def.name] = turn
            changed = true
        } else if (testEnd && !shouldBeActive) {
            active.remove(def.name)
            changed = true
        }
    }
    return changed
}

/** Adds [delta] to [target] by flavor and keeps the result within FLAVOR_MIN_VALUE..FLAVOR_MAX_VALUE (CvFlavorRecipient::ChangeFlavors) */
internal fun addFlavorDelta(target: IntArray, delta: IntArray, min: Int, max: Int) {
    for (i in 0 until Flavor.count) {
        if (delta[i] == 0) continue
        target[i] = (target[i] + delta[i]).coerceIn(min, max)
    }
}
