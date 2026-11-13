package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.managers.IntrigueMessage
import com.unciv.logic.civilization.managers.IntrigueType
import com.unciv.models.ruleset.ai.MajorCivApproach

/**
 *  Telling what the spies found out to others: the AI's CvDiplomacyAI::DoShareIntrigueStatement and its execution in DoSendStatementToPlayer
 *  (DIPLO_STATEMENT_SHARE_INTRIGUE), and a human's sharing with an AI (CvDiplomacyAI::DoFromUIDiploEvent, FROM_UI_DIPLO_EVENT_HUMAN_DISCUSSION_SHARE_INTRIGUE;
 *  the notification of CvNotifications::Activate that opens the AI's diplomacy). What an AI thinks of a civ that warned it is
 *  [com.unciv.logic.automation.diplomacy.OpinionAi]'s CvDiplomacyAI::GetTimesIntrigueSharedScore.
 *  Not ported: the leader scenes and their dialog texts, which belong to the original's Lua; the texts here are the original's.
 */
object IntrigueAi {

    /**
     *  CvDiplomacyAI::DoShareIntrigueStatement, DoContactPlayer's statement after the offers: the plot we found out most recently against [other]
     *  that nobody has told them yet. If another civ told them already it is only marked as shared. Said only if we are not on guard against
     *  them or hostile or at war with them in truth, and not again within a turn. Returns whether the statement was made.
     */
    fun doShareIntrigueStatement(civ: Civilization, other: Civilization): Boolean {
        val message = civ.espionageManager.getRecentIntrigueInfo(other) ?: return false
        val plotter = civ.gameInfo.getCivilizationOrNull(message.source) ?: return false
        val isNew = civ.gameInfo.civilizations.none {
            it.isMajorCiv && it != plotter && it.espionageManager.hasSharedIntrigue(other, plotter, message.type)
        }
        if (!isNew) {
            // mark this as shared so it doesn't try to interrupt the player
            civ.espionageManager.markRecentIntrigueAsShared(other, plotter, message.type)
            return false
        }
        val approach = civ.ai.diplomacy.approachTowards(other, hideTrueFeelings = false)
        if (approach == MajorCivApproach.Guarded || approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) return false
        val memory = civ.ai.diplomacy.memoryOf(other)
        if (DiplomacyAutomation.turnsSinceStatement(civ, memory, DiploStatement.ShareIntrigue) <= 1) return false

        DiplomacyAutomation.sendStatement(civ, other, DiploStatement.ShareIntrigue)
        shareWith(civ, other, message, plotter)
        return true
    }

    /**
     *  DoSendStatementToPlayer for DIPLO_STATEMENT_SHARE_INTRIGUE: unless [other] is at war with the plotter already, the plot goes into
     *  their list of intrigue (an AI keeps it, a human is told); then every civ's copy of this plot against [other] counts as shared.
     */
    private fun shareWith(civ: Civilization, other: Civilization, message: IntrigueMessage, plotter: Civilization) {
        if (!other.isAtWarWith(plotter)) {
            val city = message.cityLocation?.let { civ.gameInfo.tileMap[it].getCity() }
            other.espionageManager.addIntrigueMessage(civ, plotter, other, false, null, message.type, city, notify = false, spy = null)
            if (other.isHuman()) other.addNotification(statementText(message, plotter, city?.name), NotificationCategory.Diplomacy, civ.civName)
        }
        for (loop in civ.gameInfo.civilizations)
            if (loop.isMajorCiv) loop.espionageManager.markRecentIntrigueAsShared(other, plotter, message.type)
    }

    /** The leader's words of the original's XML (TXT_KEY_GENERIC_RESPONSE_SHARE_INTRIGUE_*), by the kind of intrigue and whether the city is known */
    private fun statementText(message: IntrigueMessage, plotter: Civilization, cityName: String?): String {
        val tail = " We hope that you will share any intrigue your agents discover with us."
        val name = plotter.civName
        return when (message.type) {
            IntrigueType.ArmySneakAttack ->
                if (cityName != null) "Our spies have uncovered that [$name] is currently marching an army to sneak attack your city of [$cityName].$tail"
                else "Our spies have uncovered that [$name] is currently marching an army your way in a sneak attack.$tail"
            IntrigueType.AmphibiousSneakAttack ->
                if (cityName != null) "Our spies have uncovered that [$name] embarked an army with naval escorts to sneak attack your city of [$cityName].$tail"
                else "Our spies have uncovered that [$name] embarked an army with naval escorts in a sneak attack against your civilization.$tail"
            else -> "Our spies have uncovered that [$name] is plotting against you.$tail"
        }
    }

    /**
     *  Whether [human] can share what its spies uncovered with [ai] (CvNotifications::Activate for the intrigue notifications): an AI major
     *  we are not at war with that a plot of someone else uncovered within the last 5 turns is aimed at and that we did not share yet.
     */
    fun canShare(human: Civilization, ai: Civilization): Boolean =
        ai.isMajorCiv && !ai.isHuman() && ai != human && !human.isAtWarWith(ai) && human.espionageManager.hasRecentIntrigueAbout(ai)

    /**
     *  FROM_UI_DIPLO_EVENT_HUMAN_DISCUSSION_SHARE_INTRIGUE: [human] tells [ai] of the plot against it. The AI remembers that it was warned
     *  (+[com.unciv.models.ruleset.ai.AiDefine.OPINION_WEIGHT_INTRIGUE_SHARED_BY] opinion weight each time) and the message counts as shared.
     *  Returns the civ that is plotting, null if there was nothing to share.
     */
    fun shareWithAi(human: Civilization, ai: Civilization): Civilization? {
        if (!canShare(human, ai)) return null
        val message = human.espionageManager.getRecentIntrigueInfo(ai) ?: return null
        val plotter = human.gameInfo.getCivilizationOrNull(message.source) ?: return null
        ai.ai.diplomacy.memoryOf(human).numTimesIntrigueShared++
        human.espionageManager.markRecentIntrigueAsShared(ai, plotter, message.type)
        return plotter
    }
}
