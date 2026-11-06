package com.unciv.models.ruleset

import com.unciv.logic.city.City
import com.unciv.logic.league.canContribute
import com.unciv.logic.city.CityConstructions
import com.unciv.models.Counter
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.components.fonts.Fonts
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

sealed class PerpetualConstruction(override var name: String, val description: String) : IConstruction {
    @Readonly
    abstract fun getProductionTooltip(city: City, withIcon: Boolean = false): String

    abstract class StatConversion(
        val stat: Stat
    ) : PerpetualConstruction(stat.name, "Convert production to [${stat.name}] at a rate of [rate] to 1") {
        override fun getProductionTooltip(city: City, withIcon: Boolean) : String
            = "\r\n${(city.cityStats.currentCityStats.production / getConversionRate(city)).roundToInt()}${if (withIcon) stat.character else ""}/${Fonts.turn}"

        @Readonly
        fun getConversionRate(city: City) : Int = (1 / city.cityStats.getStatConversionRate(stat)).roundToInt()

        override fun isBuildable(cityConstructions: CityConstructions): Boolean {
            val city = cityConstructions.city
            if (stat == Stat.Faith && !city.civ.gameInfo.isReligionEnabled())
                return false

            val context = city.state
            @Suppress("DEPRECATION") // forEachMatchingUnique doesn't allow a `return true` exit
            return city.civ.getMatchingUniques(UniqueType.EnablesStatProduction, context)
                .any { it.params[0] == stat.name }
        }
    }

    @Readonly
    override fun shouldBeDisplayed(cityConstructions: CityConstructions, queue: List<String>) = isBuildable(cityConstructions)
    @Readonly
    override fun getStockpiledResourceRequirements(state: GameContext) = Counter.ZERO
    @Readonly
    override fun getResourceRequirementsPerTurn(state: GameContext?) = Counter.ZERO
    @Readonly
    override fun requiredResources(state: GameContext): Set<String> = emptySet()

    object Science : StatConversion(Stat.Science)
    object Gold : StatConversion(Stat.Gold)
    object Culture : StatConversion(Stat.Culture)
    object Faith : StatConversion(Stat.Faith)
    object Food : StatConversion(Stat.Food)

    /**
     * The city production target of a League project (a Process in the original, CvCity::doProcess, CvCity.cpp:13081-13099): nothing is built,
     * the production of the city goes to the contribution of its civilization to the project, see [com.unciv.logic.league.League.addContribution].
     * The name is that of the resolution that starts the project. Buildable while the project is running and the civilization is a major member.
     */
    sealed class Project(name: String) : PerpetualConstruction(name, "Contribute production to the [$name] project of the World Congress") {
        override fun getProductionTooltip(city: City, withIcon: Boolean) =
            "\r\n${city.cityStats.currentCityStats.production.roundToInt()}${if (withIcon) Stat.Production.character else ""}/${Fonts.turn}"

        override fun isBuildable(cityConstructions: CityConstructions): Boolean {
            val civ = cityConstructions.city.civ
            return civ.gameInfo.league?.canContribute(civ, name) == true
        }
    }
    object WorldsFair : Project("World's Fair")
    object InternationalGames : Project("International Games")
    object InternationalSpaceStation : Project("International Space Station")
    object Idle : PerpetualConstruction("Nothing", "The city will not produce anything.") {
        override fun getProductionTooltip(city: City, withIcon: Boolean) = ""
        override fun isBuildable(cityConstructions: CityConstructions) = true
    }

    private object Mapper {
        /** Map of names to instances: Cannot be part of the companion due to initialization order injustice - a companion is nobility, members are peasants */
        val perpetualConstructionsMap: Map<String, PerpetualConstruction> =
            mapOf(Science.name to Science, Gold.name to Gold, Culture.name to Culture, Faith.name to Faith, Food.name to Food, Idle.name to Idle,
                WorldsFair.name to WorldsFair, InternationalGames.name to InternationalGames, InternationalSpaceStation.name to InternationalSpaceStation)
    }

    companion object {
        val perpetualConstructionsMap get() = Mapper.perpetualConstructionsMap
        /** @return whether [name] represents a PerpetualConstruction - note "" is translated to Nothing in the queue so `isNamePerpetual("")==true` */
        fun isNamePerpetual(name: String) = name.isEmpty() || name in Mapper.perpetualConstructionsMap
    }
}
