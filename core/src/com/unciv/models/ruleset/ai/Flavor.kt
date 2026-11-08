package com.unciv.models.ruleset.ai

import yairm210.purity.annotations.Immutable
import yairm210.purity.annotations.Pure

/**
 *  The AI flavors of Civ V: Brave New World, in the order of its `Flavors` table (CIV5Flavors.xml), so [ordinal] is the
 *  index the original uses for its flavor arrays. Flavor vectors in the AI code are `IntArray(Flavor.count)` indexed by [ordinal].
 *
 *  In ruleset JSON (AI.json, Personalities.json) a flavor is written by its Kotlin [name], e.g. `"CityDefense": 7`.
 *  Vanilla and Gods & Kings have fewer flavors (31 and 32): their data simply has no values for the later ones.
 *
 *  @property xmlName the `Type` of the flavor in the original XML
 */
@Suppress("EnumEntryName")  // I_ prefix as in the original (FLAVOR_I_*: trade-route flavors)
enum class Flavor(val xmlName: String) {
    Offense("FLAVOR_OFFENSE"),
    Defense("FLAVOR_DEFENSE"),
    CityDefense("FLAVOR_CITY_DEFENSE"),
    MilitaryTraining("FLAVOR_MILITARY_TRAINING"),
    Recon("FLAVOR_RECON"),
    Ranged("FLAVOR_RANGED"),
    Mobile("FLAVOR_MOBILE"),
    Naval("FLAVOR_NAVAL"),
    NavalRecon("FLAVOR_NAVAL_RECON"),
    NavalGrowth("FLAVOR_NAVAL_GROWTH"),
    NavalTileImprovement("FLAVOR_NAVAL_TILE_IMPROVEMENT"),
    Air("FLAVOR_AIR"),
    Expansion("FLAVOR_EXPANSION"),
    Growth("FLAVOR_GROWTH"),
    TileImprovement("FLAVOR_TILE_IMPROVEMENT"),
    Infrastructure("FLAVOR_INFRASTRUCTURE"),
    Production("FLAVOR_PRODUCTION"),
    Gold("FLAVOR_GOLD"),
    Science("FLAVOR_SCIENCE"),
    Culture("FLAVOR_CULTURE"),
    Happiness("FLAVOR_HAPPINESS"),
    GreatPeople("FLAVOR_GREAT_PEOPLE"),
    Wonder("FLAVOR_WONDER"),
    Religion("FLAVOR_RELIGION"),
    Diplomacy("FLAVOR_DIPLOMACY"),
    Spaceship("FLAVOR_SPACESHIP"),
    WaterConnection("FLAVOR_WATER_CONNECTION"),
    Nuke("FLAVOR_NUKE"),
    Antiair("FLAVOR_ANTIAIR"),
    UseNuke("FLAVOR_USE_NUKE"),
    Espionage("FLAVOR_ESPIONAGE"),
    AirCarrier("FLAVOR_AIR_CARRIER"),
    I_LandTradeRoute("FLAVOR_I_LAND_TRADE_ROUTE"),
    I_SeaTradeRoute("FLAVOR_I_SEA_TRADE_ROUTE"),
    Archaeology("FLAVOR_ARCHAEOLOGY"),
    I_TradeOrigin("FLAVOR_I_TRADE_ORIGIN"),
    I_TradeDestination("FLAVOR_I_TRADE_DESTINATION"),
    Airlift("FLAVOR_AIRLIFT"),
    ;

    companion object {
        /** Number of flavors = size of every flavor vector */
        val count = entries.size

        @Immutable private val byName = entries.associateBy { it.name }

        @Pure fun fromName(name: String): Flavor? = byName[name]

        /** A flavor map as written in the JSON (name -> value) as a vector; names that are no [Flavor] are ignored (the validator reports them),
         *  flavors missing from the map get [missingValue]. */
        fun vectorOf(map: Map<String, Int>, missingValue: Int = 0): IntArray {
            val vector = IntArray(count) { missingValue }
            for ((name, value) in map) {
                val flavor = byName[name] ?: continue
                vector[flavor.ordinal] = value
            }
            return vector
        }
    }
}

/** The personality values of a leader's diplomacy AI: the personality columns of the original `Leaders` table, in that order.
 *  JSON key = Kotlin [name] (same as the XML column). */
enum class DiplomacyPersonality {
    VictoryCompetitiveness,
    WonderCompetitiveness,
    MinorCivCompetitiveness,
    Boldness,
    DiploBalance,
    WarmongerHate,
    DenounceWillingness,
    DoFWillingness,
    Loyalty,
    Neediness,
    Forgiveness,
    Chattiness,
    Meanness,
}

/** The original's MajorCivApproachTypes in XML order (MAJOR_CIV_APPROACH_WAR ...), used for the leaders' approach biases */
enum class MajorCivApproach { War, Hostile, Deceptive, Guarded, Afraid, Friendly, Neutral }

/** The original's MinorCivApproachTypes in XML order (MINOR_CIV_APPROACH_IGNORE ...), used for the leaders' approach biases */
enum class MinorCivApproach { Ignore, Friendly, Protective, Conquest, Bully }
