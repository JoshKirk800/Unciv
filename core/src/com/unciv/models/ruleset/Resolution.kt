package com.unciv.models.ruleset

import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** What a member of the World Congress decides for a [Resolution] (voter), or chooses when proposing it (proposer) (CvVotingClasses.cpp, CvResolutionInfo) */
enum class ResolutionDecision {
    None,
    /** Vote for or against the proposal */
    YesNo,
    /** Vote for or choose a major civilization */
    MajorCiv,
    /** Choose a major civilization other than oneself */
    OtherMajorCiv,
    /** Choose a luxury resource */
    Luxury,
    /** Choose a religion */
    Religion,
    /** Choose an ideology */
    Ideology
}

/**
 * A resolution the World Congress can enact (Resolutions.json, CvResolutionInfo in the original).
 * The [uniques] apply to every civilization while the resolution is in effect; the other effects are the typed fields.
 */
class Resolution : RulesetObject() {
    /** [ResolutionDecision.YesNo] for most, [ResolutionDecision.MajorCiv] for the host election and World Leader */
    var voterDecision = ResolutionDecision.YesNo
    /** What the proposer has to choose: None, OtherMajorCiv (Embargo), Luxury (Ban Luxury), Religion or Ideology */
    var proposerDecision = ResolutionDecision.None
    /** The resolution can only be proposed while any living civilization knows this technology */
    var requiredTechAnyMember: String? = null
    /** False for the host election and World Leader, which the World Congress proposes itself */
    var playerProposable = true
    /** Only one instance can be active or proposed, whatever the choice (World Religion, World Ideology) */
    var exclusive = false
    /** The leaders of the vote get this many delegates more if the vote fails (World Leader) */
    var leadersVoteBonusOnFail = 0
    /** Delegates a civilization gets more for voting for the chosen religion, if it follows it */
    var votesForFollowingReligion = 0
    /** Delegates a civilization gets more for voting for the chosen ideology, if it follows it */
    var votesForFollowingIdeology = 0
    /** Percent more tourism of the holy city of the chosen religion */
    var holyCityTourismPercent = 0
    /** Percent more spread strength of the chosen religion */
    var religionSpreadStrengthPercent = 0
    /** Ideological pressure of the chosen ideology on all civilizations */
    var ideologyPressure = 0
    /** Trade routes to city-states are forbidden */
    var embargoesCityStates = false
    /** Trade routes with the civilization the proposer chose are forbidden */
    var embargoesPlayer = false
    /** The luxury the proposer chose gives no happiness and cannot be traded */
    var bansLuxuryHappiness = false

    override fun getUniqueTarget() = UniqueTarget.Resolution

    /** There is no Civilopedia category for resolutions */
    override fun makeLink() = ""

    /** The resolution elects the host of the World Congress */
    val isHostElection get() = hasUnique(UniqueType.ElectsWorldCongressHost)

    /** The resolution elects the World Leader, winning the Diplomatic Victory */
    val isWorldLeader get() = hasUnique(UniqueType.ElectsWorldLeader)

    /** Whether the game settings allow this resolution (see [UniqueType.OnlyAvailable]) */
    @Readonly
    fun isAvailableInGame(gameInfo: GameInfo): Boolean = !isUnavailableBySettings(gameInfo)
}
