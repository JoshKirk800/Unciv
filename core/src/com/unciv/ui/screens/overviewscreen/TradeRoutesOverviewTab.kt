package com.unciv.ui.screens.overviewscreen

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.TradeRoute
import com.unciv.models.TradeRouteKind
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.view.CivView

/** The trade routes of the civilization, those it started and those that end in one of its cities, and how many it may have */
class TradeRoutesOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    init {
        defaults().pad(5f)
        val civ = viewingPlayer.getCiv()
        val manager = civ.tradeRoutes
        add("[${manager.routes.size}] of [${manager.getNumSlots()}] trade routes in use, [${manager.getSlotsUsed() - manager.routes.size}] more trade units"
            .tr().toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padBottom(10f).row()

        val own = manager.routes.sortedBy { it.turnRouteComplete }
        val incoming = gameInfo.civilizations.filter { it != civ }
            .flatMap { it.tradeRoutes.routes }
            .filter { it.getDestinationCiv() == civ }
            .sortedBy { it.turnRouteComplete }

        if (own.isNotEmpty()) addRoutes("Our trade routes", own, true)
        if (incoming.isNotEmpty()) addRoutes("Trade routes ending in our cities", incoming, false)
    }

    private fun addRoutes(title: String, routes: List<TradeRoute>, isOwn: Boolean) {
        add(title.toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(15f).row()
        for (header in listOf("From", "To", "Kind", "We get", "Turns left", ""))
            add(header.toLabel())
        row()
        addSeparator()

        for (route in routes) {
            add((route.getOriginCity()?.name ?: "").toLabel())
            add("${route.getDestinationCity()?.name ?: ""} (${route.getDestinationCiv()?.civName ?: ""})".toLabel())
            add(describeKind(route).toLabel())
            val yields = if (isOwn) route.originYields else route.destinationYields
            add(yields.toString().toLabel())
            add(route.getTurnsLeft().toString().toLabel())
            val show = "Show".toTextButton()
            show.onClick {
                val worldScreen = UncivGame.Current.resetToWorldScreen()
                worldScreen.mapHolder.setCenterPosition(route.getCurrentTile().position)
            }
            add(show).row()
        }
    }

    private fun describeKind(route: TradeRoute): String {
        val kind = when (route.kind) {
            TradeRouteKind.International -> "International"
            TradeRouteKind.Food -> "Food"
            TradeRouteKind.Production -> "Production"
        }
        return kind.tr() + ", " + (if (route.isSea) "by water" else "over land").tr()
    }

    private companion object {
        const val COLUMNS = 6
    }
}
