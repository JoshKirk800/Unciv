package com.unciv.logic.automation.diplomacy

/*
 * The state types of CvDiplomacyAI (CvDiplomacyAIEnums.h), in the original's order: the AI compares several of them with < and >.
 */

/** MajorCivOpinionTypes: worst to best */
enum class MajorCivOpinion { Unforgivable, Enemy, Competitor, Neutral, Favorable, Friend, Ally }

/** WarProjectionTypes: how a war against a player would go, worst to best */
enum class WarProjection { Destruction, Defeat, Stalemate, Unknown, Good, VeryGood }

/** WarGoalTypes; "no war goal" is null */
enum class WarGoal { Demand, Prepare, Conquest, Damage, Peace }

/** PeaceTreatyTypes: what a side gives up for peace, nothing to everything. [None] = NO_PEACE_TREATY_TYPE */
enum class PeaceTreatyType { None, WhitePeace, Armistice, Settlement, Backdown, Submission, Surrender, Cession, Capitulation, UnconditionalSurrender }

/** ThreatTypes (military threat, warmonger threat) */
enum class AiThreat { None, Minor, Major, Severe, Critical }

/** DisputeLevelTypes (land, wonder, minor civ, victory) */
enum class DisputeLevel { None, Weak, Strong, Fierce }

/** AggressivePostureTypes (expansion, plot buying, military) */
enum class AggressivePosture { None, Low, Medium, High, Incredible }

/** WarDamageLevelTypes */
enum class WarDamageLevel { None, Minor, Major, Serious, Crippled }

/** WarFaceTypes: the approach shown while planning war; "no war face" is null */
enum class WarFace { Hostile, Friendly, Neutral }

/** CoopWarStates; "no state" is null */
enum class CoopWarState { Rejected, Soon, Accepted }

/** The DiploStatementTypes this port sends between AIs, kept in the statement log for their resend intervals */
enum class DiploStatement {
    WorkWithUs, WorkWithUsRandFailed, DenounceFriend, Denounce, DenounceRandFailed, RequestFriendDenounce, CoopWarRequest,
    Demand, Request, RequestRandFailed, RequestPeace,
}
