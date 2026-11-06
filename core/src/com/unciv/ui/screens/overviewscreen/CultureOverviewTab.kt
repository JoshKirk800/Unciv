package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.view.CivView

/**
 * The Great Works of the civilization, building by building. Click a work and then another slot of the same kind
 * to move it there, or swap it with the work that is in it.
 */
class CultureOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    private val civ = viewingPlayer.getCiv()
    private val canMove = civ == gameInfo.getCurrentPlayerCivilization()
    private var selected: GreatWorkSlot? = null

    init {
        defaults().pad(5f)
        build()
    }

    private fun build() {
        clear()
        val works = GreatWorkFunctions.getNumWorks(civ)
        val totalSlots = civ.cities.sumOf { it.greatWorks.getSlots().size }
        add("[$works] Great Works in [$totalSlots] slots".tr().toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padBottom(10f).row()

        val chosen = selected?.getWork()
        if (chosen != null)
            add(describeWork(chosen).toLabel(Color.GOLD)).colspan(COLUMNS).padBottom(10f).row()
        else if (canMove && works > 0)
            add("Select a Great Work, then another slot, to move it".toLabel()).colspan(COLUMNS).padBottom(10f).row()

        for (city in civ.cities.sortedBy { it.name }) {
            val buildings = city.cityConstructions.getBuiltBuildings().filter { it.greatWorkSlotTypes.isNotEmpty() }.toList()
            if (buildings.isEmpty()) continue
            add(city.name.toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(15f).row()
            addSeparator()
            for (building in buildings) addBuilding(building, city.greatWorks.getSlots(building))
        }
    }

    private fun addBuilding(building: Building, slots: List<GreatWorkSlot>) {
        add(building.name.toLabel()).left()
        val city = slots.first().city
        val themingEntry = city.greatWorks.getThemingBonusEntry(building)
        val themingText = when {
            building.themingBonuses.isEmpty() -> ""
            themingEntry == null -> "No theming bonus"
            else -> "{${fillThemingName(themingEntry.name, slots.mapNotNull { it.getWork() })}} +${city.greatWorks.getThemingBonus(building)}"
        }
        add(themingText.tr().toLabel(if (themingEntry != null) Color.GREEN else Color.GRAY)).left()

        for (slot in slots) {
            val work = slot.getWork()
            val text = work?.work?.tr() ?: "Empty [${slot.getType().label}] slot".tr()
            val button = (if (slot == selected) "> $text <" else text).toTextButton()
            button.isEnabled = canMove
            button.onClick { slotClicked(slot) }
            add(button)
        }
        row()
    }

    /** "Museum of [era] [civ] Art" for the works actually in the building. [era] and [civ] stand for the first work's, which is also the others' where it matters */
    private fun fillThemingName(name: String, works: List<GreatWorkInstance>): String {
        val first = works.firstOrNull() ?: return name
        val era = first.era.removeSuffix(" era")
        return name.replace("[era]", "[$era]").replace("[civ]", "[${first.creatorCiv}]")
    }

    private fun describeWork(work: GreatWorkInstance): String =
        "${work.work.tr()}: ${"Attributed to [${work.creatorCiv}]".tr()}, ${"from the [${work.era.removeSuffix(" era")}] era".tr()}"

    private fun slotClicked(slot: GreatWorkSlot) {
        val from = selected
        when {
            from == null -> if (slot.getWork() != null) selected = slot
            from == slot -> selected = null
            GreatWorkFunctions.move(from, slot) -> selected = null
            else -> selected = if (slot.getWork() != null) slot else null
        }
        build()
    }

    private companion object {
        const val COLUMNS = 8
    }
}
