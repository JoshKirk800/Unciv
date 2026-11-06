package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.city.GreatWorkSlot
import com.unciv.logic.civilization.managers.CultureManager
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.InfluenceTrend
import com.unciv.logic.civilization.managers.PublicOpinionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.MilestoneType
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
        addTourism()
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

    private fun addTourism() {
        val culture = civ.culture
        val others = culture.getOtherMajorCivs()
        add("Tourism".toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padBottom(5f).row()
        add("[${culture.getTourism()}] Tourism per turn, [${civ.totalCultureForContests}] Culture in the whole game".tr().toLabel()).colspan(COLUMNS).row()
        val victory = civ.gameInfo.ruleset.victories.values.firstOrNull { victory -> victory.milestoneObjects.any { it.type == MilestoneType.InfluentialOverAllCivs } }
        if (victory != null && victory.name in civ.gameInfo.gameParameters.victoryTypes && others.isNotEmpty()) {
            val total = if (civ.shouldHideCivCount()) "?" else others.size.toString()
            add("Influential over [${culture.getNumCivsInfluentialOn()}] of [$total] civilizations".tr().toLabel(Color.GOLD)).colspan(COLUMNS).padTop(5f).row()
        }
        val known = others.filter { civ.knows(it) }
        if (known.isNotEmpty()) {
            for (header in listOf("Civilization", "Ideology", "Tourism per turn", "Influence", "Level", "Trend", "Turns to Influential", "Bonuses"))
                add(header.toLabel()).pad(3f)
            row()
            addSeparator()
            for (other in known.sortedBy { it.civName }) {
                val level = culture.getInfluenceLevel(other) ?: InfluenceLevel.Unknown
                add(other.civName.toLabel(hideIcons = true)).left()
                add((other.getIdeology()?.name ?: "").toLabel())
                add(culture.getInfluencePerTurn(other).toString().toLabel())
                add("${culture.getInfluencePercent(other)}%".toLabel())
                add(level.label.toLabel(if (level >= InfluenceLevel.Influential) Color.GREEN else Color.WHITE))
                val trend = when (culture.getInfluenceTrend(other)) {
                    InfluenceTrend.Rising -> "Rising"
                    InfluenceTrend.Falling -> "Falling"
                    InfluenceTrend.Static -> "Static"
                    null -> ""
                }
                add(trend.toLabel())
                val turns = culture.getTurnsToInfluential(other)
                add((if (level >= InfluenceLevel.Influential || turns >= CultureManager.NEVER) "" else turns.toString()).toLabel())
                add(culture.getRelationsWith(other).joinToString { it.label.tr() }.toLabel())
                row()
            }
        }
        addPublicOpinion()
        val cities = civ.cities.filter { culture.getCityTourism(it) > 0 }.sortedByDescending { culture.getCityTourism(it) }
        if (cities.isNotEmpty()) {
            add("Tourism of our cities".toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(15f).row()
            for (city in cities) {
                add(city.name.toLabel()).left().colspan(2)
                add(culture.getCityTourism(city).toString().toLabel()).left().row()
            }
        }
        add("Great Works".toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(20f).row()
    }

    /** What the other ideologies do to us, see [com.unciv.logic.civilization.managers.PublicOpinion] */
    private fun addPublicOpinion() {
        val ideology = civ.getIdeology() ?: return
        val opinion = civ.culture.publicOpinion
        add("Public Opinion".toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(15f).row()
        add("Our ideology is [${ideology.name}]".tr().toLabel()).colspan(COLUMNS).row()
        val color = when (opinion.type) {
            PublicOpinionType.Content -> Color.GREEN
            PublicOpinionType.Dissidents -> Color.WHITE
            PublicOpinionType.CivilResistance -> Color.YELLOW
            PublicOpinionType.RevolutionaryWave -> Color.RED
        }
        add(opinion.type.label.toLabel(color)).colspan(COLUMNS).row()
        for ((name, total) in opinion.pressure.entries.sortedByDescending { it.value }) {
            val civs = opinion.pressureByCiv[name]!!.keys.mapNotNull { id -> civ.gameInfo.civilizations.firstOrNull { it.civID == id }?.civName }
            add("Pressure for [$name]: [$total] ([${civs.joinToString()}])".tr().toLabel()).colspan(COLUMNS).row()
        }
        if (opinion.pressure.isEmpty())
            add("No other ideology presses on us".toLabel()).colspan(COLUMNS).row()
        opinion.preferredIdeology?.let {
            add("The people would prefer [$it]".tr().toLabel(Color.ORANGE)).colspan(COLUMNS).row()
        }
        if (opinion.unhappiness > 0)
            add("The public opinion makes [${opinion.unhappiness}] unhappiness".tr().toLabel(Color.RED)).colspan(COLUMNS).row()
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
