package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.managers.TradeDestination
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter

/**
 * Lets the player choose where a trade unit that stands in one of our cities sends its route:
 * the cities within range, with what the route would bring both sides.
 */
class TradeRoutePickerScreen(private val unit: MapUnit) : PickerScreen() {
    private val civ = unit.civ
    private var chosen: TradeDestination? = null

    init {
        val origin = unit.getTile().getCity()
        rightSideButton.setText("Establish trade route".tr())
        rightSideButton.onClick(UncivSound.Coin) { establish() }

        val destinations = if (origin == null || origin.civ != civ) emptyList() else civ.tradeRoutes.getDestinations(origin, unit.baseUnit)
        val previews = destinations.mapNotNull { destination ->
            civ.tradeRoutes.preview(unit.baseUnit, origin!!, destination)?.let { destination to it }
        }.sortedWith(compareByDescending<Pair<TradeDestination, TradeRoute>> { it.first.kind == TradeRouteKind.International }
            .thenByDescending { it.second.originYields.gold + 3 * it.second.originYields.science }
            .thenByDescending { it.second.originYields.food + it.second.originYields.production })

        if (previews.isEmpty()) descriptionLabel.setText("No city is in reach for a trade route.".tr())

        for ((destination, route) in previews) {
            val city = destination.city
            val icon = if (destination.kind == TradeRouteKind.International) ImageGetter.getNationPortrait(city.civ.nation, 30f)
                else ImageGetter.getStatIcon(destination.kind.name)
            val label = if (destination.kind == TradeRouteKind.International) "${city.name} (${city.civ.civName})".tr()
                else "[${city.name}] ([${destination.kind.name}])".tr()
            val button = PickerPane.getPickerOptionButton(icon, label)
            button.onClick {
                chosen = destination
                pick("Establish trade route".tr())
                descriptionLabel.setText(describe(destination, route))
            }
            button.onDoubleClick(UncivSound.Coin) { chosen = destination; establish() }
            topTable.add(button).pad(5f).row()
        }
    }

    private fun describe(destination: TradeDestination, route: TradeRoute): String {
        val lines = ArrayList<String>()
        lines += "[${destination.city.name}]".tr() + ": " + "[${destination.path.size - 1}] tiles".tr() + ", " +
            (if (route.isSea) "by water" else "over land").tr() + ", " + "lasts [${route.turnRouteComplete - civ.gameInfo.turns}] turns".tr()
        if (!route.originYields.isEmpty()) lines += "Yields for us:".tr() + " " + route.originYields.toString()
        if (!route.destinationYields.isEmpty()) lines += "Yields for [${destination.city.civ.civName}]:".tr() + " " + route.destinationYields.toString()
        if (destination.kind == TradeRouteKind.International) {
            val other = destination.city.civ
            val current = civ.culture.getInfluencePerTurn(other)
            val gain = civ.culture.getTourismIncreaseFromTradeConnection(other)
            lines += "Tourism towards [${other.civName}]: [$current] -> [${current + gain}] per turn".tr()
        }
        return lines.joinToString("\n")
    }

    private fun establish() {
        val destination = chosen ?: return
        val route = civ.tradeRoutes.establish(unit, destination.city, destination.kind)
        if (route == null) {
            descriptionLabel.setText("This trade route is not possible anymore.".tr())
            return
        }
        GUI.getWorldScreen().shouldUpdate = true
        UncivGame.Current.popScreen()
    }
}
