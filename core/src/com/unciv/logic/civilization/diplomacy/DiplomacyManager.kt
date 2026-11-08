package com.unciv.logic.civilization.diplomacy

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.automation.diplomacy.CoopWarState
import com.unciv.logic.automation.diplomacy.DiplomacyEvents
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.PromiseAi
import com.unciv.logic.automation.diplomacy.WarFace
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.ui.components.extensions.toPercent
import com.unciv.utils.Log
import yairm210.purity.annotations.Cache
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Immutable
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

enum class RelationshipLevel(val color: Color) {
    // DiplomaticStatus.War is tested separately for the Diplomacy Screen. Colored RED.
    // DiplomaticStatus.DefensivePact - similar. Colored PURPLE.
    Unforgivable(Color.BROWN),
    Enemy(Color.ORANGE),
    Afraid(Color.YELLOW),
    Competitor(Color.GRAY),
    Neutral(Color.WHITE),
    Favorable(Color(0x14cc3cff)),  // HSV(133,90,80)
    Friend(Color.ROYAL),
    Ally(Color.CYAN)
    ;
    @Pure
    operator fun plus(delta: Int): RelationshipLevel {
        val newOrdinal = (ordinal + delta).coerceIn(0, entries.size - 1)
        return entries[newOrdinal]
    }
}

enum class DiplomacyFlags {
    DeclinedLuxExchange,
    DeclinedGreatWorkExchange,
    DeclinedPeace,
    DeclinedResearchAgreement,
    DeclinedEmbassy,
    DeclinedOpenBorders,
    DeclaredWar,
    DeclarationOfFriendship,
    DeclinedDeclarationOfFriendship,
    DefensivePact,
    DeclinedDefensivePact,
    DeclinedJoinWarOffer,
    ResearchAgreement,
    BorderConflict,
    TilesStolen,

    SettledCitiesNearUs,
    AgreedToNotSettleNearUs,
    IgnoreThemSettlingNearUs,

    SpreadReligionInOurCities,
    AgreedToNotSpreadReligion,
    IgnoreThemSpreadingReligion,

    DiscoveredSpiesInOurCities,
    AgreedToNotSendSpies,
    IgnoreThemSendingSpies,
    DugInOurLands,
    AgreedToNotDigInOurLands,
    IgnoreThemDiggingInOurLands,
    
    AgreedToNotAttackUs,
    MilitaryPresenceNearBorderOrAttackedUsDespitePromise,
    IgnoreMilitaryPresenceNearBorder,

    ProvideMilitaryUnit,
    MarriageCooldown,
    NotifiedAfraid,
    RecentlyPledgedProtection,
    RecentlyWithdrewProtection,
    AngerFreeIntrusion,


    Denunciation,
    WaryOf,
    Bullied,
    RecentlyAttacked,
    ResourceTradesCutShort,
}

/**
 *  The opinion one major civilization holds of another, term by term: the opinion weights of CvDiplomacyAI::GetMajorCivOpinionWeight, sign
 *  flipped (positive = good). [com.unciv.logic.automation.diplomacy.OpinionAi] rewrites every [isOpinionTerm] entry each turn from the AI's memory.
 *  The three embassy entries are also the embassy state (who has an embassy where); their value is the embassy opinion term.
 */
