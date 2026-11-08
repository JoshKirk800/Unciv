package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.models.Counter
import com.unciv.models.ruleset.PolicyBranch
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.ReturnsNewInstance

/** How unhappy the people are about the ideology of their civilization (CvCultureClasses.h PublicOpinionTypes), worse with the pressure of the other ideologies */
enum class PublicOpinionType(val label: String, val unhappinessPerCity: Int, val populationPerUnhappiness: Int) {
    Content("Content", 0, 1),
    Dissidents("Dissidents", 1, 10),
    CivilResistance("Civil Resistance", 2, 5),
    RevolutionaryWave("Revolutionary Wave", 4, 3);

    companion object {
        /** The type for a [dissatisfaction] above 0, by the limits in the constants (3 and 5 in the original) */
        @Readonly
        fun forDissatisfaction(dissatisfaction: Int, civilResistanceFrom: Int, revolutionaryWaveFrom: Int) = when {
            dissatisfaction <= 0 -> Content
            dissatisfaction < civilResistanceFrom -> Dissidents
            dissatisfaction < revolutionaryWaveFrom -> CivilResistance
            else -> RevolutionaryWave
        }
    }
}

/**
 * What a civilization with an ideology has to bear because other civilizations that have another ideology influence its people more than it
 * influences theirs (CvPlayerCulture::DoPublicOpinion). It is worked out every turn after the influence, and when the ideology is changed.
 *
 * Every civilization with an ideology, that has met us, presses for it with how much more its influence level on us is than ours on it.
 * The people are content if what presses for their own ideology is at least as much as what presses for all the others together,
 * if not they are dissatisfied by the difference, and would prefer the ideology that presses most.
 */
class PublicOpinion : IsPartOfGameInfoSerialization {
    var type = PublicOpinionType.Content
        private set

    /** Unhappiness that is part of the happiness of the whole civilization, before the handicap of AIs */
    var unhappiness = 0
        private set

    /** The ideology that the people want, if they are not content: what we change to when we choose to. Null while content */
    var preferredIdeology: String? = null
        private set

    /** The civilization that presses most for the [preferredIdeology], by its civ id */
    var biggestInfluence: String? = null
        private set

    /** The pressure on each ideology by name, that is the ones that press for a different ideology than our own too */
    var pressure = Counter<String>()
        private set

    /** For each ideology: the civilizations that press for it, and by how much */
    var pressureByCiv = LinkedHashMap<String, Counter<String>>()
        private set

    /** The dissatisfaction, 0 if content */
    var dissatisfaction = 0
        private set

    var turnIdeologySwitch = -1

    @Transient
    lateinit var civ: Civilization
        private set

    @ReturnsNewInstance
    fun clone(): PublicOpinion {
        val toReturn = PublicOpinion()
        toReturn.type = type
        toReturn.unhappiness = unhappiness
        toReturn.preferredIdeology = preferredIdeology
        toReturn.biggestInfluence = biggestInfluence
        toReturn.pressure = pressure.clone()
        toReturn.pressureByCiv = LinkedHashMap(pressureByCiv.mapValues { it.value.clone() })
        toReturn.dissatisfaction = dissatisfaction
        toReturn.turnIdeologySwitch = turnIdeologySwitch
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
    }

    private fun reset() {
        type = PublicOpinionType.Content
        unhappiness = 0
        dissatisfaction = 0
        preferredIdeology = null
        biggestInfluence = null
        pressure = Counter()
        pressureByCiv = LinkedHashMap()
    }

