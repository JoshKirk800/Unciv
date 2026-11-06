package com.unciv.ui.screens.leaguescreens

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.ActiveResolution
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.pickerscreens.PickerPane
import com.unciv.ui.screens.pickerscreens.PickerScreen

/**
 * Where a human member of the World Congress uses the proposal it is entitled to: pick a resolution to enact
 * (with what it is about if it needs a choice) or one in effect to repeal, then [confirm] puts it on the table (CvLeague::DoProposeEnact/DoProposeRepeal).
 * Resolutions that cannot be proposed are listed too, with the reason.
 */
class LeagueProposalPickerScreen(private val civ: Civilization) : PickerScreen() {
    private val gameInfo = civ.gameInfo
    private val league = checkNotNull(gameInfo.league) { "There is no World Congress" }

    /** What was picked, shown on the right button */
    private sealed class Pick {
        class Enact(val resolution: Resolution, val choice: String?) : Pick()
        class Repeal(val active: ActiveResolution) : Pick()
    }
    private var picked: Pick? = null

    init {
        setDefaultCloseAction()
        rightSideButton.setText("Propose".tr())
        descriptionLabel.setText("Choose what you propose to the World Congress.".tr())

        addEnactSection()
        addRepealSection()

        rightSideButton.onClick(UncivSound.Chimes, ::confirm)
    }

    private fun addEnactSection() {
        topTable.add("Enact a resolution".toLabel(fontSize = Constants.headingFontSize)).pad(10f).row()
        val candidates = gameInfo.ruleset.resolutions.values.filter { it.playerProposable && it.isAvailableInGame(gameInfo) }
        for (resolution in candidates) {
            val reason = ResolutionDescriptions.getRejectionReason(league, resolution, civ)
            val button = PickerPane.getPickerOptionButton(getIcon(), resolution.name)
            if (reason != null) button.disable()
            else button.onClick { onResolutionClicked(resolution) }
            topTable.add(button).fillX().pad(10f).padBottom(if (reason == null) 10f else 0f).row()
            if (reason != null) topTable.add(reason.toLabel(Color.ORANGE)).padBottom(10f).row()
        }
    }

    private fun addRepealSection() {
        if (league.activeResolutions.isEmpty()) return
        topTable.add("Repeal a resolution in effect".toLabel(fontSize = Constants.headingFontSize)).pad(10f).padTop(20f).row()
        for (active in league.activeResolutions) {
            val title = ResolutionDescriptions.getTitle(active.resolution, active.choice, gameInfo)
            val reason = ResolutionDescriptions.getRepealRejectionReason(league, active.id, civ)
            val button = PickerPane.getPickerOptionButton(getIcon(), "Repeal [$title]")
            if (reason != null) button.disable()
            else button.onClick { select(Pick.Repeal(active)) }
            topTable.add(button).fillX().pad(10f).padBottom(if (reason == null) 10f else 0f).row()
            if (reason != null) topTable.add(reason.toLabel(Color.ORANGE)).padBottom(10f).row()
        }
    }

    private fun getIcon() = ImageGetter.getImage("OtherIcons/Politics").apply {
        setSize(PickerPane.pickerOptionIconSize, PickerPane.pickerOptionIconSize)
    }

    private fun onResolutionClicked(resolution: Resolution) {
        if (resolution.proposerDecision == ResolutionDecision.None) select(Pick.Enact(resolution, null))
        else openChoicePopup(resolution)
    }

    /** The proposer has to choose a civilization, luxury, religion or ideology: list those that can be proposed */
    private fun openChoicePopup(resolution: Resolution) {
        val popup = Popup(this)
        val caption = when (resolution.proposerDecision) {
            ResolutionDecision.OtherMajorCiv, ResolutionDecision.MajorCiv -> "Choose a civilization"
            ResolutionDecision.Luxury -> "Choose a luxury resource"
            ResolutionDecision.Religion -> "Choose a religion"
            ResolutionDecision.Ideology -> "Choose an ideology"
            else -> resolution.name
        }
        popup.addGoodSizedLabel(caption, Constants.headingFontSize).row()
        for (choice in league.getChoices(resolution, civ).filter { league.canProposeEnact(resolution, it, civ) }) {
            val button = ResolutionDescriptions.getChoiceName(choice, gameInfo).toTextButton()
            button.onActivation {
                popup.close()
                select(Pick.Enact(resolution, choice))
            }
            popup.add(button).pad(5f).row()
        }
        popup.addCloseButton()
        popup.open()
    }

    /** Picks [resolution] to enact with what the proposer chose ([choice], `null` for a resolution without choice), as clicking it does */
    fun selectEnact(resolution: Resolution, choice: String?) = select(Pick.Enact(resolution, choice))

    /** Picks the resolution in effect [active] to repeal */
    fun selectRepeal(active: ActiveResolution) = select(Pick.Repeal(active))

    private fun select(what: Pick) {
        picked = what
        val lines = when (what) {
            is Pick.Enact -> listOf(ResolutionDescriptions.getTitle(what.resolution.name, what.choice, gameInfo)) +
                ResolutionDescriptions.getDescription(what.resolution, gameInfo.ruleset).map { it.tr() }
            is Pick.Repeal -> listOf("Repeal [${ResolutionDescriptions.getTitle(what.active.resolution, what.active.choice, gameInfo)}]".tr())
        }
        descriptionLabel.setText(lines.joinToString("\n"))
        pick("Propose".tr())
    }

    /** Puts the picked resolution on the table, nothing happens if nothing was picked. Does not leave the screen. */
    fun propose() {
        when (val what = picked ?: return) {
            is Pick.Enact -> league.proposeEnact(what.resolution, what.choice, civ)
            is Pick.Repeal -> league.proposeRepeal(what.active.id, civ)
        }
        picked = null
        GUI.clearUndoCheckpoints()
    }

    /** Proposes what was picked and leaves, nothing happens if nothing was picked */
    fun confirm() {
        if (picked == null) return
        propose()
        game.popScreen()
    }
}