enum class DiplomaticModifiers(val text: String, val isOpinionTerm: Boolean = true) {
    // Negative
    LeaderPersonality("Our leader's temperament"),
    LandDispute("You are taking land we want"),
    WonderDispute("You have beaten us to Wonders"),
    MinorCivDispute("You are allied with City-States we protect"),
    WarMongerer("Your warmongering ways are unacceptable to us."),
    CapturedOurCities("You have captured our capital!"),
    DeclaredFriendshipWithOurEnemies("You have declared friendship with our enemies!"),
    BetrayedDeclarationOfFriendship("Your so-called 'friendship' is worth nothing."),
    Denunciation("You have publicly denounced us!"),
    WeDenouncedThem("We have publicly denounced you"),
    DenouncedOurAllies("You have denounced our allies"),
    AskedUsNotToSettle("You asked us not to settle near you"),
    AskedUsToStopSpying("You asked us to stop spying on you"),
    RefusedToNotSettleCitiesNearUs("You refused to stop settling cities near us"),
    RefusedToNotSpreadReligionToUs("You refused to stop spreading religion to us"),
    RefusedToNotSendingSpiesToUs("You refused to stop spying on us"),
    RefusedToPromiseNotToAttackUs("You refused to promise not to attack us"),
    IgnoredBorderPromise("You refused to stop buying land near us"),
    BetrayedPromiseToNotSettleCitiesNearUs("You betrayed your promise to not settle cities near us"),
    BetrayedPromiseToNotSpreadReligionToUs("You betrayed your promise to not spread your religion to us"),
    BetrayedPromiseToNotSendingSpiesToUs("You betrayed your promise to stop spying on us"),
    BetrayedPromiseToNotDigInOurLands("You broke your promise to stop digging in our lands"),
    BetrayedPromiseToNotAttackUs("You betrayed your promise to not attack us!"),
    BetrayedPromiseToNotAttackOtherCiv("You were observed betraying your promise to not declare war!"),
    BrokenBorderPromise("You broke your promise to stop buying land near us"),
    BrokenCoopWarPromise("You did not go to war as you promised"),
    UnacceptableDemands("Your arrogant demands are in bad taste"),
    UsedNuclearWeapons("You have used nuclear weapons against us!"),
    StealingTerritory("You have stolen our lands!"),
    ReligiousConversions("You have converted our cities to your religion"),
    DifferentIdeology("Your ideology is a threat to our way of life"),
    DestroyedProtectedMinor("You destroyed City-States that were under our protection!"),
    AttackedProtectedMinor("You attacked City-States that were under our protection!"),
    BulliedProtectedMinor("You demanded tribute from City-States that were under our protection!"),
    SidedWithProtectedMinor("You sided with a City-State over us"),
    SpiedOnUs("You stole technology from us!"),
    RecklessExpander("You are expanding recklessly"),
    RefusedToHelpUs("You refused to help us"),
    DislikedTheirProposal("We dislike your proposals in the World Congress"),
    FoiledOurProposal("You foiled our proposal in the World Congress"),

    IgnoredRequestToStopDigging("You refused to stop digging in our lands"),
    // Positive
    EstablishedEmbassy("We have an embassy in your capital", isOpinionTerm = false),
    ReceivedEmbassy("You have an embassy in our capital", isOpinionTerm = false),
    SharedEmbassies("We have shared embassies", isOpinionTerm = false),
    NoLandDispute("We have no quarrel over land"),
    SharedEnemy("Our mutual military struggle brings us closer together."),
    LiberatedCity("We applaud your liberation of conquered cities!"),
    ResurrectedUs("You brought us back to life!"),
    DeclarationOfFriendship("We have signed a public declaration of friendship"),
    DeclaredFriendshipWithOurAllies("You have declared friendship with our allies"),
    DenouncedOurEnemies("You have denounced our enemies"),
    TradedWithUs("We have traded with you recently"),
    HelpedUs("You gave us what we asked for"),
    BuiltLandmarksForUs("You built Landmarks in our lands"),
    ReturnedCapturedUnits("You returned captured units to us"),
    WeAdoptedTheirReligion("We have adopted your religion"),
    TheyAdoptedOurReligion("You have adopted our religion"),
    SameIdeology("We share the same ideology"),
    LikedTheirProposal("We like your proposals in the World Congress"),
    SupportedOurProposal("You supported our proposal in the World Congress"),
    SupportedOurHosting("You supported us as host of the World Congress");

    companion object{
        @Immutable private val valuesAsMap = entries.associateBy { it.name }
        @Pure fun safeValueOf(name: String) = valuesAsMap[name]
    }
}

class DiplomacyManager() : IsPartOfGameInfoSerialization {

    companion object {
        /** The value city-state influence can't go below */
        const val MINIMUM_INFLUENCE = -60f
    }

    @Transient
    lateinit var civInfo: Civilization

    lateinit var otherCivName: String
        private set
    @Cache
    @Transient
    private lateinit var _otherCiv: Civilization
    @get:Readonly val otherCiv: Civilization get() {
        if (!::_otherCiv.isInitialized)
            _otherCiv = civInfo.gameInfo.getCivilization(otherCivName)
        return _otherCiv
    }
    
    @Cache
    @Transient
    private lateinit var _context: GameContext
    @get:Readonly val state: GameContext get() {
        if (!::_context.isInitialized)
            _context = GameContext(civInfo, otherCiv)
        return _context
    }

    // since this needs to be checked a lot during travel, putting it in a transient is a good performance booster
    @Transient
    /** Can civInfo enter otherCivInfo's tiles? */
    var hasOpenBorders = false

    var trades = ArrayList<Trade>()
    var diplomaticStatus = DiplomaticStatus.War

    /** Contains various flags (declared war, promised to not settle, declined luxury trade) and the number of turns in which they will expire.
     *  The JSON serialize/deserialize REFUSES to deserialize hashmap keys as Enums, so I'm forced to use strings instead =(
     *  This is so sad Alexa play Despacito */
    internal var flagsCountdown = HashMap<String, Int>()

