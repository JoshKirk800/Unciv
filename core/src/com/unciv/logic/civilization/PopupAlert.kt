package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization

enum class AlertType : IsPartOfGameInfoSerialization {
    Defeated,
    WonderBuilt,
    TechResearched,
    WarDeclaration,
    FirstContact,
    CityConquered,
    CityTraded,
    BorderConflict,
    TilesStolen,

    DemandToStopSettlingCitiesNear,
    CitySettledNearOtherCivDespiteOurPromise,

    DemandToStopSpreadingReligion,
    ReligionSpreadDespiteOurPromise,
    
    DemandToStopSpyingOnUs,
    SpyingOnUsDespiteOurPromise,
    DemandToStopDiggingInOurLands,
    DugInOurLandsDespitePromise,
    
    DemandToNotAttackUs,
    AttackedUsDespitePromise,

    DemandToStopBuyingLandNearUs,
    LandBoughtNearUsDespiteOurPromise,
    
    AcceptingDemand,
    RejectingDemand,

    GoldenAge,
    /** A dig is complete and its civilization chooses what to do with the finds, [PopupAlert.value] is the position "x,y" of the tile */
    ArchaeologyChoice,
    /** A Native Tongue explorer chooses its ruin reward; [PopupAlert.value] is the explorer's unit id. */
    RuinsReward,
    DeclarationOfFriendship,
    StartIntro,
    DiplomaticMarriage,
    BulliedProtectedMinor,
    AttackedProtectedMinor,
    AttackedAllyMinor,
    RecapturedCivilian,
    GameHasBeenWon,
    Event,
    
    Denounced,

    /** An AI asks a human to join its war: [PopupAlert.value] is [PopupAlert.pairValue] of the asking civ and the war's target (CvDiplomacyAI COOP_WAR_REQUEST) */
    CoopWarRequest,
    /** An AI asks a human who said "soon" to declare the war now, value as [CoopWarRequest] (COOP_WAR_TIME) */
    CoopWarNow,
    /** An AI friend asks a human to denounce a civ, value as [CoopWarRequest] (REQUEST_FRIEND_DENOUNCE) */
    DenounceRequest,

    /** A session of the World Congress has finished, its outcomes are in `League.lastResults` */
    LeagueSessionResult
}

class PopupAlert : IsPartOfGameInfoSerialization {
    lateinit var type: AlertType
    lateinit var value: String

    constructor(type: AlertType, value: String) {
        this.type = type
        this.value = value
    }

    constructor() // for json serialization

    companion object {
        /** The value of an alert about two civs, `first@second` as the protected city-state alerts have it */
        fun pairValue(first: String, second: String) = "$first@$second"
    }
}
