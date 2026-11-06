package com.unciv.ui.screens.leaguescreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueProposal
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen

/**
 * Where a human member of the World Congress spends its delegates in a session: for each proposal on the table and each of its choices
 * (Yes or No, or a civilization for the host and the World Leader) a counter, bounded by the delegates that are left.
 * Delegates may be split over any proposals and choices (CvLeague::DoVoteEnact/DoVoteRepeal, CvVotingClasses.cpp:2204-2247).
 * [castVotes] spends what was set up, the delegates that are not set up stay for later; [abstainRemaining] gives up all that are left.
 */
class LeagueVoteScreen(private val civ: Civilization) : PickerScreen() {
    private val gameInfo = civ.gameInfo
    private val league: League = checkNotNull(gameInfo.league) { "There is no World Congress" }

    /** (proposal id, choice) to the delegates set up, only positive entries */
    private val setUp = LinkedHashMap<Pair<Int, String>, Int>()
    private val abstainButton = "Abstain remaining".toTextButton()
    private val delegatesAtStart = league.members[civ.civID]?.delegatesAtStart ?: 0

    init {
        setDefaultCloseAction()
        rightSideButton.setText("Cast votes".tr())
        descriptionLabel.setText("Set up your delegates for each proposal. Delegates that you do not set up stay with you until you cast or abstain them.".tr())
        rightSideGroup.addActor(abstainButton)
        rightSideButton.onClick(UncivSound.Chimes, ::castVotes)
        abstainButton.onClick(UncivSound.Chimes, ::abstainRemaining)
        rebuild()
    }

    /** Delegates of the civilization that are not spent and not set up */
    fun getUnassignedDelegates() = league.getDelegatesLeft(civ) - setUp.values.sum()

    /** The delegates set up for [choice] of the proposal [proposalId] */
    fun getDelegates(proposalId: Int, choice: String) = setUp[proposalId to choice] ?: 0

    /** Sets up one delegate more for [choice], if one is unassigned */
    fun addDelegate(proposalId: Int, choice: String) {
        if (getUnassignedDelegates() <= 0) return
        setUp[proposalId to choice] = getDelegates(proposalId, choice) + 1
        rebuild()
    }

    /** Takes one delegate back from [choice], if there is one */
    fun removeDelegate(proposalId: Int, choice: String) {
        val count = getDelegates(proposalId, choice)
        if (count <= 0) return
        if (count == 1) setUp.remove(proposalId to choice) else setUp[proposalId to choice] = count - 1
        rebuild()
    }

    private fun getChoices(proposal: LeagueProposal): List<String> {
        if (proposal.repeal) return league.getChoices(ResolutionDecision.YesNo, civ)
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return emptyList()
        return league.getChoices(resolution.voterDecision, civ)
    }

    private fun rebuild() {
        topTable.clear()
        val unassigned = getUnassignedDelegates()
        topTable.add("Delegates left: [$unassigned] of [$delegatesAtStart]".toLabel(fontSize = Constants.headingFontSize)).pad(10f).row()

        for (proposal in league.proposals.filter { !it.onHold })
            addProposal(proposal, unassigned)

        rightSideButton.isEnabled = setUp.isNotEmpty()
    }

    private fun addProposal(proposal: LeagueProposal, unassigned: Int) {
        val resolution = gameInfo.ruleset.resolutions[proposal.resolution] ?: return
        val title = ResolutionDescriptions.getTitle(proposal.resolution, proposal.proposerChoice, gameInfo)
        topTable.add((if (proposal.repeal) "Repeal [$title]" else title).toLabel(fontSize = Constants.headingFontSize)).padTop(20f).row()
        val proposer = proposal.proposer?.let { gameInfo.getCivilizationOrNull(it) }
        topTable.add((if (proposer == null) "Proposed by the World Congress" else "Proposed by [${proposer.civName}]").toLabel(Color.LIGHT_GRAY)).row()
        if (!proposal.repeal) {
            for (line in ResolutionDescriptions.getDescription(resolution, gameInfo.ruleset))
                topTable.add(line.toLabel(Color.LIGHT_GRAY)).row()
            if (resolution.isWorldLeader)
                topTable.add("Delegates needed to be elected World Leader: [${league.getVotesNeededForWorldLeader()}]".toLabel(Color.GOLD)).row()
        }

        val choices = Table()
        choices.defaults().pad(3f)
        for (choice in getChoices(proposal)) {
            val other = gameInfo.getCivilizationOrNull(choice)
            val nameCell = Table()
            if (other != null) nameCell.add(ImageGetter.getNationPortrait(other.nation, 25f)).padRight(5f)
            nameCell.add(ResolutionDescriptions.getChoiceName(choice, gameInfo).toLabel(hideIcons = other != null))
            choices.add(nameCell).left().minWidth(150f)

            val minus = "-".toTextButton()
            if (getDelegates(proposal.id, choice) <= 0) minus.disable()
            else minus.onClick { removeDelegate(proposal.id, choice) }
            choices.add(minus)
            choices.add(getDelegates(proposal.id, choice).toString().toLabel()).minWidth(30f)
            val plus = "+".toTextButton()
            if (unassigned <= 0) plus.disable()
            else plus.onClick { addDelegate(proposal.id, choice) }
            choices.add(plus).row()
        }
        topTable.add(choices).row()
    }

    /** Spends everything that is set up, no matter whether delegates are left. Does not leave the screen. */
    fun spendSetUpDelegates() {
        for ((key, count) in setUp)
            league.vote(key.first, civ, count, key.second)
        setUp.clear()
        GUI.clearUndoCheckpoints()
    }

    /** Spends what is set up and leaves. Asks first if delegates are left that are not set up, as they keep blocking the end of the turn. */
    fun castVotes() {
        val unassigned = getUnassignedDelegates()
        if (unassigned <= 0) {
            spendSetUpDelegates()
            game.popScreen()
            return
        }
        ConfirmPopup(this,
            "[$unassigned] delegates are not set up. They stay with you and you cannot end the turn before you cast or abstain them.",
            "Cast votes", isConfirmPositive = true
        ) {
            spendSetUpDelegates()
            game.popScreen()
        }.open()
    }

    /** Spends what is set up and gives up the delegates that are left. Does not leave the screen. */
    fun giveUpRemainingDelegates() {
        spendSetUpDelegates()
        league.abstainRemaining(civ)
        GUI.clearUndoCheckpoints()
    }

    /** [giveUpRemainingDelegates] and leave */
    fun abstainRemaining() {
        giveUpRemainingDelegates()
        game.popScreen()
    }
}