    /** The opinion of the other civ, term by term (positive is good): see [DiplomaticModifiers].
     * As for why it's String and not DiplomaticModifier see FlagsCountdown comment */
    var diplomaticModifiers = HashMap<String, Float>()
    /** CvDiplomacyAI archaeology counters, from the landowner's perspective. */
    var negativeArchaeologyPoints = 0
    var landmarksBuiltForUs = 0

    /** For city-states. Influence is saved in the CITY STATE -> major civ Diplomacy, NOT in the major civ -> city state diplomacy.
     * Access via getInfluence() and setInfluence() unless you know what you're doing.
     * Note that not using the setter skips recalculating the ally and bounds checks,
     * and skipping the getter bypasses the modified value when at war */
    internal var influence = 0f

    /** Total of each turn Science during Research Agreement */
    internal var totalOfScienceDuringRA = 0

    fun clone(): DiplomacyManager {
        val toReturn = DiplomacyManager()
        toReturn.otherCivName = otherCivName
        toReturn.diplomaticStatus = diplomaticStatus
        toReturn.trades.addAll(trades.map { it.clone() })
        toReturn.influence = influence
        toReturn.flagsCountdown.putAll(flagsCountdown)
        toReturn.diplomaticModifiers.putAll(diplomaticModifiers)
        toReturn.negativeArchaeologyPoints = negativeArchaeologyPoints
        toReturn.landmarksBuiltForUs = landmarksBuiltForUs
        toReturn.totalOfScienceDuringRA = totalOfScienceDuringRA
        return toReturn
    }

    // called when we meet a new civ
    constructor(civilization: Civilization, otherCivName: String) : this() {
        civInfo = civilization
        this.otherCivName = otherCivName
        updateHasOpenBorders()
    }

    constructor(civilization: Civilization, otherCiv: Civilization) : this() {
        civInfo = civilization
        _otherCiv = otherCiv
        otherCivName = otherCiv.civID
        updateHasOpenBorders()
    }

    //region pure functions
    @Readonly fun otherCivDiplomacy() = otherCiv.getDiplomacyManager(civInfo)!!

    @Readonly
    fun turnsToPeaceTreaty(): Int {
        for (trade in trades)
            for (offer in trade.ourOffers)
                if (offer.name == Constants.peaceTreaty && offer.duration > 0) return offer.duration
        return 0
    }

    /**
     * Reflects how we perceive the other civ, and affects willingness to trade, declare war, etc..
     * The other civ can view this number in the diplimacy screen.
     * Between majors it is minus CvDiplomacyAI::GetMajorCivOpinionWeight, kept term by term in [diplomaticModifiers]
     * by [com.unciv.logic.automation.diplomacy.OpinionAi].
     */
    @Readonly
    fun opinionOfOtherCiv(): Float = diplomaticModifiers.values.sum()

    /** Related to [relationshipLevel], this compares with a specific outcome.
     *
     *  It is cheap unless you ask such that Neutral / Afraid on a CityState need to be distinguished and influence is currently in 0 until 30.
     *  Thus it can be far cheaper than first retrieving [relationshipLevel] and then comparing.
     *
     *  Readability shortcuts: [isRelationshipLevelEQ], [isRelationshipLevelGE], [isRelationshipLevelGT], [isRelationshipLevelLE], [isRelationshipLevelLT]
     *
     *  @param comparesAs same as [RelationshipLevel.compareTo]
     *  @return `true` if [relationshipLevel] ().compareTo([level]) == [comparesAs] - or: when [comparesAs] > 0 only if [relationshipLevel] > [level] and so on.
     */
    @Readonly
    private fun compareRelationshipLevel(level: RelationshipLevel, comparesAs: Int): Boolean {
        if (!civInfo.isCityState)
            return relationshipLevel().compareTo(level).sign == comparesAs
        return when(level) {
            RelationshipLevel.Afraid -> when {
                comparesAs < 0 -> getInfluence() < 0
                comparesAs > 0 -> getInfluence() >= 30 || relationshipLevel() > level
                else -> getInfluence().let { it >= 0 && it < 30 } && relationshipLevel() == level
            }
            RelationshipLevel.Neutral -> when {
                comparesAs < 0 -> getInfluence() < 0 || relationshipLevel() < level
                comparesAs > 0 -> getInfluence() >= 30
                else -> getInfluence().let { it >= 0 && it < 30 } && relationshipLevel() == level
            }
            else ->
                // Outside the potentially expensive questions, do it the easy way
                // Except - Enum.compareTo does not behave quite like other compareTo's
                // or like kotlinlang.org says, thus the `sign`
                relationshipLevel().compareTo(level).sign == comparesAs
        }
    }
    /** @see compareRelationshipLevel */
    @Readonly fun isRelationshipLevelEQ(level: RelationshipLevel) =
            compareRelationshipLevel(level, 0)
    /** @see compareRelationshipLevel */
    @Readonly fun isRelationshipLevelLT(level: RelationshipLevel) =
            compareRelationshipLevel(level, -1)
    /** @see compareRelationshipLevel */
    @Readonly fun isRelationshipLevelGT(level: RelationshipLevel) =
            compareRelationshipLevel(level, 1)
    /** @see compareRelationshipLevel */
    @Readonly fun isRelationshipLevelLE(level: RelationshipLevel) =
            if (level == RelationshipLevel.Ally) true
            else compareRelationshipLevel(level + 1, -1)
    /** @see compareRelationshipLevel */
    @Readonly
    fun isRelationshipLevelGE(level: RelationshipLevel) =
            if (level == RelationshipLevel.Unforgivable) true
            else compareRelationshipLevel(level + -1, 1)

