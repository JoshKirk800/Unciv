package com.unciv.logic.battle

import com.unciv.logic.map.tile.Tile
import com.unciv.models.Counter
import com.unciv.models.ruleset.GlobalUniques
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.ReturnsNewInstance
import kotlin.collections.set
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

object BattleDamage {

    @Readonly
    private fun getModifierStringFromUnique(unique: Unique): String {
        val source = when (unique.sourceObjectType) {
            UniqueTarget.Unit -> "Unit ability"
            UniqueTarget.Nation -> "National ability"
            UniqueTarget.Global -> GlobalUniques.getUniqueSourceDescription(unique)
            else -> "[${unique.sourceObjectName}] ([${unique.getSourceNameForUser()}])"
        }.tr()
        if (unique.modifiers.isEmpty()) return source

        val conditionalsText = unique.modifiers.joinToString { it.text.tr() }
        return "$source - $conditionalsText"
    }

    @Readonly @ReturnsNewInstance
    private fun getGeneralModifiers(combatant: ICombatant, enemy: ICombatant, combatAction: CombatAction, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()

        val conditionalState = getGameContext(combatAction, combatant, enemy)
        val civInfo = combatant.getCivInfo()

        if (combatant is MapUnitCombatant) {

            val unitUniqueModifiers = getUnitUniqueModifiers(combatant, enemy, conditionalState, tileToAttackFrom)
            modifiers.add(unitUniqueModifiers)

            val civResources = civInfo.getCivResourcesByName()
            for (resource in combatant.unit.getResourceRequirementsPerTurn().keys)
                if (civResources[resource]!! < 0 && !civInfo.isBarbarian)
                    modifiers["Missing resource"] = BattleConstants.MISSING_RESOURCES_MALUS

            val (greatGeneralName, greatGeneralBonus) = GreatGeneralImplementation.getGreatGeneralBonus(combatant, enemy, combatAction)
            if (greatGeneralBonus != 0)
                modifiers[greatGeneralName] = greatGeneralBonus

        } else if (combatant is CityCombatant) {
            combatant.city.forEachMatchingUnique(UniqueType.StrengthForCities, conditionalState) { unique ->
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        if (enemy.getCivInfo().isBarbarian) {
            modifiers["Difficulty"] =
                (civInfo.gameInfo.getDifficulty().barbarianBonus * 100).toInt()
        }

        return modifiers
    }

    @Readonly
    private fun getGameContext(
        combatAction: CombatAction,
        combatant: ICombatant,
        enemy: ICombatant,
    ): GameContext {
        val attackedTile =
            if (combatAction == CombatAction.Attack) enemy.getTile()
            else combatant.getTile()

        val conditionalState = GameContext(
            combatant.getCivInfo(),
            city = (combatant as? CityCombatant)?.city,
            ourCombatant = combatant,
            theirCombatant = enemy,
            attackedTile = attackedTile,
            combatAction = combatAction
        )
        return conditionalState
    }

    @Readonly
    private fun getUnitUniqueModifiers(combatant: MapUnitCombatant, enemy: ICombatant, conditionalState: GameContext,
                                       tileToAttackFrom: Tile): Counter<String> {
        val civInfo = combatant.getCivInfo()
        val modifiers = Counter<String>()

        combatant.unit.forEachMatchingUnique(UniqueType.Strength, conditionalState, checkCivInfoUniques = true) { unique ->
            modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
        }

        // e.g., Mehal Sefari https://civilization.fandom.com/wiki/Mehal_Sefari_(Civ5)
        for (unique in combatant.getMatchingUniques(
            UniqueType.StrengthNearCapital, conditionalState, true
        )) {
            if (civInfo.cities.isEmpty() || civInfo.getCapital() == null) break
            val distance =
                combatant.getTile().aerialDistanceTo(civInfo.getCapital()!!.getCenterTile())
            // https://steamcommunity.com/sharedfiles/filedetails/?id=326411722#464287
            val effect = unique.params[0].toInt() - 3 * distance
            if (effect > 0)
                modifiers.add(getModifierStringFromUnique(unique), effect)
        }

        //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
        var adjacentUnits = combatant.getTile().neighbors.flatMap { it.getUnits() }
        if (enemy.getTile() !in combatant.getTile().neighbors && tileToAttackFrom in combatant.getTile().neighbors
            && enemy is MapUnitCombatant
        )
            adjacentUnits += sequenceOf(enemy.unit)

        // e.g., Maori Warrior - https://civilization.fandom.com/wiki/Maori_Warrior_(Civ5)
        val strengthMalus = adjacentUnits.filter { it.civ.isAtWarWith(combatant.getCivInfo()) }
            .flatMap { it.getMatchingUniques(UniqueType.StrengthForAdjacentEnemies) }
            .filter { combatant.matchesFilter(it.params[1]) && combatant.getTile().matchesFilter(it.params[2]) }
            .maxByOrNull { it.params[0] }
        if (strengthMalus != null) {
            modifiers.add("Adjacent enemy units", strengthMalus.params[0].toInt())
        }
        return modifiers
    }

    /** CvUnit::IsNearSapper: a friendly unit of the same domain with the Sapper ability, adjacent to the attacked city and within its radius of the attacker
     *  (but not on the attacker's tile), gives the attacker a bonus against that city. The best one counts. */
    @Readonly
    private fun getSapperBonus(attacker: MapUnitCombatant, city: CityCombatant): Int {
        val unit = attacker.unit
        val attackerTile = unit.getTile()
        val cityTile = city.getTile()
        var best = 0
        for (sapper in unit.civ.units.getCivUnits()) {
            if (!sapper.cache.hasSappingBonusUnique) continue
            val sapperTile = sapper.getTile()
            if (sapperTile == attackerTile || cityTile !in sapperTile.neighbors) continue
            if (sapper.baseUnit.isLandUnit != unit.baseUnit.isLandUnit || sapper.baseUnit.isWaterUnit != unit.baseUnit.isWaterUnit) continue
            val distance = sapperTile.aerialDistanceTo(attackerTile)
            for (unique in sapper.getMatchingUniques(UniqueType.SappingBonusInRadius,
                    GameContext(unit.civ, ourCombatant = attacker, theirCombatant = city, combatAction = CombatAction.Attack))) {
                if (distance <= unique.params[2].toInt() && unit.matchesFilter(unique.params[1], state = sapper.cache.state))
                    best = max(best, unique.params[0].toInt())
            }
        }
        return best
    }

    @Readonly
    fun getAttackModifiers(
        attacker: ICombatant,
        defender: ICombatant, tileToAttackFrom: Tile
    ): Counter<String> {
        val modifiers = getGeneralModifiers(attacker, defender, CombatAction.Attack, tileToAttackFrom)

        if (attacker is MapUnitCombatant) {

            val terrainAttackModifiers = getTerrainAttackModifiers(attacker, defender, tileToAttackFrom)
            modifiers.add(terrainAttackModifiers)

            // Air unit attacking with Air Sweep
            if (attacker.unit.isPreparingAirSweep())
                modifiers.add(getAirSweepAttackModifiers(attacker))

            if (defender is CityCombatant) {
                val sapperBonus = getSapperBonus(attacker, defender)
                if (sapperBonus != 0) modifiers["Sapper"] = sapperBonus
            }

            if (attacker.isMelee()) {
                val numberOfOtherAttackersSurroundingDefender = defender.getTile().neighbors.count {
                    it.militaryUnit != null && it.militaryUnit != attacker.unit
                            && it.militaryUnit!!.civ == attacker.getCivInfo()
                            && MapUnitCombatant(it.militaryUnit!!).isMelee()
                }
                if (numberOfOtherAttackersSurroundingDefender > 0) {
                    var flankingBonus = BattleConstants.BASE_FLANKING_BONUS

                    // e.g., Discipline policy - https://civilization.fandom.com/wiki/Discipline_(Civ5)
                    attacker.unit.forEachMatchingUnique(UniqueType.FlankAttackBonus, checkCivInfoUniques = true,
                            gameContext = getGameContext(CombatAction.Attack, attacker, defender)) { unique ->
                        flankingBonus *= unique.params[0].toPercent()
                    }
                    modifiers["Flanking"] =
                        (flankingBonus * numberOfOtherAttackersSurroundingDefender).toInt()
                }
            }

        }

        return modifiers
    }

    @Readonly
    private fun getTerrainAttackModifiers(attacker: MapUnitCombatant, defender: ICombatant, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = Counter<String>()
        if (attacker.unit.isEmbarked() && defender.getTile().isLand
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Landing"] = BattleConstants.LANDING_MALUS

        // Land Melee Unit attacking to Water
        if (attacker.unit.type.isLandUnit() && !attacker.getTile().isWater && attacker.isMelee() && defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast)
        )
            modifiers["Boarding"] = BattleConstants.BOARDING_MALUS

        // Melee Unit on water attacking to Land (not City) unit
        if (!attacker.unit.type.isAirUnit() && attacker.isMelee() && attacker.getTile().isWater && !defender.getTile().isWater
            && !attacker.unit.hasUnique(UniqueType.AttackAcrossCoast) && !defender.isCity()
        )
            modifiers["Landing"] = BattleConstants.LANDING_MALUS

        if (isMeleeAttackingAcrossRiverWithNoBridge(attacker, tileToAttackFrom, defender))
            modifiers["Across river"] = BattleConstants.ATTACKING_ACROSS_RIVER_MALUS
        return modifiers
    }

    @Readonly
    private fun isMeleeAttackingAcrossRiverWithNoBridge(attacker: MapUnitCombatant, tileToAttackFrom: Tile, defender: ICombatant) = (
        attacker.isMelee()
            &&
            (tileToAttackFrom.aerialDistanceTo(defender.getTile()) == 1
                && tileToAttackFrom.isConnectedByRiver(defender.getTile())
                && !attacker.unit.hasUnique(UniqueType.AttackAcrossRiver))
            &&
            (!tileToAttackFrom.hasConnection(attacker.getCivInfo()) // meaning, the tiles are not road-connected for this civ
                || !defender.getTile().hasConnection(attacker.getCivInfo())
                || !attacker.getCivInfo().tech.roadsConnectAcrossRivers)
        )

    @Readonly
    fun getAirSweepAttackModifiers(
        attacker: ICombatant
    ): Counter<String> {
        val modifiers = Counter<String>()

        if (attacker is MapUnitCombatant) {
            attacker.unit.forEachMatchingUnique(UniqueType.StrengthWhenAirsweep) { unique ->
                modifiers.add(getModifierStringFromUnique(unique), unique.params[0].toInt())
            }
        }

        return modifiers
    }

    @Readonly
    fun getDefenceModifiers(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile): Counter<String> {
        val modifiers = getGeneralModifiers(defender, attacker, CombatAction.Defend, tileToAttackFrom)
        val tile = defender.getTile()

        if (defender is MapUnitCombatant && !defender.unit.isEmbarked()) { // Embarked units get no terrain defensive bonuses

            val tileDefenceBonus = tile.getDefensiveBonus(unit = defender.unit)
            if (!defender.unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true) && tileDefenceBonus > 0
                || !defender.unit.hasUnique(UniqueType.NoDefensiveTerrainPenalty, checkCivInfoUniques = true) && tileDefenceBonus < 0
            )
                modifiers["Tile"] = (tileDefenceBonus * 100).toInt()


            if (defender.unit.isFortified() || defender.unit.isGuarding())
                modifiers["Fortification"] = BattleConstants.FORTIFICATION_BONUS * defender.unit.getFortificationTurns()
        }

        return modifiers
    }

    /** The sum of the modifiers as a factor; a unit never drops below 10% of its strength (CvUnit::GetMaxAttackStrength: "can't drop below 10%") */
    @Readonly
    private fun modifiersToFinalBonus(modifiers: Counter<String>): Float {
        var finalModifier = 1f
        for (modifierValue in modifiers.values) finalModifier += modifierValue / 100f
        return max(finalModifier, 0.1f)
    }

    /** The original truncates the damage to whole hit points and deals at least 1 (CvUnit::getCombatDamage and the ranged and air versions) */
    @Pure
    private fun damageToInt(damage: Float): Int = max(damage.toInt(), 1)

    /** @param fullHealthScale damage is proportional to the health left, as in the original's air strikes (GetAirCombatDamage,
     *  GetAirStrikeDefenseDamage, GetInterceptionDamage - a city's too); otherwise each 3 points of health lost reduce it by 1%,
     *  and a city is not weakened at all (GetCombatDamage) */
    @Readonly
    private fun getHealthDependantDamageRatio(combatant: ICombatant, fullHealthScale: Boolean = false): Float {
        if (fullHealthScale) return combatant.getHealth().toFloat() / combatant.getMaxHealth()
        return if (combatant !is MapUnitCombatant
            || combatant.unit.hasUnique(UniqueType.NoDamagePenaltyWoundedUnits, checkCivInfoUniques = true)
        ) 1f
        else {
            // The multiplier is WOUNDED_DAMAGE_MULTIPLIER plus the owner's WoundedUnitDamageMod, never below 0
            // (CvUnit.cpp:2796-2802, strength reduction 9851-9862, projected damage 10893-10899)
            var multiplier = BattleConstants.WOUNDED_DAMAGE_MULTIPLIER
            combatant.unit.forEachMatchingUnique(UniqueType.WoundedUnitDamagePenalty, checkCivInfoUniques = true) { multiplier += it.params[0].toInt() }
            // A third of the lost health, in whole numbers: WOUNDED_DAMAGE_MULTIPLIER is 33% (CvUnit::getCombatDamage)
            (100 - (100 - combatant.getHealth()) * max(multiplier, 0) / 100) / 100f
        }
    }


    /**
     * Includes attack modifiers
     */
    @Readonly
    fun getAttackingStrength(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile
    ): Float {
        val attackModifier = modifiersToFinalBonus(getAttackModifiers(attacker, defender, tileToAttackFrom))
        val strength = max(1f, attacker.getAttackingStrength(defender) * attackModifier)
        // CvUnitCombat::GenerateMeleeCombatInfo: Heavy Charge, 150% after all the modifiers, against a unit that cannot fall back
        return if (attacker is MapUnitCombatant && attacker.unit.cache.hasHeavyChargeUnique && MeleeFallBack.isHeavyChargeBoosted(attacker, defender)) strength * 1.5f else strength
    }


    /**
     * Includes defence modifiers
     */
    @Readonly
    fun getDefendingStrength(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile): Float {
        val defenceModifier = modifiersToFinalBonus(getDefenceModifiers(attacker, defender, tileToAttackFrom))
        return max(1f, defender.getDefendingStrength(attacker) * defenceModifier)
    }
    
    @Readonly
    fun getRandomness(combatant: ICombatant): Float = 
        Random(combatant.getCivInfo().gameInfo.turns
                * combatant.getTile().position.toVector2().hashCode().toLong()).nextFloat()

    /** A bombing mission: an air unit attacking something that is not an air unit. The original resolves it separately
     *  (CvUnitCombat::GenerateAirCombatInfo) and scales damage with the full health of the one dealing it. */
    @Readonly
    fun isAirStrike(attacker: ICombatant, defender: ICombatant) = attacker.isAirUnit() && !defender.isAirUnit()

    @Readonly
    fun calculateDamageToAttacker(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1. */
        randomnessFactor: Float = getRandomness(attacker),
        fullHealthScale: Boolean = isAirStrike(attacker, defender)
    ): Int {
        if (attacker.isRanged() && !attacker.isAirUnit()) return 0
        if (defender.isCivilian()) return 0
        val ratio = getAttackingStrength(attacker, defender, tileToAttackFrom) / getDefendingStrength(
                attacker, defender, tileToAttackFrom)
        return damageToInt(damageModifier(ratio, true, randomnessFactor) * getHealthDependantDamageRatio(defender, fullHealthScale))
    }

    @Readonly
    fun calculateDamageToDefender(
        attacker: ICombatant,
        defender: ICombatant,
        tileToAttackFrom: Tile = defender.getTile(),
        /** Between 0 and 1.  Defaults to turn and location-based random to avoid save scumming */
        randomnessFactor: Float = getRandomness(defender),
        /** Damage follows the full health of the attacker instead of a third of its health loss: bombing missions and interceptions */
        fullHealthScale: Boolean = isAirStrike(attacker, defender)
    ): Int {
        if (defender.isCivilian()) return BattleConstants.DAMAGE_TO_CIVILIAN_UNIT
        val ratio = getAttackingStrength(attacker, defender, tileToAttackFrom) /
                getDefendingStrength(attacker, defender, tileToAttackFrom)
        return damageToInt(damageModifier(ratio, false, randomnessFactor) * getHealthDependantDamageRatio(attacker, fullHealthScale))
    }

    /** Whether [attacker] lives through attacking [defender]: either the estimated damage to it is less than its health, or it is a melee unit
     *  that takes a city, which it survives however much damage the city does, or a melee unit that would kill and be killed by a unit
     *  but has taken less damage in total (see [Battle.takeDamage] and [resolveMeleeDeaths]). */
    @Readonly
    fun attackerSurvives(attacker: ICombatant, defender: ICombatant, tileToAttackFrom: Tile = defender.getTile()): Boolean {
        val toDefender = calculateDamageToDefender(attacker, defender, tileToAttackFrom)
        val toAttacker = calculateDamageToAttacker(attacker, defender, tileToAttackFrom)
        if (attacker.isMelee() && defender is CityCombatant && toDefender >= defender.getHealth()) return true
        if (attacker.isMelee() && defender !is CityCombatant && !defender.isCivilian())
            return resolveMeleeDeaths(attacker, defender, toDefender, toAttacker).second < attacker.getHealth()
        return toAttacker < attacker.getHealth()
    }

    /** A melee attack on a unit rolls both damages up front and applies both (CvUnitCombat::GenerateMeleeCombatInfo). If that would kill
     *  both units, the one that has taken less damage in total - the damage it already had plus what it would take - survives with
     *  1 HP; on a tie the defender survives.
     *  @return the damage to the defender and the damage to the attacker after that correction */
    @Readonly
    fun resolveMeleeDeaths(attacker: ICombatant, defender: ICombatant, damageToDefender: Int, damageToAttacker: Int): Pair<Int, Int> {
        val attackerHealth = attacker.getHealth()
        val defenderHealth = defender.getHealth()
        if (damageToDefender < defenderHealth || damageToAttacker < attackerHealth) return damageToDefender to damageToAttacker
        val totalOnDefender = damageToDefender + defender.getMaxHealth() - defenderHealth
        val totalOnAttacker = damageToAttacker + attacker.getMaxHealth() - attackerHealth
        return if (totalOnDefender > totalOnAttacker) damageToDefender to attackerHealth - 1
        else defenderHealth - 1 to damageToAttacker
    }

    /** (max, min) bonus damage dealt to [defender] from an additional [UniqueType.ExtraRangedAttack] - `(0, 0)` if not applicable. */
    @Readonly
    fun getExtraRangedAttackBonusDamage(attacker: MapUnitCombatant, defender: ICombatant, tileToAttackFrom: Tile): Pair<Int, Int> {
        var maxExtra = 0
        var minExtra = 0
        for (fakeAttacker in Battle.getExtraRangedAttackFakeUnits(attacker)) {
            maxExtra += calculateDamageToDefender(fakeAttacker, defender, tileToAttackFrom, 1f)
            minExtra += calculateDamageToDefender(fakeAttacker, defender, tileToAttackFrom, 0f)
        }
        return maxExtra to minExtra
    }

    @Pure
    private fun damageModifier(
        attackerToDefenderRatio: Float,
        damageToAttacker: Boolean,
        /** Between 0 and 1. */
        randomnessFactor: Float,
    ): Float {
        // https://forums.civfanatics.com/threads/getting-the-combat-damage-math.646582/#post-15468029
        val strongerToWeakerRatio =
            attackerToDefenderRatio.pow(if (attackerToDefenderRatio < 1) -1 else 1)
        var ratioModifier = (((strongerToWeakerRatio + 3) / 4).pow(4) + 1) / 2
        if (damageToAttacker && attackerToDefenderRatio > 1 || !damageToAttacker && attackerToDefenderRatio < 1) // damage ratio from the weaker party is inverted
            ratioModifier = ratioModifier.pow(-1)
        val randomCenteredAround30 = 24 + 12 * randomnessFactor
        return randomCenteredAround30 * ratioModifier
    }
}
enum class CombatAction {
    Attack,
    Defend,
    Intercept,
}
