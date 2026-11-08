package com.unciv.logic.automation.unit.tactical

/** AITacticalTargetType (CvTacticalAnalysisMap.h): what a tactical target or a temporary dominance zone is about */
enum class TacticalTargetType {
    None,
    City,
    BarbarianCamp,
    Improvement,
    BlockadeResourcePoint,
    /** Can't attack one of our cities */
    LowPriorityUnit,
    /** Can damage one of our cities */
    MediumPriorityUnit,
    /** Can contribute to capturing one of our cities */
    HighPriorityUnit,
    CityToDefend,
    ImprovementToDefend,
    DefensiveBastion,
    AncientRuins,
    /** Used for the naval bombardment operation */
    BombardmentZone,
    EmbarkedMilitaryUnit,
    EmbarkedCivilian,
    VeryHighPriorityCivilian,
    HighPriorityCivilian,
    MediumPriorityCivilian,
    LowPriorityCivilian,
    TradeUnitSea,
    TradeUnitLand,
    /** Used for idle unit moves to plunder trade routes that go through our territory */
    TradeUnitSeaPlot,
    TradeUnitLandPlot,
    Citadel,
    ImprovementResource,
}

/** AITacticalPosture (CvTacticalAI.h): what the units around one dominance zone do this turn */
enum class TacticalPosture {
    None,
    Withdraw,
    SitAndBombard,
    AttritFromRange,
    ExploitFlanks,
    Steamroll,
    SurgicalCityStrike,
    Hedgehog,
    Counterattack,
    ShoreBombardment,
}

/** TacticalTerritoryTypes (CvTacticalAnalysisMap.h): whose land a dominance zone is */
enum class TacticalTerritory { None, NoOwner, Friendly, Enemy, Neutral, TempZone }

/** eTacticalDominanceFlags (CvTacticalAnalysisMap.h) */
enum class TacticalDominance { NoUnitsVisible, Friendly, Even, Enemy }