    /** The pressure on each ideology: how much more the influence level of the civilizations that follow it is over us than ours over them, added up, and the same by civilization */
    fun getPressureOnUs(): Pair<Counter<String>, Map<String, Counter<String>>> {
        val total = Counter<String>()
        val byCiv = LinkedHashMap<String, Counter<String>>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || other.isDefeated()) continue
            val ideology = other.getIdeology() ?: continue
            val theirs = other.culture.getInfluenceLevel(civ)?.ordinal ?: -1
            val ours = civ.culture.getInfluenceLevel(other)?.ordinal ?: -1
            val dominance = theirs - ours
            if (dominance <= 0) continue
            total.add(ideology.name, dominance)
            byCiv.getOrPut(ideology.name) { Counter() }.add(other.civID, dominance)
        }
        // World Ideology (LR 4.3.11): the World Congress presses for its ideology on everybody
        val league = civ.gameInfo.league
        if (league != null) for (branch in civ.gameInfo.ruleset.policyBranches.values) {
            if (!branch.isIdeology) continue
            val worldPressure = league.getIdeologyPressure(branch.name)
            if (worldPressure <= 0) continue
            total.add(branch.name, worldPressure)
            byCiv.getOrPut(branch.name) { Counter() }.add(WORLD_CONGRESS_PRESSURE_KEY, worldPressure)
        }
        return total to byCiv
    }

    /**
     * Which of the other ideologies the people want when they are not content: the one that presses most, and on a tie the one that comes before
     * their own in the order of the ruleset, where the last is followed by the first (for Brave New World's Freedom, Autocracy, Order that is
     * the original's: with Freedom Order wins a tie, with Autocracy Freedom, with Order Autocracy).
     */
    private fun choosePreferred(own: PolicyBranch, pressure: Counter<String>): PolicyBranch {
        val ideologies = civ.gameInfo.ruleset.policyBranches.values.filter { it.isIdeology }
        val ownIndex = ideologies.indexOf(own)
        val others = ideologies.filter { it != own }
        val best = others.maxOf { pressure[it.name] }
        return others.filter { pressure[it.name] == best }
            .minBy { (ownIndex - ideologies.indexOf(it) + ideologies.size) % ideologies.size }
    }

    /** CvPlayerCulture::DoPublicOpinion */
    fun update() {
        reset()
        val own = civ.getIdeology() ?: return
        val constants = civ.gameInfo.ruleset.modOptions.constants

        val (total, byCiv) = getPressureOnUs()
        pressure = total
        pressureByCiv = LinkedHashMap(byCiv)

        val againstOwn = civ.gameInfo.ruleset.policyBranches.values.filter { it.isIdeology && it != own }.sumOf { total[it.name] }
        dissatisfaction = againstOwn - total[own.name]
        if (dissatisfaction <= 0) {
            dissatisfaction = 0
            return
        }

        type = PublicOpinionType.forDissatisfaction(dissatisfaction, constants.publicOpinionCivilResistance, constants.publicOpinionRevolutionaryWave)
        val preferred = choosePreferred(own, total)
        preferredIdeology = preferred.name
        biggestInfluence = byCiv[preferred.name]?.entries?.maxByOrNull { it.value }?.key // the first of equals, the order of the civilizations

        unhappiness = unhappinessOf(type)
    }

    /** CvPlayerCulture::ComputePublicOpinionUnhappiness: per city, or per so many citizens, whichever is more */
    private fun unhappinessOf(type: PublicOpinionType): Int {
        val cities = civ.cities.size
        val population = civ.cities.sumOf { it.population.population }
        return maxOf(cities * type.unhappinessPerCity, population / type.populationPerUnhappiness)
    }

    /**
     * CvPlayerCulture::ComputeHypotheticalPublicOpinionUnhappiness: the unhappiness the public opinion would bring if [ideology] were ours,
     * by the pressure on us now (the ideology AI weighs it before choosing one).
     */
    fun hypotheticalUnhappiness(ideology: PolicyBranch): Int {
        val (total, _) = getPressureOnUs()
        val againstIt = civ.gameInfo.ruleset.policyBranches.values.filter { it.isIdeology && it != ideology }.sumOf { total[it.name] }
        val hypotheticalDissatisfaction = againstIt - total[ideology.name]
        if (hypotheticalDissatisfaction <= 0) return 0
        val constants = civ.gameInfo.ruleset.modOptions.constants
        return unhappinessOf(PublicOpinionType.forDissatisfaction(hypotheticalDissatisfaction,
            constants.publicOpinionCivilResistance, constants.publicOpinionRevolutionaryWave))
    }

    @Readonly
    fun isContent() = type == PublicOpinionType.Content

    companion object {
        /** The key of the World Congress in [pressureByCiv], where the civ id of an influencing civilization usually is */
        const val WORLD_CONGRESS_PRESSURE_KEY = "World Congress"
    }
}
