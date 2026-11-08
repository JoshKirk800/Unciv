package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import yairm210.purity.annotations.Readonly

/**
 *  Read-only CvMinorCivAI rules that the major civs' AI consults about a city-state ([minor]) and a major ([major]).
 *  Only existing Unciv state is read, so other AI ports can use this file as is.
 */
object MinorCivHelpers {
    /** CvMinorCivAI::GetAlliesThreshold / GetFriendsThreshold (FRIENDSHIP_THRESHOLD_ALLIES 60, _FRIENDS 30), as Unciv's
     *  [com.unciv.logic.civilization.diplomacy.DiplomacyManager.relationshipIgnoreAfraid] applies them */
    const val ALLIES_THRESHOLD = 60
    const val FRIENDS_THRESHOLD = 30

    /** Turns within which a bully counts as recent (CvMinorCivAI::IsRecentlyBulliedByMajor: iRecentlyBulliedTurnInterval = 20) -
     *  Unciv keeps it as the city-state's [DiplomacyFlags.Bullied] flag, set for that many turns by each tribute */
    @Readonly
    fun isRecentlyBulliedByMajor(minor: Civilization, major: Civilization): Boolean =
        minor.getDiplomacyManager(major)?.hasFlag(DiplomacyFlags.Bullied) == true

    /** CvMinorCivAI::IsRecentlyBulliedByAnyMajor */
    @Readonly
    fun isRecentlyBulliedByAnyMajor(minor: Civilization): Boolean =
        minor.diplomacy.values.any { it.otherCiv.isMajorCiv && it.hasFlag(DiplomacyFlags.Bullied) }

    /**
     *  CvMinorCivAI::GetNumResourcesMajorLacks: non-bonus resources the city-state has from its own lands
     *  (getNumResourceTotal without imports) that the major does not have from its own lands.
     */
    @Readonly
    fun numResourcesMajorLacks(minor: Civilization, major: Civilization): Int {
        var count = 0
        for (supply in minor.detailedCivResources) {
            val resource = supply.resource
            if (resource.resourceType == ResourceType.Bonus) continue
            if (supply.amount <= 0 || supply.isCityStateOrTradeOrigin()) continue
            if (ownTotal(major, resource) > 0) continue
            if (counted(minor, resource, supply)) continue
            count++
        }
        return count
    }

    /** Whether an earlier own-production entry of the same resource was already counted (several cities give several entries) */
    @Readonly
    private fun counted(minor: Civilization, resource: TileResource, current: Any): Boolean {
        for (supply in minor.detailedCivResources) {
            if (supply === current) return false
            if (supply.resource == resource && supply.amount > 0 && !supply.isCityStateOrTradeOrigin()) return true
        }
        return false
    }

    /** CvPlayer::getNumResourceTotal(eResource, bIncludeImport = false): what the civ produces itself, before use and exports */
    @Readonly
    fun ownTotal(civ: Civilization, resource: TileResource): Int {
        var total = 0
        for (supply in civ.detailedCivResources)
            if (supply.resource == resource && supply.amount > 0 && !supply.isCityStateOrTradeOrigin()) total += supply.amount
        return total
    }

    /** CvMinorCivAI::IsCloseToNotBeingAllies: allied with a margin under MINOR_FRIENDSHIP_CLOSE_AMOUNT */
    @Readonly
    fun isCloseToNotBeingAllies(minor: Civilization, major: Civilization): Boolean {
        val buffer = influence(minor, major) - ALLIES_THRESHOLD
        return buffer >= 0 && buffer < closeAmount(minor)
    }

    /** CvMinorCivAI::IsCloseToNotBeingFriends: friends with a margin under MINOR_FRIENDSHIP_CLOSE_AMOUNT */
    @Readonly
    fun isCloseToNotBeingFriends(minor: Civilization, major: Civilization): Boolean {
        val buffer = influence(minor, major) - FRIENDS_THRESHOLD
        return buffer >= 0 && buffer < closeAmount(minor)
    }

    /** CvMinorCivAI::GetEffectiveFriendshipWithMajor (whole points) */
    @Readonly
    fun influence(minor: Civilization, major: Civilization): Int =
        minor.getDiplomacyManager(major)?.getInfluence()?.toInt() ?: 0

    @Readonly
    private fun closeAmount(minor: Civilization): Int =
        minor.gameInfo.ruleset.ai.define(AiDefine.MINOR_FRIENDSHIP_CLOSE_AMOUNT)
}