    /** Careful: Cheap unless this is a CityState and influence is in 0 until 30,
     *  where the distinction Neutral/Afraid gets expensive.
     *  @see compareRelationshipLevel
     *  @see relationshipIgnoreAfraid
     */
    @Readonly
    fun relationshipLevel(): RelationshipLevel {
        val level = relationshipIgnoreAfraid()
        return when {
            level != RelationshipLevel.Neutral || !civInfo.isCityState -> level
            civInfo.cityStateFunctions.getTributeWillingness(otherCiv) > 0 -> RelationshipLevel.Afraid
            else -> RelationshipLevel.Neutral
        }
    }

    /** Same as [relationshipLevel] but omits the distinction Neutral/Afraid, which can be _much_ cheaper */
    @Readonly
    fun relationshipIgnoreAfraid(): RelationshipLevel {
        if (civInfo.isHuman() && otherCiv.isHuman())
            return RelationshipLevel.Neutral // People make their own choices.

        if (civInfo.isHuman())
            return otherCivDiplomacy().relationshipLevel()

        if (civInfo.isCityState) return when {
            getInfluence() <= -30 -> RelationshipLevel.Unforgivable  // getInfluence tests isAtWarWith
            getInfluence() < 0 -> RelationshipLevel.Enemy
            getInfluence() >= 60 && civInfo.allyCiv == otherCiv -> RelationshipLevel.Ally
            getInfluence() >= 30 -> RelationshipLevel.Friend
            else -> RelationshipLevel.Neutral
        }

        // CvDiplomacyAI::DoUpdateOnePlayerOpinion: the opinion is minus the opinion weight, the levels are the OPINION_THRESHOLD_* defines
        val weight = -opinionOfOtherCiv()
        val rules = civInfo.gameInfo.ruleset.ai
        return when {
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_UNFORGIVABLE) -> RelationshipLevel.Unforgivable
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_ENEMY) -> RelationshipLevel.Enemy
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_COMPETITOR) -> RelationshipLevel.Competitor
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_FAVORABLE) -> RelationshipLevel.Neutral
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_FRIEND) -> RelationshipLevel.Favorable
            weight >= rules.define(AiDefine.OPINION_THRESHOLD_ALLY) -> RelationshipLevel.Friend
            else -> RelationshipLevel.Ally
        }
    }


    /** Returns the number of turns to degrade from Ally or from Friend */
    @Readonly
    fun getTurnsToRelationshipChange(): Int {
        if (otherCiv.isCityState)
            return otherCivDiplomacy().getTurnsToRelationshipChange()

        if (civInfo.isCityState && !otherCiv.isCityState) {
            val dropPerTurn = getCityStateInfluenceDegrade()
            return when {
                dropPerTurn == 0f -> 0
                isRelationshipLevelEQ(RelationshipLevel.Ally) -> ceil((getInfluence() - 60f) / dropPerTurn).toInt() + 1
                isRelationshipLevelEQ(RelationshipLevel.Friend) -> ceil((getInfluence() - 30f) / dropPerTurn).toInt() + 1
                else -> 0
            }
        }
        return 0
    }

    fun addInfluence(amount: Float) {
        setInfluence(influence + amount)
    }

    /**
     * Reduces the influence to zero, or if they have negative influence does nothing
     * @param amount A positive value to subtract from the influecne
     */
    fun reduceInfluence(amount: Float) {
        if (influence <= 0) return
        influence = (influence - amount).coerceAtLeast(0f)
    }

    fun setInfluence(amount: Float) {
        influence = max(amount, MINIMUM_INFLUENCE)
        civInfo.cityStateFunctions.updateAllyCivForCityState()
    }

    @VisibleForTesting
    fun setInfluenceWithoutSideEffects(amount: Float) {
        influence =amount
    }

    @Readonly
    fun getInfluence() = if (civInfo.isAtWarWith(otherCiv)) MINIMUM_INFLUENCE else influence

    // To be run from City-State DiplomacyManager, which holds the influence. Resting point for every major civ can be different.
    @Readonly
    internal fun getCityStateInfluenceRestingPoint(): Float {
        var restingPoint = 0f

        otherCiv.forEachMatchingUnique(UniqueType.CityStateRestingPoint) { unique ->
            restingPoint += unique.params[0].toInt()
        }

        if (civInfo.cities.any() && civInfo.getCapital() != null)
            otherCiv.forEachMatchingUnique(UniqueType.RestingPointOfCityStatesFollowingReligionChange) { unique ->
                // CvPlayerReligions::GetCityStateMinimumInfluence: only for the religion the founder still has the founder benefits of
                val founderReligionName = otherCiv.religionManager.getFounderBenefitsReligion()?.name
                if (founderReligionName != null && founderReligionName == civInfo.getCapital()!!.religion.getMajorityReligionName())
                    restingPoint += unique.params[0].toInt()
            }

        if (diplomaticStatus == DiplomaticStatus.Protector) restingPoint += 10

        if (hasFlag(DiplomacyFlags.WaryOf)) restingPoint -= 20

        return restingPoint
    }

    @Readonly
    internal fun getCityStateInfluenceDegrade(): Float {
        if (getInfluence() <= getCityStateInfluenceRestingPoint())
            return 0f

        val decrement = when {
            civInfo.cityStatePersonality == CityStatePersonality.Hostile -> 1.5f
            otherCiv.isMinorCivAggressor() -> 2f
            else -> 1f
        }

        var modifierPercent = 0f
        otherCiv.forEachMatchingUnique(UniqueType.CityStateInfluenceDegradation) { unique ->
            modifierPercent += unique.params[0].toFloat()
        }

        val religion = if (civInfo.cities.isEmpty() || civInfo.getCapital() == null) null
            else civInfo.getCapital()!!.religion.getMajorityReligionName()
        if (religion != null && religion == otherCiv.religionManager.religion?.name)
            modifierPercent -= 25f  // 25% slower degrade when sharing a religion

        for (civ in civInfo.gameInfo.civilizations.filter { it.isMajorCiv && it != otherCiv}) {
            civ.forEachMatchingUnique(UniqueType.OtherCivsCityStateRelationsDegradeFaster) { unique ->
                modifierPercent += unique.params[0].toFloat()
            }
        }

        return max(0f, decrement) * max(-100f, modifierPercent).toPercent()
    }


    @Readonly
    fun canDeclareWar() = !civInfo.isDefeated() && !otherCiv.isDefeated()
            && turnsToPeaceTreaty() == 0 && diplomaticStatus != DiplomaticStatus.War

    fun declareWar(declareWarReason: DeclareWarReason = DeclareWarReason(WarType.DirectWar)) =
        DeclareWar.declareWar(this, declareWarReason)

    //Used for nuke
    @Readonly fun canAttack() = turnsToPeaceTreaty() == 0

    @Readonly
    fun goldPerTurn(): Int {
        var goldPerTurnForUs = 0
        for (trade in trades) {
            for (offer in trade.ourOffers.filter { it.type == TradeOfferType.Gold_Per_Turn })
                goldPerTurnForUs -= offer.amount
            for (offer in trade.theirOffers.filter { it.type == TradeOfferType.Gold_Per_Turn })
                goldPerTurnForUs += offer.amount
        }
        return goldPerTurnForUs
    }
    
    @Readonly
    fun resourcesFromTrade(): ResourceSupplyList {
        val newResourceSupplyList = ResourceSupplyList()
        val resourcesMap = civInfo.gameInfo.ruleset.tileResources
        val isResourceFilter: (TradeOffer) -> Boolean = {
            (it.type == TradeOfferType.Strategic_Resource || it.type == TradeOfferType.Luxury_Resource)
                    && resourcesMap.containsKey(it.name)
                    && !resourcesMap[it.name]!!.isStockpiled
        }
        for (trade in trades) {
            for (offer in trade.ourOffers.filter(isResourceFilter))
                newResourceSupplyList.add(resourcesMap[offer.name]!!, "Trade", -offer.amount)
            for (offer in trade.theirOffers.filter(isResourceFilter))
                newResourceSupplyList.add(resourcesMap[offer.name]!!, "Trade", offer.amount)
        }

        return newResourceSupplyList
    }

    /** Returns the [civilizations][Civilization] that know about both sides ([civInfo] and [otherCiv]) */
    @Readonly fun getCommonKnownCivs(): Set<Civilization> = civInfo.getKnownCivs().asIterable().intersect(otherCiv.getKnownCivs().toSet())

    @Readonly fun getCommonKnownCivsWithSpectators(): Set<Civilization> = civInfo.getKnownCivsWithSpectators().asIterable().intersect(otherCiv.getKnownCivsWithSpectators().toSet())
    /** Returns true when the [civInfo]'s territory is considered allied for [otherCiv].
     *  This includes friendly and allied city-states and the open border treaties.
     */
    @Readonly
    fun isConsideredFriendlyTerritory(): Boolean {
        if (civInfo.isCityState &&
            (isRelationshipLevelGE(RelationshipLevel.Friend) || otherCiv.hasUnique(UniqueType.CityStateTerritoryAlwaysFriendly)))
            return true

        return otherCivDiplomacy().hasOpenBorders // if THEY can enter US then WE are considered friendly territory for THEM
    }
    //endregion

    //region state-changing functions
    // for performance reasons we don't want to call this every time we want to see if a unit can move through a tile
    fun updateHasOpenBorders() {
        // City-states can enter ally's territory (the opposite is true anyway even without open borders)
        val newHasOpenBorders = civInfo.allyCiv == otherCiv
                || trades.flatMap { it.theirOffers }.any { it.name == Constants.openBorders && it.duration > 0 }

        val bordersWereClosed = hasOpenBorders && !newHasOpenBorders
        hasOpenBorders = newHasOpenBorders

        if (bordersWereClosed) { // borders were closed, get out!
            for (unit in civInfo.units.getCivUnits()
                .filter { it.currentTile.getOwner() == otherCiv }.toList()) {
                unit.movement.teleportToClosestMoveableTile()
            }
        }
    }

    /** Should only be called from makePeace */
    private fun makePeaceOneSide() {
        diplomaticStatus = DiplomaticStatus.Peace
        val otherCiv = otherCiv
        // Get out of others' territory
        for (unit in civInfo.units.getCivUnits().filter { it.getTile().getOwner() == otherCiv }.toList())
            unit.movement.teleportToClosestMoveableTile()

        for (thirdCiv in civInfo.getKnownCivs()) {
            // Our ally city states make peace with us
            if (thirdCiv.allyCiv == civInfo && thirdCiv.isAtWarWith(otherCiv)) {
                val thirdCivDiplo = thirdCiv.getDiplomacyManager(otherCiv)!!
                thirdCivDiplo.makePeace()

                // Make the peace treaty so that the civ can't declare war immedietly
                val tradeLogic = TradeLogic(thirdCiv, otherCiv)
                tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civInfo.gameInfo.speed))
                tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civInfo.gameInfo.speed))
                thirdCivDiplo.trades.add(tradeLogic.currentTrade)
                thirdCivDiplo.otherCivDiplomacy().trades.add(tradeLogic.currentTrade.reverse())
            }
            // Other City-States that are not our ally don't like the fact that we made peace with their enemy
            if (thirdCiv.allyCiv != civInfo && thirdCiv.isAtWarWith(otherCiv))
                thirdCiv.getDiplomacyManager(civInfo)!!.addInfluence(-10f)
        }
    }


    fun makePeace() {
        makePeaceOneSide()
        otherCivDiplomacy().makePeaceOneSide()
        DiplomacyEvents.onPeace(civInfo, otherCiv)
        if (otherCiv.isCityState) civInfo.cache.updateCivResources()
        if (civInfo.isCityState) otherCiv.cache.updateCivResources()

        for (civ in getCommonKnownCivsWithSpectators()) {
            civ.addNotification(
                    "[${civInfo.civName}] and [${otherCiv.civName}] have signed a Peace Treaty!",
                    NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy, otherCiv.civName
            )
        }
        
        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponSigningPeace, ignoreCities = false) { unique ->
            if (otherCiv.matchesFilter(unique.params[0])) {
                UniqueTriggerActivation.triggerUnique(unique, civInfo)
            }
        }

        otherCiv.forEachTriggeredUnique(UniqueType.TriggerUponSigningPeace, ignoreCities = false) { unique ->
            if (civInfo.matchesFilter(unique.params[0])) {
                UniqueTriggerActivation.triggerUnique(unique, otherCiv)
            }
        }
    }

    @Readonly fun hasFlag(flag: DiplomacyFlags) = flagsCountdown.containsKey(flag.name)
    
    fun setFlag(flag: DiplomacyFlags, amount: Int, adjustWithGameSpeed: Boolean = false) {
        flagsCountdown[flag.name] =
            if (adjustWithGameSpeed) (amount * civInfo.gameInfo.speed.modifier).roundToInt()
            else amount
    }

    /** 0 indicates 'flag does not exist' */
    @Readonly fun getFlag(flag: DiplomacyFlags) = flagsCountdown[flag.name] ?: 0
    fun removeFlag(flag: DiplomacyFlags) {
        flagsCountdown.remove(flag.name)
    }

    fun addModifier(modifier: DiplomaticModifiers, amount: Float, limit: Float? = null) {
        val modifierString = modifier.name
        if (!hasModifier(modifier)) setModifier(modifier, 0f)
        var newValue = diplomaticModifiers[modifierString]!! + amount
        if (limit != null) {
            if (limit < 0) newValue = newValue.coerceAtLeast(limit)
            else newValue = newValue.coerceAtMost(limit)
        }
        diplomaticModifiers[modifierString] = newValue
        if (diplomaticModifiers[modifierString] == 0f) diplomaticModifiers.remove(modifierString)
    }

    fun setModifier(modifier: DiplomaticModifiers, amount: Float) {
        diplomaticModifiers[modifier.name] = amount
    }

    /**
     * Allows repeatedly adding to a modifier without worrying that it will reach ridiculous levels.
     * Add 20 to 0 -> 20 (+20) (same as [addModifier]).
     * Add 20 to 20 -> 30 (+10).
     * Add 20 to 60 -> 65 (+5).
     * Works for both positive and negative values.
     */
    fun addDiminishingModifier(modifier: DiplomaticModifiers, amount: Float) {
        addModifier(
            modifier,
            // caution if we are dealing with positive AND negative values
            if (amount == 0f || sign(amount) != sign(getModifier(modifier))) amount 
            else amount * amount / (getModifier(modifier) + amount)
        ) 
    }

    @Readonly
    internal fun getModifier(modifier: DiplomaticModifiers): Float {
        if (!hasModifier(modifier)) return 0f
        return diplomaticModifiers[modifier.name]!!
    }

    internal fun removeModifier(modifier: DiplomaticModifiers) = diplomaticModifiers.remove(modifier.name)
    @Readonly
    fun hasModifier(modifier: DiplomaticModifiers) = diplomaticModifiers.containsKey(modifier.name)
    
    fun replaceModifier(oldModifier: DiplomaticModifiers, newModifier: DiplomaticModifiers, amount: Float) {
        removeModifier(oldModifier)
        addModifier(newModifier, amount)
    }

    /** CvDiplomacyAI::SetDoFAccepted for both: the friendship lasts GameSpeeds.RelationshipDuration turns (its opinion is [DiplomaticModifiers.DeclarationOfFriendship]) */
    fun signDeclarationOfFriendship() {
        val duration = DiplomacyTurn.relationshipDuration(civInfo)
        setFlag(DiplomacyFlags.DeclarationOfFriendship, duration)
        otherCivDiplomacy().setFlag(DiplomacyFlags.DeclarationOfFriendship, duration)
        if (civInfo.isMajorCiv) civInfo.ai.diplomacy.memoryOf(otherCiv).doFCounter = 0
        if (otherCiv.isMajorCiv) otherCiv.ai.diplomacy.memoryOf(civInfo).doFCounter = 0

        for (thirdCiv in getCommonKnownCivsWithSpectators()) {
            thirdCiv.addNotification("[${civInfo.civName}] and [${otherCiv.civName}] have signed a Declaration of Friendship!",
                NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy, otherCiv.civName)
        }

        // Ignore contitionals as triggerUnique will check again, and that would break
        // UniqueType.ConditionalChance - 25% declared chance would work as 6% actual chance
        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponDeclaringFriendship, GameContext.IgnoreConditionals, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }
        otherCiv.forEachTriggeredUnique(UniqueType.TriggerUponDeclaringFriendship, GameContext.IgnoreConditionals, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, otherCiv)
        }
    }

    fun signDefensivePact(duration: Int) {
        setFlag(DiplomacyFlags.DefensivePact, duration)
        otherCivDiplomacy().setFlag(DiplomacyFlags.DefensivePact, duration)
        diplomaticStatus = DiplomaticStatus.DefensivePact
        otherCivDiplomacy().diplomaticStatus = DiplomaticStatus.DefensivePact


        for (thirdCiv in getCommonKnownCivsWithSpectators()) {
            thirdCiv.addNotification("[${civInfo.civName}] and [${otherCiv.civName}] have signed a Defensive Pact!",
                NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy, otherCiv.civName)
        }

        // Ignore contitionals as triggerUnique will check again, and that would break
        // UniqueType.ConditionalChance - 25% declared chance would work as 6% actual chance
        civInfo.forEachTriggeredUnique(UniqueType.TriggerUponSigningDefensivePact, GameContext.IgnoreConditionals, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, civInfo)
        }
        otherCiv.forEachTriggeredUnique(UniqueType.TriggerUponSigningDefensivePact, GameContext.IgnoreConditionals, ignoreCities = false) { unique ->
            UniqueTriggerActivation.triggerUnique(unique, otherCiv)
        }
    }

    /**
     * CvDiplomacyAI::DoDenouncePlayer: the denouncement lasts GameSpeeds.RelationshipDuration turns; both embassies close; a friendship ends
     * and the former friend remembers the betrayal; a friendly war face drops; coop wars against third parties with them are off.
     * The opinions follow from these (see [DiplomaticModifiers.Denunciation] and [DiplomaticModifiers.WeDenouncedThem]).
     */
    fun denounce() {
        setFlag(DiplomacyFlags.Denunciation, DiplomacyTurn.relationshipDuration(civInfo))
        civInfo.diplomacyFunctions.removeEmbassies(otherCiv)
        if (civInfo.isMajorCiv && otherCiv.isMajorCiv) {
            val memory = civInfo.ai.diplomacy.memoryOf(otherCiv)
            val theirMemory = otherCiv.ai.diplomacy.memoryOf(civInfo)
            memory.denouncedCounter = 0
            memory.denouncementActive = true
            if (hasFlag(DiplomacyFlags.DeclarationOfFriendship)) {
                removeFlag(DiplomacyFlags.DeclarationOfFriendship)
                otherCivDiplomacy().removeFlag(DiplomacyFlags.DeclarationOfFriendship)
                memory.doFCounter = -666
                theirMemory.doFCounter = -666
                theirMemory.friendDenouncedUs = true
            }
            if (memory.warFace == WarFace.Friendly) memory.warFace = WarFace.Neutral
            for (entry in memory.coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
                entry.state = null
                entry.counter = -666
            }
            for (entry in theirMemory.coopWars.values) if (entry.state != null && entry.state!! >= CoopWarState.Soon) {
                entry.state = null
                entry.counter = -666
            }
        }

        // the denounced civ will get a popup
        otherCiv.popupAlerts.add(PopupAlert(AlertType.Denounced, civInfo.civID))
        // ...and a notification as a reminder for the rest of the turn
        otherCiv.addNotification("[${civInfo.civName}] has denounced us!", NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civInfo.civName)
        for (thirdCiv in getCommonKnownCivsWithSpectators()) {
            thirdCiv.addNotification("[${civInfo.civName}] has denounced [${otherCiv.civName}]!",
                NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy, otherCiv.civName)
        }
    }

    /**
     * Queues a PopupAlert to be displayed on [otherCiv]'s turn
     * If a popup of the same type is already queued, do nothing.
     */
    private fun queueOtherCivPopupIfUnique(popup: PopupAlert) {
        if (otherCiv.popupAlerts.none { it.type == popup.type && it.value == popup.value })
            otherCiv.popupAlerts.add(popup)
    }

    fun agreeToDemand(demand: Demand){
        otherCivDiplomacy().setFlag(demand.agreedToDemand, if (demand == Demand.DoNotDigInOurLands) 1 else 100, demand != Demand.DoNotDigInOurLands)
        if (demand == Demand.DoNotDigInOurLands) otherCivDiplomacy().negativeArchaeologyPoints =
            (otherCivDiplomacy().negativeArchaeologyPoints - 10).coerceAtLeast(0)
        PromiseAi.onDemandAgreed(otherCiv, civInfo, demand)
        val text = demand.agreedToDemandText.fillPlaceholders(civInfo.civName)
        otherCiv.addNotification(text, NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civInfo.civName)
        queueOtherCivPopupIfUnique(PopupAlert(AlertType.AcceptingDemand, civInfo.civID))
    }

    fun refuseDemand(demand: Demand) {
        otherCivDiplomacy().setFlag(demand.willIgnoreViolation, if (demand == Demand.DoNotDigInOurLands) 1 else 100, demand != Demand.DoNotDigInOurLands)
        PromiseAi.onDemandRefused(otherCiv, civInfo, demand)
        val text = demand.refusedDemandText.fillPlaceholders(civInfo.civName)
        otherCiv.addNotification(text, NotificationCategory.Diplomacy, NotificationIcon.Diplomacy, civInfo.civName)
        queueOtherCivPopupIfUnique(PopupAlert(AlertType.RejectingDemand, civInfo.civID))
        if (demand == Demand.DoNotAttackUs) declareWar()
    }

    /** We sided with our protected city-state against the other civ, who remembers (CvDiplomacyAI::GetAngryAboutSidedWithProtectedMinorScore) */
    fun sideWithCityState() {
        DiplomacyEvents.onSidedWithProtectedMinor(civInfo, otherCiv)
    }

    fun becomeWary() {
        if (hasFlag(DiplomacyFlags.WaryOf)) return // once is enough
        setFlag(DiplomacyFlags.WaryOf, -1) // Never expires
        otherCiv.addNotification("City-States grow wary of your aggression. " +
                "The resting point for Influence has decreased by [20] for [${civInfo.civName}].",
            NotificationCategory.Diplomacy, civInfo.civName)
    }
    //endregion
}
