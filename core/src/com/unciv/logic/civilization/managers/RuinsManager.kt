package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.RuinReward
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

class RuinsManager(
    private val lastChosenRewards: ArrayList<String> = arrayListOf("", "")
) : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization
    @Transient
    lateinit var validRewards: Collection<RuinReward>

    @Readonly fun clone() = RuinsManager(ArrayList(lastChosenRewards))  // needs to deep-clone (the List, not the Strings) so undo works

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        validRewards = civInfo.gameInfo.ruleset.ruinRewards.values
    }

    private fun rememberReward(reward: String) {
        lastChosenRewards[0] = lastChosenRewards[1]
        lastChosenRewards[1] = reward
    }

    /** A picker sees the same rewards as a normal explorer, minus rewards whose effect cannot currently be triggered. */
    fun getEligibleRewards(unit: MapUnit): List<RuinReward> =
        validRewards.filter { reward ->
            reward.weight > 0 && isPossibleReward(reward, unit) &&
                reward.uniqueObjects.any { unique ->
                    unique.type != UniqueType.PlaySound && unique.conditionalsApply(unit.cache.state) &&
                        UniqueTriggerActivation.getTriggerFunction(unique, civInfo, unit = unit, tile = unit.getTile()) != null
                }
        }

    @Readonly
    fun hasPendingChoice(unit: MapUnit): Boolean =
        civInfo.popupAlerts.any { it.type == AlertType.RuinsReward && it.value == unit.id.toString() }

    fun offerChoice(unit: MapUnit) {
        if (getEligibleRewards(unit).isEmpty()) {
            selectNextRuinsReward(unit)
            return
        }
        if (!hasPendingChoice(unit))
            civInfo.popupAlerts.add(PopupAlert(AlertType.RuinsReward, unit.id.toString()))
    }

    /** Recheck legality at selection time; the alert is the saved, single-use authority for this ruin. */
    fun chooseReward(unit: MapUnit, rewardName: String): Boolean {
        if (unit.isDestroyed || unit.civ != civInfo || !hasPendingChoice(unit)) return false
        val reward = getEligibleRewards(unit).firstOrNull { it.name == rewardName } ?: return false
        if (!applyReward(reward, unit)) return false
        civInfo.popupAlerts.removeAll { it.type == AlertType.RuinsReward && it.value == unit.id.toString() }
        return true
    }
    /** A reward may become ineligible while a saved picker is open (for example, the city was lost).
     *  Give the ordinary eligible reward instead of silently losing an already consumed ruin. */
    fun resolveUnavailableChoice(unit: MapUnit) {
        if (!hasPendingChoice(unit)) return
        if (!unit.isDestroyed && unit.civ == civInfo) selectNextRuinsReward(unit)
        civInfo.popupAlerts.removeAll { it.type == AlertType.RuinsReward && it.value == unit.id.toString() }
    }


    @Readonly
    private fun getShuffledPossibleRewards(triggeringUnit: MapUnit): Iterable<RuinReward> {
        val candidates =
            validRewards.asSequence().filter { isPossibleReward(it, triggeringUnit) }
            // This might be a dirty way to do this, but it works (we do have randomWeighted in CollectionExtensions, but below we
            // need to choose another when the first choice's TriggerActivations report failure, and that's simpler this way)
            // For each possible reward, this feeds (reward.weight) copies of this reward to the overall Sequence to implement 'weight'.
            .flatMap { reward -> generateSequence { reward }.take(reward.weight) }
            // Convert to List since Sequence.shuffled would do one anyway, Mutable so shuffle doesn't need to pull a copy
            .toMutableList()
        // The resulting List now gets shuffled, using a tile-based random to thwart save-scumming.
        // Note both Sequence.shuffled and Iterable.shuffled (with a 'd') always pull an extra copy of a MutableList internally, even if you feed them one.
        candidates.shuffle(Random(triggeringUnit.getTile().position.toVector2().hashCode()))
        return candidates
    }

    @Readonly
    private fun isPossibleReward(ruinReward: RuinReward, unit: MapUnit): Boolean {
        if (ruinReward.name in lastChosenRewards) return false
        if (ruinReward.isUnavailableBySettings(civInfo.gameInfo)) return false
        val gameContext = GameContext(civInfo, unit = unit, tile = unit.getTile())
        if (ruinReward.hasUnique(UniqueType.Unavailable, gameContext)) return false
        if (ruinReward.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(gameContext) }) return false
        return true
    }

    fun selectNextRuinsReward(triggeringUnit: MapUnit) {
        for (possibleReward in getShuffledPossibleRewards(triggeringUnit)) {
            if (applyReward(possibleReward, triggeringUnit)) break
        }
    }

    private fun applyReward(reward: RuinReward, unit: MapUnit): Boolean {
        var atLeastOneUniqueHadEffect = false
        for (unique in reward.uniqueObjects) {
            if (!unique.conditionalsApply(unit.cache.state)) continue
            var uniqueTriggered = false
            repeat(unique.getUniqueMultiplier(unit.cache.state)) {
                uniqueTriggered =
                    UniqueTriggerActivation.triggerUnique(
                        unique,
                        unit,
                        notification = reward.notification,
                        triggerNotificationText = "from the ruins"
                    ) || uniqueTriggered
            }
            atLeastOneUniqueHadEffect = atLeastOneUniqueHadEffect || uniqueTriggered
        }
        if (atLeastOneUniqueHadEffect) rememberReward(reward.name)
        return atLeastOneUniqueHadEffect
    }
}
