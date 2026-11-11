# Miscellaneous JSON files

## Difficulties.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Difficulties.json)

This optional file defines the difficulty levels a player can choose when starting a new game.

Base ruleset Mods can omit the file or supply an empty list, even though at least one difficulty is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/Difficulties.json).

Each difficulty level has the following structure:

| Attribute                         | Type            | Default  | Notes                                                                                                                                                                                                                      |
|-----------------------------------|-----------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| name                              | String          | Required |                                                                                                                                                                                                                            |
| baseHappiness                     | Integer         | 0        |                                                                                                                                                                                                                            |
| extraHappinessPerLuxury           | Float           | 0        |                                                                                                                                                                                                                            |
| researchCostModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| unitCostModifier                  | Float           | 1        | Applies to the gold maintenance of the units of a human player (the original's UnitCostPercent), not to production |
| unitSupplyBase                    | Integer         | 5        |                                                                                                                                                                                                                            |
| unitSupplyPerCity                 | Integer         | 2        |                                                                                                                                                                                                                            |
| buildingCostModifier              | Float           | 1        | Applies to the gold maintenance of the buildings of a human player (the original's BuildingCostPercent), not to production |
| policyCostModifier                | Float           | 1        |                                                                                                                                                                                                                            |
| unhappinessModifier               | Float           | 1        |                                                                                                                                                                                                                            |
| barbarianBonus                    | Float           | 0        |                                                                                                                                                                                                                            |
| barbarianSpawnDelay               | Integer         | 0        |                                                                                                                                                                                                                            |
| playerBonusStartingUnits          | List of Strings | empty    | Can also be 'Era Starting Unit', maps to `startingMilitaryUnit` of the Eras file. All other units must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson). Applies only to human player civs                     |
| isSelectable                      | Boolean         | true     | `false` hides the level from the difficulty choice of a new game: for a level that only serves as the handicap of the AI civilizations, see aiDifficultyLevel |
| aiDifficultyLevel                 | String          | empty    | Applies some modifiers to the AI from the chosen difficulty: the AI civilizations get the player settings (happiness, research and policy cost, unhappiness, unit supply) of this level. Modifiers such as unhappinessModifier and aiUnhappinessModifier stack multiplicatively. If empty: defaults to "Chieftain", or the first available difficulty. |
| cityStateDifficultyLevel          | String          | empty    | The level whose player settings city-state civilizations have. If empty: aiDifficultyLevel                                                                                                                                 |
| barbarianDifficultyLevel          | String          | empty    | The level whose player settings the barbarians have. If empty: aiDifficultyLevel                                                                                                                                           |
| aiCityGrowthModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitCostModifier                | Float           | 1        |                                                                                                                                                                                                                            |
| aiBuildingCostModifier            | Float           | 1        |                                                                                                                                                                                                                            |
| aiWonderCostModifier              | Float           | 1        |                                                                                                                                                                                                                            |
| aiBuildingMaintenanceModifier     | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitMaintenanceModifier         | Float           | 1        |                                                                                                                                                                                                                            |
| aiUnitSupplyModifier              | Integer         | 5        |                                                                                                                                                                                                                            |
| aiFreeTechs                       | List of Strings | empty    | Must be in [Techs.json](2-Civilization-related-JSON-files.md#techsjson)                                                                                                                                                    |
| aiMajorCivBonusStartingUnits      | List of Strings | empty    | Same rules as playerBonusStartingUnits, See above. Applies only to AI major civs                                                                                                                                           |
| aiCityStateBonusStartingUnits     | List of Strings | empty    | Same rules as playerBonusStartingUnits, See above. Applies only to city-state civs                                                                                                                                         |
| aiUnhappinessModifier             | Float           | 1        |                                                                                                                                                                                                                            |
| aiPerEraModifier                  | Float           | 0        | Change of an AI's production costs, city growth thresholds and unit upgrade costs per era it is in. -0.05: 5% cheaper per era (the original's AIPerEraModifier) |
| aiUnitUpgradeCostModifier         | Float           | 1        | Multiplier for the gold cost of unit upgrades of an AI |
| aiFreeUnitExperience              | Integer         | 0        | Experience that new military units of an AI start with |
| aiFreeUnitExperiencePercent       | Float           | 0        | Additional experience gain in combat for the units of an AI. 1: 100% more |
| aiWorkRateModifier                | Float           | 0        | Additional speed of the workers of an AI. 1: twice as fast |
| routeCostModifier                 | Float           | 1        | Multiplier for the gold maintenance of roads and railroads of the players with this level (the original's RouteCostPercent) |
| turnBarbariansCanEnterPlayerTiles | Integer         | 0        |                                                                                                                                                                                                                            |
| clearBarbarianCampReward          | Integer         | 25       |                                                                                                                                                                                                                            |
| uniques                           | List of Strings | empty    | Will be treated as part of GlobalUniques when this difficulty is chosen                                                                                                                                                    |

## Eras.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Eras.json)

This file should contain all the era's you want to use in your mod.

Each era can have the following attributes:

| Attribute                 | Type                                   | Default         | Notes                                                                                                                                                                                                                                           |
|---------------------------|----------------------------------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| name                      | String                                 | Required        |                                                                                                                                                                                                                                                 |
| researchAgreementCost     | Integer (≥0)                           | 300             | Cost of research agreements when the most technologically advanced civ is in this era                                                                                                                                                           |
| iconRGB                   | [List of 3× Integer](#rgb-colors-list) | white           | RGB color that icons for technologies of this era should have in the Tech screen                                                                                                                                                                |
| startingSettlerCount      | Integer (≥0)                           | 1               | Amount of settler units that should be spawned when starting a game in this era (setting this to zero is discouraged [^1])                                                                                                                      |
| startingSettlerUnit       | String                                 | "Settler"       | Name of the unit that should be used for the previous field. Must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson), or a unit with the "Founds a new city" unique must exist                                                             |
| startingWorkerCount       | Integer (≥0)                           | 0               | Amount of worker units that should be spawned when starting a game in this era                                                                                                                                                                  |
| startingWorkerUnit        | String                                 | "Worker"        | Name of the unit that should be used for the previous field. If startingWorkerCount>0, then it must exist in [Units.json](4-Unit-related-JSON-files.md#unitsjson), or a unit with the "Can build [filter] improvements on tiles" unique must exist |
| startingMilitaryUnitCount | Integer (≥0)                           | 1               | Amount of military units that should be spawned when starting a game in this era                                                                                                                                                                |
| startingMilitaryUnit      | String                                 | "Warrior"       | Name of the unit that should be used for the previous field. Must be in [Units.json](4-Unit-related-JSON-files.md#unitsjson)                                                                                                                       |
| startingGold              | Integer (≥0)                           | 0               | Amount of gold each civ should receive when starting a game in this era                                                                                                                                                                         |
| startingCulture           | Integer (≥0)                           | 0               | Amount of culture each civ should receive when starting a game in this era                                                                                                                                                                      |
| settlerPopulation         | Integer (>0)                           | 1               | Amount of population each city should have when settled when starting a game in this era                                                                                                                                                        |
| settlerBuildings          | List of Strings                        | empty           | Buildings that should automatically be built whenever a city is settled when starting a game in this era                                                                                                                                        |
| startingObsoleteWonders   | List of Strings                        | empty           | Wonders (and technically buildings) that should be impossible to built when starting a game in this era. Used in the base game to remove all wonders older than 2 era's                                                                         |
| baseUnitBuyCost           | Integer                                | 200             | Default value used for the unique `Can be purchased with [stat] [cityFilter]`                                                                                                                                                                   |
| embarkDefense             | Integer                                | 3               | Default defense for embarked unit in this era                                                                                                                                                                                                   |
| startPercent              | Integer                                | 0               | When starting, percentage (\[0\]%-\[100\]%) of turns skipped in total turns specified in [Speed.json](#speedsjson)                                                                                                                              |
| researchPercent           | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Tech cost |
| trainPercent              | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of units |
| constructPercent          | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of buildings and wonders |
| createPercent             | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Production cost of spaceship parts (the original game's projects) |
| greatPeoplePercent        | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Points needed for a Great Person, Great General or Great Admiral |
| growthPercent             | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Food needed for a city to grow |
| buildPercent              | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Time workers need to build improvements |
| buildingMaintenancePercent | Integer (≥0)                           | 100             | Percentage applied for the whole game when starting in this era: Gold maintenance of buildings |
| citySound                 | String                                 | "cityClassical" | Sound used when city is founded in this era                                                                                                                                                                                                     |
| uniques                           | List of Strings | empty    | Will be treated as part of GlobalUniques when starting in this era                                                                                                                                                    |

[^1]: Successfully setting startingSettlerCount to zero in a mod (idea: conquer or die) is not easy. Some player-controlled settings require at least one Settler, through any source (see difficulties for other possible settler sources), or you won't be able to start a game: Once City Challenge requires one for all players, and allowing any city-states requires one for those. Would also affect defeat rules.

## Speeds.json

[Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/Speeds.json)

This optional file should contain all the speeds you want to use in your mod.

Base ruleset Mods can omit the file or supply an empty list, even though at least one game speed is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/Speeds.json).

Each speed can have the following attributes:

| Attribute                       | Type            | Default          | Notes                                                                                                       |
|---------------------------------|-----------------|------------------|-------------------------------------------------------------------------------------------------------------|
| name                            | String          | Required         | Name of the speed                                                                                           |
| modifier                        | Float (≥0)      | 1.0              | Overall game speed modifier                                                                                 |
| productionCostModifier          | Float (≥0)      | `modifier` value | Scales production cost of units and buildings                                                               |
| goldCostModifier                | Float (≥0)      | `modifier` value | Scales gold costs                                                                                           |
| scienceCostModifier             | Float (≥0)      | `modifier` value | Scales science costs                                                                                        |
| cultureCostModifier             | Float (≥0)      | `modifier` value | Scales culture costs                                                                                        |
| faithCostModifier               | Float (≥0)      | `modifier` value | Scales faith costs                                                                                          |
| improvementBuildLengthModifier  | Float (≥0)      | `modifier` value | Scales the time it takes for a worker to build tile improvements                                            |
| barbarianModifier               | Float (≥0)      | `modifier` value | Scales the time between barbarian spawns                                                                    |
| goldGiftModifier                | Float (≥0)      | `modifier` value | Scales the influence gained from gifting gold to city-states                                                |
| cityStateTributeScalingInterval | Float (≥0)      | 6.5              | The number of turns it takes for the amount of gold a player demands from city-states to increase by 5 gold |
| goldenAgeLengthModifier         | Float (≥0)      | `modifier` value | Scales the length of golden ages                                                                            |
| religiousPressureAdjacentCity   | Integer (≥0)    | 6                | Defines how much religious pressure a city exerts on nearby cities                                          |
| peaceDealDuration               | Integer (≥0)    | 10               | The number of turns a peace deal lasts                                                                      |
| dealDuration                    | Integer (≥0)    | 30               | The number of turns a non-peace deal (research agreement, open borders, etc.) lasts                         |
| startYear                       | Float           | -4000            | The start year of the game (negative is BC/BCE)                                                             |
| turns                           | List            | Required         | List of time interval per turn, [see below](#time-interval-per-turn)                                        |
| uniques                         | List of Strings | empty            | Will be treated as part of GlobalUniques when this speed is chosen                                          |


### Time interval per turn

The "turns" attribute defines the number of years passing between turns. The attribute consists of a list of objects, each having 2 required attributes: "yearsPerTurn" (Float) and "untilTurn" (Integer)

| Attribute    | Type    | Default  | Notes                                                      |
|--------------|---------|----------|------------------------------------------------------------|
| yearsPerTurn | Integer | Required | Number of years passing between turns                      |
| untilTurn    | Integer | Required | End of this interval (if it is the last object, see below) |

For each row, "yearsPerTurn" is applied up to the "untilTurn"-1 to "untilTurn" step.
The last "untilTurn" in the list is ignored for year calculation, that is, if a game passes that turn number, years continue to increment by the "yearsPerTurn" of the last entry.
However, this is used when starting a game in a later Era: Era.startPercent is relative to the last "untilTurn".

The code below is an example of a valid "turns" definition and it specifies that the first 50 turns of a game last for 60 years each, then the next 30 turns (and any played after the 80th) last for 40 years each.

```json
"turns": [
    {"yearsPerTurn": 60, "untilTurn":  50},
    {"yearsPerTurn": 40, "untilTurn":  80}
]
```

## Events.json

Events allow users to choose between options of triggers to activate.

| Attribute       | Type                                | Default  | Notes                                                                                                                    |
|-----------------|-------------------------------------|----------|--------------------------------------------------------------------------------------------------------------------------|
| name            | String                              | Required | Used for triggering via "Triggers a [event] event" unique                                                                |
| text            | String                              | None     | Flavor text displayed to user                                                                                            |
| presentation    | One of: "None", "Alert", "Floating" | Alert    | "Alert" indicates a regular popup, "None" means the choice is made randomly, "Floating" is for tutorial-style indicators |
| civilopediaText | List                                | Optional | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)                                            |
| choices         | List of EventChoices                |          | User can choose to trigger one of the viable choices                                                                     |


You can use text and/or civilopediaText, if both are present both are shown (but why would you?)

Add the "Only the first available choice is offered" unique to an Event to restrict it to just the first viable choice (in list order), like a `when`/`case` statement.

Event choices are comprised of:

| Attribute       | Type                                           | Default    | Notes                                                                                                               |
|-----------------|------------------------------------------------|------------|---------------------------------------------------------------------------------------------------------------------|
| text            | String                                         | Required   | Displayed to user as button. Should be an action name - "Do X"                                                      |
| uniques         | List of uniques to trigger or limit conditions | Required   | The triggers that this choice activates upon being chosen, and "Unavailable" / "Only available" uniques             |
| keyShortcut     | key to select (name)                           | none       | Key names see [Gdx.Input.Keys](https://github.com/libgdx/libgdx/blob/master/gdx/src/com/badlogic/gdx/Input.java#L69) |
| civilopediaText | List                                           | Optional   | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)                                          |

Here, civilopediaText is shown outside the active Button, before the triggeredUniques.

## ModOptions.json

<!-- [Link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/ModOptions.json) -->

This file is a little different:

- Does not exist in Vanilla ruleset
- Is entirely optional but will be created after downloading a mod

Note that this file controls _declarative mod compatibility_ (Work in progress) - e.g. there's [uniques](../uniques.md#modoptions-uniques) to say your Mod should only or never be used as 'Permanent audiovisual mod'.
Incompatibility filtering works so far between extension and base mods, but feel free to document known extension-to-extension incompatibilities using the same Unique now. Stay tuned!

The file can have the following attributes, not including the values Unciv sets automatically:

| Attribute              | Type    | default | Notes                                                                                                                                                                               |
|------------------------|---------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| isBaseRuleset          | Boolean | false   | Replaces vanilla ruleset if true                                                                                                                                                    |
| uniques                | List    | empty   | Mod-wide specials, [see here](../uniques.md#modoptions-uniques)                                                                                                                     |
| beliefsToRemove        | List    | empty   | List of [Beliefs](2-Civilization-related-JSON-files.md#beliefsjson) or beliefFilter (matches name, type, "All" or a filtering Unique) to remove (isBaseRuleset=false only)          |
| buildingsToRemove      | List    | empty   | List of [Buildings or Wonders](2-Civilization-related-JSON-files.md#buildingsjson) or [buildingFilter](../Unique-parameters.md#buildingfilter) to remove (isBaseRuleset=false only) |
| cityStateTypesToRemove | List    | empty   | List of Citystate Types or "All" to remove (isBaseRuleset=false only).                                                                                                              |
| difficultiesToRemove   | List    | empty   | List of Difficulties or "All" to remove (isBaseRuleset=false only).                                                                                                                 |
| eventsToRemove         | List    | empty   | List of List of Events or "All" to remove (isBaseRuleset=false only).                                                                                                               |
| greatWorksToRemove     | List    | empty   | List of [Great Works](2-Civilization-related-JSON-files.md#greatworksjson) or "All" to remove (isBaseRuleset=false only).                                                           |
| improvementsToRemove   | List    | empty   | List of List of Tile Improvements or [improvementFilter](../Unique-parameters.md#improvementFilter) to remove (isBaseRuleset=false only).                                           |
| nationsToRemove        | List    | empty   | List of [Nations](2-Civilization-related-JSON-files.md#nationsjson) or [nationFilter](../Unique-parameters.md#nationfilter) to remove (isBaseRuleset=false only)                    |
| personalitiesToRemove  | List    | empty   | List of Leader Personalities or "All" to remove (isBaseRuleset=false only).                                                                                                         |
| policyBranchesToRemove | List    | empty   | List of [Policy Branches](2-Civilization-related-JSON-files.md#policiesjson) to remove (isBaseRuleset=false only)                                                                   |
| policiesToRemove       | List    | empty   | List of [Policies](2-Civilization-related-JSON-files.md#policiesjson) to remove (isBaseRuleset=false only)                                                                          |
| questsToRemove         | List    | empty   | List of Citystate Quest names or "All" to remove (isBaseRuleset=false only).                                                                                                        |
| resolutionsToRemove    | List    | empty   | List of [Resolutions](2-Civilization-related-JSON-files.md#resolutionsjson) or "All" to remove (isBaseRuleset=false only).                                                          |
| religionsToRemove      | List    | empty   | List of [Religions](2-Civilization-related-JSON-files.md#religionsjson) or "All" to remove (isBaseRuleset=false only)                                                               |
| resourcesToRemove      | List    | empty   | List of Tile Resources or [resourceFilter](../Unique-parameters.md#resourceFilter) to remove (isBaseRuleset=false only).                                                            |
| ruinRewardsToRemove    | List    | empty   | List of Ruin Rewards or "All" to remove (isBaseRuleset=false only).                                                                                                                 |
| specialistsToRemove    | List    | empty   | List of Specialists or "All" to remove (isBaseRuleset=false only).                                                                                                                  |
| speedsToRemove         | List    | empty   | List of Game Speeds or "All" to remove (isBaseRuleset=false only).                                                                                                                  |
| techsToRemove          | List    | empty   | List of [Technologies](2-Civilization-related-JSON-files.md#techsjson) or [technologyFilter](../Unique-parameters.md#technologyfilter) to remove (isBaseRuleset=false only)         |
| terrainsToRemove       | List    | empty   | List of Terrains or [terrainFilter](../Unique-parameters.md#terrainFilter) to remove (isBaseRuleset=false only).                                                                    |
| tutorialsToRemove      | List    | empty   | List of Tutorials or "All" to remove (isBaseRuleset=false only).                                                                                                                    |
| unitsToRemove          | List    | empty   | List of [Units](4-Unit-related-JSON-files.md#unitsjson) or [unitFilter](../Unique-parameters.md#baseunitfilter) to remove (isBaseRuleset=false only)                                |
| unitNameGroupsToRemove | List    | empty   | List of Unit-name Groups or "All" to remove (isBaseRuleset=false only).                                                                                                             |
| unitPromotionsToRemove | List    | empty   | List of Unit Promotions or "All" to remove (isBaseRuleset=false only).                                                                                                              |
| unitTypesToRemove      | List    | empty   | List of Unit Types or [unitTypeFilter](../Unique-parameters.md#unitTypeFilter) to remove (isBaseRuleset=false only). Note dependent units are not automatically removed as well.    |
| victoriesToRemove      | List    | empty   | List of Victories or "All" to remove (isBaseRuleset=false only).                                                                                                                    |
| constants              | Object  | empty   | See [ModConstants](#modconstants)                                                                                                                                                   |
| tileset                | String  | empty   | Only applicable for base rulesets                                                                                                                                                   |
| unitset                | String  | empty   | Only applicable for base rulesets                                                                                                                                                   |

Note that removal happens before adding new content from your mod, so an "All" filter won't kill your content too.

Policies with their branches and Technologies have more complex removal rules.

- Removing Policy Branches removes their member Policies as well.
- Overriding a Policy Branch will remove existing member Policies.
- Removing all techs from a TechColumn will also remove that column.
- Adding techs will merge columns by their `columnNumber`.
- Adding tech columns outside the existing ones is allowed: Replacing a column that was removed, or placing a column before (using negative `columnNumber`) or after the existing ones.
- After tech removal and merge is done, TechColumns are resorted and renumbered so `columnNumber` is monotonous, contiguous ands starts at 0.

Removing religions will clean those from the preferred religions of nations.

Otherwise, no interdependencies are handled automatically, and your mod may easily fail validation unless you're careful.

The values normally set automatically from github metadata are:

| Attribute     | Type    |        | Notes                                                                                 |
|---------------|---------|--------|---------------------------------------------------------------------------------------|
| modUrl        | String  |        | The github page the mod was downloaded from, or empty if a freely hosted zip was used |
| defaultBranch | String  | master | The repo's default branch                                                             |
| author        | String  |        | Repo owner                                                                            |
| lastUpdated   | String  |        | ISO date                                                                              |
| modSize       | Integer | 0      | Size in kB                                                                            |
| topics        | List    | empty  | A list of "unciv-mod-*" github topics                                                 |

To clarify: When your Mod is distributed via github, including these in the Mod repo has no effect.
However, when a Mod is distributed _without_ a github repository, these values can and _should_ be set by the author in the distributed `ModOptions.json`.

### ModConstants

Stored in ModOptions.constants, this is a collection of constants used internally in Unciv.
This is the only structure that is _merged_ field by field from mods, not overwritten, so you can change XP from Barbarians in one mod
and city distance in another. In case of conflicts, there is no guarantee which mod wins, only that _default_ values are ignored.

| Attribute                                | Type   | Default                       | Notes |
|------------------------------------------|--------|-------------------------------|-------|
| maxXPfromBarbarians                      | Int    | 30                            | [^A]  |
| cityStrengthBase                         | Float  | 8.0                           | [^B]  |
| cityStrengthPerPop                       | Float  | 0.4                           | [^B]  |
| cityStrengthFromTechsMultiplier          | Float  | 5.5                           | [^B]  |
| cityStrengthFromTechsExponent            | Float  | 2.8                           | [^B]  |
| cityStrengthFromTechsFullMultiplier      | Float  | 1.0                           | [^B]  |
| cityStrengthFromGarrison                 | Float  | 0.2                           | [^B]  |
| baseCityBombardRange                     | Int    | 2                             | [^X]  |
| cityWorkRange                            | Int    | 3                             | [^Y]  |
| cityExpandRange                          | Int    | 5                             | [^Z]  |
| cityAirUnitCapacity                      | Int    | 6                             | [^W]  |
| cultureCostLikeCivV                      | Boolean | false                        | [^V]  |
| startNormalizerLikeCivV                  | Boolean | false                        | [^G]  |
| coastExpansionDice                       | List<Int> | empty                      | [^T]  |
| seaDepositsOfLandResource                | String  | empty                        | [^SeaOil] |
| terrainGeneratorLikeCivV                 | Boolean | false                        | [^CivVTerrain] |
| luxuryPlacementLikeCivV                  | Boolean | false                        | [^CivVLux] |
| startAssignmentLikeCivV                 | Boolean | false                        | [^CivVAssign] |
| resourceExtrasLikeCivV                  | Boolean | false                        | [^CivVExtras] |
| naturalWondersLikeCivV                  | Boolean | false                        | [^CivVWonders] |
| ancientRuinsLikeCivV                    | Boolean | false                        | [^CivVRuins] |
| tilesPerAncientRuinLikeCivV              | Int     | 40                           | Land tiles per ancient ruin when `ancientRuinsLikeCivV` is set |
| unitSupplyPerPopulation                  | Float  | 0.5                           | [^C]  |
| unitMaintenanceFreeUnits                 | Int    | 3                             | [^U]  |
| unitMaintenanceGameMultiplier            | Float  | 1.0                           | [^U]  |
| unitMaintenanceExponentDivisor           | Float  | 3.0                           | [^U]  |
| minimalCityDistance                      | Int    | 3                             | [^D]  |
| minimalCityDistanceOnDifferentContinents | Int    | 2                             | [^D]  |
| unitUpgradeCost                          | Object | [See below](#unitupgradecost) | [^J]  |
| naturalWonderCountMultiplier             | Float  | 0.124                         | [^E]  |
| naturalWonderCountAddedConstant          | Float  | 0.1                           | [^E]  |
| ancientRuinCountMultiplier               | Float  | 0.025                         | [^F]  |
| maxLakeSize                              | Int    | 10                            | [^H]  |
| riverCountMultiplier                     | Float  | 0.01                          | [^I]  |
| minRiverLength                           | Int    | 5                             | [^I]  |
| maxRiverLength                           | Int    | 666                           | [^I]  |
| religionLimitBase                        | Int    | 1                             | [^K]  |
| religionLimitMultiplier                  | Float  | 0.5                           | [^K]  |
| pantheonBase                             | Int    | 10                            | [^L]  |
| pantheonGrowth                           | Int    | 5                             | [^L]  |
| workboatAutomationSearchMaxTiles         | Int    | 37                            | [^M]  |
| maxSpyRank                               | Int    | 3                             | [^N]  |
| spyRankSkillPercentBonus                 | Float  | 30                            | [^O]  |
| minimumWarDuration                       | Int    | 10                            | [^P]  |
| baseTurnsUntilRevolt                     | Int    | 4                             | [^Q]  |
| cityStateElectionTurns                   | Int    | 15                            | [^R]  |
| maxImprovementTechErasForward            | Int    | None                          | [^S]  |
| scoreFromGreatWorks                      | Int    | 0                             | Score for each Great Work. Brave New World uses 4 |
| greatWorkBaseCulture                     | Int    | 2                             | Culture each Great Work gives its city, before bonuses (see the unique `[stats] from each Great Work`) |
| greatWorkBaseTourism                     | Int    | 2                             | Tourism each Great Work gives its city, before bonuses |
| tourismModifierSharedReligion            | Int    | 25                            | Percent more tourism towards civilizations that follow the same religion. See the unique `[relativeAmount]% Tourism towards civilizations with [tourismRelation]` |
| tourismModifierOpenBorders               | Int    | 25                            | Percent more tourism towards civilizations that have opened their borders to us |
| tourismModifierTradeRoute                | Int    | 25                            | Percent more tourism towards civilizations that we have a trade route with |
| concertTourMinimumTourism                | Int    | 100                           | The least a concert tour is worth, before the game speed |
| influencePercentExotic                   | Int    | 10                            | Tourism towards a civilization, added up over the game, makes us Exotic to it at this percent of the culture it has made in the whole game |
| influencePercentFamiliar                 | Int    | 30                            | As above, for Familiar |
| influencePercentPopular                  | Int    | 60                            | As above, for Popular |
| influencePercentInfluential              | Int    | 100                           | As above, for Influential |
| influencePercentDominant                 | Int    | 200                           | As above, for Dominant |
| archaeologySitesPerCivMin                | Int    | 5                             | Archaeological sites that appear for each major civilization, at least. See the unique `Archaeological sites appear around the world` |
| archaeologySitesPerCivMax                | Int    | 8                             | ... and at most |
| archaeologyPercentHidden                 | Int    | 30                            | Percent of the sites that are hidden until a policy is adopted (the resource `archaeologyHiddenSiteResource`) |
| archaeologyPercentHiddenWriting          | Int    | 30                            | Percent of the hidden sites that hold a writing |
| archaeologySiteResource                  | String | Antiquity site                | The resource of a site |
| archaeologyHiddenSiteResource            | String | Hidden Antiquity site         | The resource of a hidden site |
| archaeologicalDigImprovement            | String | Archaeological Dig            | The improvement of a dig, see the unique `Excavate what was found here` |
| landmarkImprovement                      | String | Landmark                      | The improvement that can be chosen after a dig |
| archaeologySiteTech                      | String | Archaeology                   | The technology whose civilizations are told when the sites appear |
| landmarkInfluenceWithCityStates          | Int    | 50                            | Influence with a city-state when a Landmark is built on its land |
| ideologyTenetsLostOnSwitch               | Int    | 2                             | Tenets that are lost for good when a civilization changes its ideology (see the unique `Is an ideology`); the others become free tenets |
| ideologyAnarchyTurns                     | Int    | 2                             | Turns without gold, science, culture, faith and production after a change of ideology |
| tourismModifierDifferentIdeologies       | Int    | -34                           | Percent change of the tourism towards a civilization that follows another ideology (`Different Ideologies` of the tourism relations) |
| tradeRouteBaseGold                       | Float  | 1                             | Gold a trade route is worth before additions, for both ends. See the unique `Can establish trade routes` |
| tradeRouteCityGoldDivisor                | Int    | 20                            | Each end of an international route adds the gold of the city at the other end divided by this |
| tradeRouteGoldPerDifferentResource       | Float  | 0.5                           | Gold per resource of the other civilization that the route brings that the origin does not have |
| tradeRouteSeaBonusPercent                | Int    | 100                           | Percent more gold on routes by water |
| tradeRouteRiverBonusPercent              | Int    | 25                            | Percent more gold on routes over land between cities on a river |
| tradeRouteTargetTurns                    | Int    | 30                            | A route lasts the whole number of round trips that comes closest to this many turns, but at least two |
| tradeRoutePlunderGold                    | Int    | 100                           | Gold for plundering a route, more for one by water |
| tradeRouteDomesticBaseYield              | Float  | 3                             | Food or production of a route to a city of the same civilization |
| tradeRouteDomesticYieldPerEra            | Float  | 0.5                           | Added to the yield of a domestic route for every era of the destination civilization |
| leagueProposersPerSession                | Int    | 2                             | Civilizations that propose resolutions in a session of the World Congress |
| leagueProposalsPerProposer               | Int    | 1                             | Resolutions each proposer proposes |
| leagueSessionSoonWarningTurns            | Int    | 5                             | Turns ahead of a session that the player is warned |
| leagueFallbackSessionInterval            | Int    | 12                            | Turns between sessions at Standard speed while no era has the unique `World Congress sessions: ...` |
| leagueFallbackDelegates                  | Int    | 1                             | Delegates of each civilization while no era has that unique |
| leagueLeadersVoteBonusCount              | Int    | 2                             | Civilizations leading the vote that get the delegates of `leadersVoteBonusOnFail` when the World Leader vote fails |
| leagueWorldLeaderCivCoefficient          | Float  | 1.443                         | Votes needed for the World Leader: the sum of `max(0, a * ln(civs) + b)` for civilizations (a, b = this and the next) and `max(0, c * ln(city-states) + d)` for city-states |
| leagueWorldLeaderCivConstant             | Float  | 7                             | See above (b) |
| leagueWorldLeaderCityStateCoefficient    | Float  | 16.023                        | See above (c) |
| leagueWorldLeaderCityStateConstant       | Float  | -13.758                       | See above (d) |
| leagueRegularHostProposals               | Int    | 1                             | Proposals the host makes in a regular session |
| leagueProjectTier1Threshold              | Float  | 0.5                           | Contribution tier 1 of a World Congress project begins at this multiple of a civilization's share of the project cost |
| leagueProjectTier2Threshold              | Float  | 1                             | The same for tier 2. Tier 3 goes to the highest contributor, who must have at least this much |
| leagueProjectProgressWarningPercent      | Int    | 33                            | Percent of its cost at which the civilizations are told how far a project is |

Legend:

- [^A]: Max amount of experience that can be gained from combat with barbarians
- [^B]: Formula for city Strength:
    Strength = baseStrength + strengthPerPop + strengthFromTiles +
    ((%techs \* multiplier) ^ exponent) \* fullMultiplier +
    (garrisonBonus \* garrisonUnitStrength \* garrisonUnitHealth/100) +
    defensiveBuildingStrength
    where %techs is the percentage of techs in the tech tree that are complete
    If no techs exist in this ruleset, %techs = 0.5 (=50%)
- [^X]: The distance that cities can attack. Minimum 0.
- [^Y]: The tiles in distance that population in cities can work on. Note: Higher values may lead to performace issues and may cause bugs. cityWorkRange may be greater than cityExpandRange. Minimum 1, maximum 100.
- [^Z]: The distance that cities can expand their borders to. Note: Higher values may lead to performace issues and may cause bugs. Minimum 1, maximum 100.
- [^C]: Formula for Unit Supply:
    Supply = unitSupplyBase (difficulties.json)
    unitSupplyPerCity \* amountOfCities + (difficulties.json)
    unitSupplyPerPopulation \* amountOfPopulationInAllCities
    unitSupplyBase and unitSupplyPerCity can be found in difficulties.json
    unitSupplyBase, unitSupplyPerCity and unitSupplyPerPopulation can also be increased through uniques
- [^U]: Formula for the gold maintenance of units (0.5 gold per unit that is not free):
    cost = (paidUnits \* 0.5 \* (1 + unitMaintenanceGameMultiplier \* progress)) ^ (1 + progress / unitMaintenanceExponentDivisor)
    progress is the turn divided by the number of turns of the game speed, paidUnits is the number of units minus unitMaintenanceFreeUnits and the units made free by uniques.
    The Civ V rulesets use 0, 8 and 7.
- [^V]: The culture a city needs for its next tile. false: 6 \* (tilesClaimed + 1.4813) ^ 1.3, the percentages of "Culture cost of natural border growth" multiplied.
    true: the formula of Civ V, 15 + (10 \* tilesGainedByCulture) ^ 1.1, times 1.5 for City-States, the percentages added up (at least -85), times the game speed, rounded down to a multiple of 5.
    The tiles gained by culture do not count tiles that were bought. The Civ V rulesets set true.
- [^G]: Whether the start of a civilization or City-State is improved with the rules of Civ V (how many bonus resources, when a hill or a horse/iron is added, stone on grass starts).
    The rules name the terrains and resources of the Civ V rulesets (Grassland, Plains, Hill, Wheat, Cattle, ...), so it only fits them. false: the generic rules, which use the yields of the tiles.
- [^T]: How the map generator widens the coast. Empty: `maxCoastExtension` passes, each ocean tile next to a coast tile becomes coast with 1 in 2. Otherwise ocean next to land becomes coast, then for each entry one pass in which an ocean tile next to a coast tile becomes coast with 1 in that number (Civ V: `[4, 4]`, so coast reaches 3 tiles from land at most).
- [^SeaOil]: A strategic resource that is also found in the sea (it needs a `Deposits in [Coast] tiles always provide [n] resources` unique), placed after the land deposits: half of the units on land divided by n deposits, on coast tiles away from each other. Civ V: Oil. The resource then needs no `Generated with weight` rule for Coast. Empty: no such rule.
- [^CivVTerrain]: Whether the map generator makes the base terrain of land and its features as the Civ V map scripts do: by latitude (snow from 75 % of the way to the pole, tundra from 60 %, grassland near the equator, desert and plains in the bands between from fractal noise), then the rivers and lakes of the scripts (four passes of sources by hills, inland land and a budget of edges per landmass; rivers turn towards wet low ground and keep their first heading), and jungle, forest, marsh, oases, flood plains, ice and atolls from fractal thresholds. The temperature and rainfall options of the scripts come from `temperatureShift` (beyond +-0.15) and `vegetationRichness` (below 0.3, above 0.5) of the map parameters. It names the terrains and features of the Civ V rulesets (Grassland, Plains, Desert, Tundra, Snow, Forest, Jungle, Marsh, Oasis, Flood plains, Ice, Hill). false: the temperature and humidity ranges of the terrain definitions.
- [^CivVLux]: Whether luxuries are assigned and placed as the Civ V map scripts do: each region (in the order of its type) gets one luxury by the weights of the resources (`Generated with weight [n] <in [type] Regions>`), each luxury at most 1, 2 or 3 times (by civ count) and at most 8 kinds in all; water luxuries need a start by the sea and 12 coast tiles; three kinds go to the City-States (`Generated near City States with weight [n]`), marble is special, some kinds are left out by world size and the rest is placed anywhere. It names the luxuries of Civ V. false: the generic placement.
- [^CivVAssign]: Whether the start regions are dealt to the civilizations as the Civ V map scripts do: civilizations with a `Coast` start bias take a random region by the sea (those with the unique `Chooses its start region before the other civilizations with a Coast start bias` first), else by a lake, else by a river; those with preferred terrains a random region of that type (one preferred type first, then more, in the order of the types; none of the type left: the region with the most of that terrain); those that avoid terrains a random region without them; the rest at random. false: the generic assignment (the region with most of the wanted terrain). Also for the City-States: they are only sent to uninhabited land as far as the regions did not take them already, and the extra ones go to the regions with the least fertility per land tile, not the least in total.
- [^CivVExtras]: The extra steps of the Civ V resource placement: one bonus resource of the kind of the region on the third ring of every start (two sheep in hills regions), more bonus resources in hills regions, a small deposit of coal, oil or aluminum at 3 in 4 City-States, top-ups of iron, horses, coal, aluminum, oil and uranium by the number of civilizations (on land tiles that can hold them), and no sugar on jungle. It names the resources of Civ V, and replaces the corresponding generic steps.
- [^CivVWonders]: Natural wonders are chosen and placed as the Civ V map scripts do: as many as the world size gives (2 on Duel up to 7 on Huge; fewer if fewer wonders have a candidate tile), drawn one by one by their `weight` from the wonders that have a candidate tile, and placed in the order drawn. A wonder that fails to be placed is replaced by the next one drawn. The generic way draws the number by map radius first and places the ones with the fewest candidate tiles first.
- [^CivVRuins]: Ancient ruins are placed as the Civ V map scripts place goody huts: on every landmass of at least 3 tiles one ruin per `tilesPerAncientRuinLikeCivV` land tiles (rounded to nearest), on tiles without a resource that are at least 3 tiles from every other ruin and more than 3 tiles from every start. Replaces `ancientRuinCountMultiplier`.
- [^D]: The minimal distance that must be between any two cities, not counting the tiles cities are on
    The number is the amount of tiles between two cities, not counting the tiles the cities are on.
    e.g. "C__C", where "C" is a tile with a city and "_" is a tile without a city, has a distance of 2.
    First constant is for cities on the same landmass, the second is for cities on different continents.
    Minimum 0.
- [^E]: NaturalWonderGenerator uses these to determine the number of Natural Wonders to spawn for a given map size. The number scales linearly with map radius: #wonders = radius * naturalWonderCountMultiplier + naturalWonderCountAddedConstant. The defaults effectively mean Tiny - 1, Small - 2, Medium - 3, Large - 4, Huge - 5, Custom radius >=109 - all G&K wonders.
- [^F]: MapGenerator.spreadAncientRuins: number of ruins = suitable tile count * this
- [^H]: MapGenerator.spawnLakesAndCoasts: Water bodies up to this tile count become Lakes
- [^I]: RiverGenerator: river frequency and length bounds
- [^J]: A [UnitUpgradeCost](#unitupgradecost) sub-structure.
- [^K]: Maximum foundable Religions = religionLimitBase + floor(MajorCivCount * religionLimitMultiplier)
- [^L]: Cost of pantheon = pantheonBase + CivsWithReligion * pantheonGrowth
- [^M]: When the AI decides whether to build a work boat, how many tiles to search from the city center for an improvable tile
- [^N]: The maximum rank any spy can reach
- [^O]: How much skill bonus each rank gives
- [^P]: The number of turns a civ has to wait before negotiating for peace
- [^Q]: The number of turns before a revolt is spawned
- [^R]: The number of turns between city-state elections
- [^S]: If set, the Improvement picker will silently skip improvements whose tech requirement is more advanced than your current Era + this value. Example: With a 0, Trade posts will not show until the Medieval Era, with a 1 they will already show in the CLassical Era.
- [^W]: Number of air units that can be stationed in a city, not including carried/transported air units.

#### UnitUpgradeCost

These values are not merged individually, only the entire sub-structure is.

| Attribute     | Type  |    | Notes |
|---------------|-------|----|-------|
| base          | Float | 10 |       |
| perProduction | Float | 2  |       |
| eraMultiplier | Float | 0  |       |
| exponent      | Float | 1  |       |
| roundTo       | Int   | 5  |       |

The formula for the gold cost of a unit upgrade is (rounded down to a multiple of `roundTo`):
        (
            max((`base` + `perProduction` \* (new_unit_cost - old_unit_cost)), 0)
            \* (1 + eraNumber \* `eraMultiplier`) \* `civModifier`
        ) ^ `exponent`
With `civModifier` being the multiplicative aggregate of ["\[relativeAmount\]% Gold cost of upgrading"](../uniques.md#global-uniques) uniques that apply.

## AI.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Brave%20New%20World/AI.json)

The data the computer players' strategic AI reads: the AI tables of the original game (Civilization V's XML), generated from it.
It is one object with these attributes; every list entry is identified by its `name` (item flavors by `kind` and `name`).
When an extension mod has an AI.json, its defines and entries replace those of the base with the same name, and new ones are added.
A ruleset without the file gets defaults: the define values of Brave New World, the four grand strategies without flavors,
no economic/military/city strategies, and item flavors derived from each object's stats and type.

| Attribute           | Type   | Notes                                                                                                                 |
|---------------------|--------|-----------------------------------------------------------------------------------------------------------------------|
| defines             | Object | Global defines of the original by name (`AI_GS_RAND_ROLL`, `DEFAULT_FLAVOR_VALUE`, ...)                                |
| postDefines         | Object | Names of objects the original refers to through post-defines                                                          |
| grandStrategies     | List   | `name`, `victoryType` (the victory it pursues), `flavors`, `flavorMods`, `yields`                                       |
| economicStrategies  | List   | `name`, `noMinorCivs`, `checkTriggerTurnCount`, `minimumNumTurnsExecuted`, `firstTurnExecuted`, `weightThreshold`, `techPrereq`, `techObsolete`, `advisor`, `advisorCounsel`, `advisorCounselImportance`, `playerFlavors`, `cityFlavors`, `personalityFlavorThresholdMods` |
| militaryStrategies  | List   | As economic strategies, plus `onlyMinorCivs`, `updateCitySpecializations`                                              |
| cityStrategies      | List   | As economic strategies without `firstTurnExecuted` and player/city flavors, plus `permanent` and `flavors`             |
| citySpecializations | List   | `name`, `yieldType`, `subtype`, `isDefault`, `isWonder`, `mustBeCoastal`, `isOperationUnitProvider`, `flavors`, `targetYields` |
| minorCivFlavors     | List   | `name` (a city-state nation), `flavors`                                                                                |
| itemFlavors         | List   | `kind` (Building, Unit, Technology, Policy, Process, Project, Resource, Improvement), `name`, `flavors`                 |
| unitAiTypes         | List   | `name` (a unit), `defaultUnitAi`, `unitAiTypes`                                                                        |
| handicaps           | List   | `name` (a difficulty), `values` (the AI columns of the original's handicaps, and PolicyNumOptionsConsidered, TechNumOptionsConsidered, CityProductionNumOptionsConsidered) |
| policyBranches      | List   | `name` (a policy branch), `aiDelayNoReligion`, `aiDelayNoCulture`, `aiDelayNoScience`, `aiDelayNoCityStates` (the branch is worthless to the AI in such a game), `firstAdopterFreePolicies`, `secondAdopterFreePolicies` (free tenets the AI expects from an ideology) |

Flavor objects map flavor names to integers. The flavors are, in the original's order: Offense, Defense, CityDefense, MilitaryTraining, Recon,
Ranged, Mobile, Naval, NavalRecon, NavalGrowth, NavalTileImprovement, Air, Expansion, Growth, TileImprovement, Infrastructure, Production,
Gold, Science, Culture, Happiness, GreatPeople, Wonder, Religion, Diplomacy, Spaceship, WaterConnection, Nuke, Antiair, UseNuke, Espionage,
AirCarrier, I_LandTradeRoute, I_SeaTradeRoute, Archaeology, I_TradeOrigin, I_TradeDestination, Airlift.
A strategy is only ever active if the game has a test for it (strategies are referred to by their original names, e.g. `ECONOMICAISTRATEGY_GS_CULTURE`).

## GlobalUniques.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/GlobalUniques.json)

GlobalUniques defines uniques that apply globally. e.g. Vanilla rulesets define the effects of Unhappiness here.

Base ruleset Mods can omit the file, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/GlobalUniques.json).
Alternatively, they can supply a file with an empty object (`{}`), meaning that no global Uniques exist for that Mod.

It has the following structure:

| Attribute   | Type            | Default         | Notes                                                                                       |
|-------------|-----------------|-----------------|---------------------------------------------------------------------------------------------|
| name        | String          | "GlobalUniques" | The name field is not used, but still must be set (the Ruleset validator might display it). |
| uniques     | List of Strings | empty           | List of [unique abilities](../uniques.md) that apply globally                               |
| unitUniques | List of Strings | empty           | List of [unique abilities](../uniques.md) that applies to each unit                         |

When extension rulesets define GlobalUniques, all uniques are merged. At the moment there is no way to change/remove uniques set by a base mod.

## Tutorials.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Tutorials.json)

**Note a Base Ruleset mod can define a "welcome page" here by adding a "Tutorial" with a name equal to the name of the mod!**
As an exception to the general rule, this file in a Base Ruleset mod will not _replace_ the default, but add to it like extension mods do.
Also, place it under `<mod>/jsons/` normally even if the original is found one level above the vanilla jsons.

Each tutorial has the following structure:

| Attribute       | Type            | Default  | Notes                                            |
|-----------------|-----------------|----------|--------------------------------------------------|
| name            | String          | Required | Entry name                                       |
| category        | String          | Optional | The category where this tutorial will be displayed. "Tutorials" is the default. |
| civilopediaText | List            | Optional | See [civilopediaText chapter](#civilopedia-text) |
| steps           | List of Strings | Optional | Plain text                                       |

If an entry contains both `steps` and `civilopediaText` attributes, the `civilopediaText` is shown first.
Tutorials shown as Popup can show an show an external image (not part of the texture atlases) if there is an image unter ExtraImages (directly under assets or the Mod folder) having the same name.
This is searched for, meaning the mod defining the Tutorial is irrelevant, mods can override builtin ExtraImages, and case sensitivity depends on the OS.

## VictoryTypes.json

[link to original](https://github.com/yairm210/Unciv/tree/master/android/assets/jsons/Civ%20V%20-%20Gods%20&%20Kings/VictoryTypes.json)

These files contain which victories this mod provides, and what milestones must be reached for someone to win a victory.
Most of the file contains of strings that are shown to the user in the victory screen, with the rest being the requirements for winning.

Base ruleset Mods can omit the file or supply an empty list, even though at least one victory type is mandatory, in which case they inherit [the ones from the Vanilla ruleset](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/Civ%20V%20-%20Vanilla/VictoryTypes.json).

Each victory have the following structure:

| Attribute              | Type            | Default  | Notes                                                                                      |
|------------------------|-----------------|----------|--------------------------------------------------------------------------------------------|
| name                   | String          | Required | Name of the victory                                                                        |
| victoryScreenHeader    | String          | none     | Shown in the footer of the victory in the `our status` in the victory screen               |
| victoryString          | String          | none     | Shown in the footer of the victory screen when you won the game with this victory          |
| defeatString           | String          | none     | Shown in the footer of the victory screen when someone else won the game with this victory |
| hiddenInVictoryScreen  | Boolean         | false    | Whether progress of this victory is hidden in the victory screen                           |
| requiredSpaceshipParts | List of Strings | empty    | What spaceship parts must be added to the capital for the corresponding milestone          |
| Milestones             | List of Strings | Required | List of milestones that must be accomplished to win, [see below](#milestones)              |
| uniques                | List of Strings | empty    | List of [unique abilities](../uniques.md) this victory has, [see below](#victory-uniques)  |
| civilopediaText        | List            | Optional | See [civilopediaText chapter](5-Miscellaneous-JSON-files.md#civilopedia-text)              |

### Victory uniques

A victory can carry uniques, like any other ruleset object.

`Only available <...>` and `Unavailable <...>` decide **who** may achieve it. They are evaluated against a
civilization, so any [civFilter](../Unique-parameters.md#civfilter) or condition on the game works.
A civilization that does not pass them never wins the victory, is not offered it in the victory screen
as one of its own goals, is not listed among the contenders for it, and does not work towards it.
Its milestones themselves are untouched.

```json
[
    {
        "name": "Reach the goal",
        "uniques": ["Only available <for [Human player] Civilizations>"],
        "milestones": ["Build [Monument]"]
    }
]
```

`Will not be chosen for new games` decides whether the victory is **offered** in the new game options,
exactly as it does for a nation. Such a victory gets no checkbox, and the "all victories" default never
enables it - a base ruleset that ships a game or a [scenario](../Scenarios.md) enables it there itself.

This is a separate unique on purpose: while a game is being set up there is no civilization to evaluate
`Only available` / `Unavailable` against, so a victory restricted to a nation, a player type or a game
speed stays selectable unless the mod says otherwise. Conditionals on `Will not be chosen for new games`
are never fulfilled for the same reason - use it plain.

### Milestones

Currently the following milestones are supported:

| Milestone                          | Requirement                                                                                                  |
|------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Build [building]                   | Build the building [building] in any city                                                                    |
| Anyone should build [building]     | Anyone must build the building [building] for all players to have this milestone                             |
| Add all [comment] in capital       | Add all units in the `requiredSpaceshipParts` field of this victory to the capital                           |
| Destroy all players                | You must be the only major civilization with any cities left                                                 |
| Capture all capitals               | Capture all the original capitals of major civilizations in the game                                         |
| Complete [amount] Policy branches  | Fully complete at least [amount] policy branches                                                             |
| Win diplomatic vote                | At any point in the game win a diplomatic vote (UN). You may lose afterwards and still retain this milestone |
| Become the world religion          | Have your religion be the majority religion in a majority of cities of all major civs                        |
| Be [Influential] over all other civilizations| Tourism has made this civilization at least that influential (Exotic, Familiar, Popular, Influential or Dominant) over every other living major civilization, those it has not met included. See the `influencePercent...` constants|
| Have highest score after max turns | Basically time victory. Enables the 'max turn' slider and calculates score when that amount is reached       |
| Have at least [amount] [countable] | Have at least [amount] of the given `countable`. Unlike the milestone below, this does not depend on the other players, so a ruleset can require several concrete things - a building, a policy, worked tiles - to be achieved in any order. |
| Have more [countable] than each player's [countable] | Have your given `countable` be more than every other Civilization's `countable` to achieve this victory. This is useful to simulate a victory similar to the Cultural Victory in Brave New World. |

## Civilopedia text

Any 'thing' defined in json and listed in the Civilopedia can supply extra text, specifically for the Civilopedia. This can be used to explain special considerations better when the automatically generated display is insufficient, or for 'flavour', background stories and the like. Such text can be formatted and linked to other Civilopedia entries, within limits.

An example of the format is:

```json
"civilopediaText": [
    { "text": "Ancient ruins provide a one-time random bonus when explored" },
    { "separator": true },
    {
        "text": "This line is red and links to the Scout including icons",
        "link": "Unit/Scout",
        "color": "red"
    },
    {
        "text": "A big fat header sporting a golden star",
        "header": 1,
        "starred": true,
        "color": "#ffeb7f"
    },
],
```

List of attributes - note not all combinations are valid:

| Attribute    | Type    | Description                                                                                                                       |
|--------------|---------|-----------------------------------------------------------------------------------------------------------------------------------|
| `text`       | String  | Text to display                                                                                                                   |
| `link`       | String  | Create link and icon, format: Category/Name or _external_ link ('http://','https://','mailto:')                                   |
| `icon`       | String  | Show icon without linking, format: Category/Name                                                                                  |
| `extraImage` | String  | Display an Image instead of text. Can be a path found in a texture atlas or or the name of a png or jpg in the ExtraImages folder |
| `imageSize`  | Float   | Size in world units of the [extraImage], the smaller coordinate is calculated preserving aspect ratio. available width            |
| `header`     | Integer | Header level. 1 means double text size and decreases from there                                                                   |
| `size`       | Integer | Text size, is 18. Use `size` or `header` but not both                                                                             |
| `indent`     | Integer | Indent level. 0 means text will follow icons, 1 aligns to the right of all icons, each further step is 30 units                   |
| `padding`    | Float   | Vertical padding between rows, 5 units                                                                                            |
| `color`      | String  | Sets text color, accepts names or 6/3-digit web colors (e.g. #FFA040)                                                             |
| `separator`  | Boolean | Renders a separator line instead of text. Can be combined only with `color` and `size` (line width, default 2)                    |
| `starred`    | Boolean | Decorates text with a star icon - if set, it receives the `color` instead of the text                                             |
| `centered`   | Boolean | Centers the line (and turns off automatic wrap). For an `extraImage`, turns on crop-to-content to equalize transparent borders    |
| `iconCrossed` | Boolean | Paint a red X over the `icon` or `link` image |

The lines from json will 'surround' the automatically generated lines such that the latter are inserted just above the first json line carrying a link, if any. If no json lines have links, they will be inserted between the automatic title and the automatic info. This method may, however, change in the future.

Note: `text` now also supports inline color markup. Insert `«color»` to start coloring text, `«»` to stop. `color` can be a name or 6/8-digit hex notation like `#ffa040` (different from the `color` attribute notation only by not allowing 3-digit codes, but allowing the alpha channel).
Effectively, the `«»` markers are replaced with `[]` _after_ translation and then passed to [gdx markup language](https://libgdx.com/wiki/graphics/2d/fonts/color-markup-language).

Note: Using an ExtraImages folder in a mod was not working until version 4.11.5

## RGB colors list

Certain objects can be specified to have its own unique color. The colors are defined by a list of 3× Integer in this order: red, green, blue. The range of color is from \[0, 0, 0\] (black) to \[255, 255, 255\] (white).

Note: The default of some objects are [gdx color classes](https://javadoc.io/doc/com.badlogicgames.gdx/gdx/latest/com/badlogic/gdx/graphics/Color.html). The values of the constants are as follows:

| name  | value           |
|-------|-----------------|
| gold  | [225, 215, 0]   |
| white | [255, 255, 255] |
| black | [0, 0, 0]       |
