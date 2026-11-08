package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvDiplomacyAI port (major civs) against the DLL formulas with the BNW XML values */
@RunWith(GdxTestRunner::class)
class DiplomacyAiTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        meet(a, b)
        meet(a, c)
        meet(b, c)
    }

    /** A major with one city whose personality is all zero */
    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun meet(x: Civilization, y: Civilization) = x.diplomacyFunctions.makeCivilizationsMeet(y)

    private fun weights(civ: Civilization, other: Civilization, old: MajorCivApproach?) =
        ApproachAi.approachWeights(civ, other, old, lookAtOtherPlayers = false, rules)

    private fun modifier(civ: Civilization, other: Civilization, modifier: DiplomaticModifiers): Float {
        OpinionAi.updateOpinion(civ, other, rules)
        return civ.getDiplomacyManager(other)!!.diplomaticModifiers[modifier.name] ?: 0f
    }

    //region Opinion

    @Test
    fun `opinion levels follow the OPINION_THRESHOLD defines inclusively`() {
        // DoUpdateOnePlayerOpinion: >= 80 unforgivable, >= 40 enemy, >= 15 competitor, >= -15 neutral, >= -40 favorable, >= -80 friend
        assertEquals(MajorCivOpinion.Unforgivable, OpinionAi.opinionLevel(80, rules))
        assertEquals(MajorCivOpinion.Enemy, OpinionAi.opinionLevel(79, rules))
        assertEquals(MajorCivOpinion.Competitor, OpinionAi.opinionLevel(15, rules))
        assertEquals(MajorCivOpinion.Neutral, OpinionAi.opinionLevel(14, rules))
        assertEquals(MajorCivOpinion.Neutral, OpinionAi.opinionLevel(-15, rules))
        assertEquals(MajorCivOpinion.Favorable, OpinionAi.opinionLevel(-16, rules))
        assertEquals(MajorCivOpinion.Friend, OpinionAi.opinionLevel(-41, rules))
        assertEquals(MajorCivOpinion.Ally, OpinionAi.opinionLevel(-81, rules))
    }

    @Test
    fun `leader personality term is twice hostile minus friendly bias`() {
        a.ai.setMajorCivApproachBias(MajorCivApproach.Hostile, 7)
        a.ai.setMajorCivApproachBias(MajorCivApproach.Friendly, 3)
        // weight (7 - 3) * 2 = 8, shown as -8
        assertEquals(-8f, modifier(a, b, DiplomaticModifiers.LeaderPersonality), 0.001f)
    }

    @Test
    fun `declaration of friendship and denouncements are opinion terms of 35`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertEquals(35f, modifier(a, b, DiplomaticModifiers.DeclarationOfFriendship), 0.001f)
        assertEquals(35f, modifier(b, a, DiplomaticModifiers.DeclarationOfFriendship), 0.001f)

        a.getDiplomacyManager(c)!!.denounce()
        // OPINION_WEIGHT_DENOUNCED_ME and OPINION_WEIGHT_DENOUNCED_THEM
        assertEquals(-35f, modifier(c, a, DiplomaticModifiers.Denunciation), 0.001f)
        assertEquals(-35f, modifier(a, c, DiplomaticModifiers.WeDenouncedThem), 0.001f)
        // C is no friend of B's: B does not mind A denouncing C
        assertEquals(0f, modifier(b, a, DiplomaticModifiers.DenouncedOurAllies), 0.001f)
    }

    @Test
    fun `denouncing a friend ends the friendship and the friend remembers`() {
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        a.getDiplomacyManager(b)!!.denounce()
        assertFalse(a.getDiplomacyManager(b)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertFalse(b.getDiplomacyManager(a)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
        assertTrue(b.ai.diplomacy.memoryOf(a).friendDenouncedUs)
        // GetRelationshipDuration of a standard game: the denouncement lasts 50 turns
        assertEquals(50, a.getDiplomacyManager(b)!!.getFlag(DiplomacyFlags.Denunciation))
    }

    @Test
    fun `land dispute levels map to their opinion weights`() {
        val memory = a.ai.diplomacy.memoryOf(b)
        memory.landDispute = DisputeLevel.Fierce
        assertEquals(-30f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
        memory.landDispute = DisputeLevel.Weak
        assertEquals(-10f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
        // No dispute is a small plus (OPINION_WEIGHT_LAND_NONE -6) and the dispute term is gone
        memory.landDispute = DisputeLevel.None
        assertEquals(6f, modifier(a, b, DiplomaticModifiers.NoLandDispute), 0.001f)
        assertEquals(0f, modifier(a, b, DiplomaticModifiers.LandDispute), 0.001f)
    }

    @Test
    fun `nuked once costs the full nuke weight and liberated cities step 30 50 60`() {
        b.ai.diplomacy.memoryOf(a).numTimesNuked = 1
        assertEquals(-50f, modifier(b, a, DiplomaticModifiers.UsedNuclearWeapons), 0.001f)
        val memory = b.ai.diplomacy.memoryOf(a)
        memory.numCitiesLiberated = 1
        assertEquals(30f, modifier(b, a, DiplomaticModifiers.LiberatedCity), 0.001f)
        memory.numCitiesLiberated = 2
        assertEquals(50f, modifier(b, a, DiplomaticModifiers.LiberatedCity), 0.001f)
        memory.numCitiesLiberated = 5
        assertEquals(60f, modifier(b, a, DiplomaticModifiers.LiberatedCity), 0.001f)
    }
    //endregion

    //region Warmonger

    @Test
    fun `declaring war on a major adds 250 warmonger to observers not at war with the victim`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 8)
        a.getDiplomacyManager(b)!!.declareWar()
        // WARMONGER_THREAT_MAJOR_ATTACKED_WEIGHT, and the victim counts it too
        assertEquals(250, c.ai.diplomacy.memoryOf(a).warmongerAmount)
        assertEquals(250, b.ai.diplomacy.memoryOf(a).warmongerAmount)
        // Score = 250 * WarmongerHate 8 / 100 = 20
        assertEquals(-20f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
    }

    @Test
    fun `an observer already at war with the victim does not count the declaration`() {
        c.getDiplomacyManager(b)!!.declareWar()
        a.getDiplomacyManager(b)!!.declareWar()
        assertEquals(0, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `declaring war on a city-state adds 125`() {
        val cityState = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        game.addCity(cityState, game.getTile(HexCoord(0, 8)))
        meet(cityState, a)
        meet(cityState, c)
        a.getDiplomacyManager(cityState)!!.declareWar()
        assertEquals(125, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `warmonger score under 5 is no opinion term`() {
        c.ai.setDiplomacyValue(DiplomacyPersonality.WarmongerHate, 1)
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 499 // 4.99 -> 4
        assertEquals(0f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 500
        assertEquals(-5f, modifier(c, a, DiplomaticModifiers.WarMongerer), 0.001f)
    }

    @Test
    fun `warmonger amount decays by 5 a turn down to 0`() {
        c.ai.diplomacy.memoryOf(a).warmongerAmount = 12
        DiplomacyTurn.doTurn(c)
        assertEquals(7, c.ai.diplomacy.memoryOf(a).warmongerAmount)
        DiplomacyTurn.doTurn(c)
        DiplomacyTurn.doTurn(c)
        assertEquals(0, c.ai.diplomacy.memoryOf(a).warmongerAmount)
    }

    @Test
    fun `conquering a city adds the warmonger offset by cities left and halves it for those at war with the loser`() {
        game.addCity(b, game.getTile(HexCoord(10, -2)))
        val lost = game.addCity(b, game.getTile(HexCoord(10, 2)))
        val d = neutralCiv(HexCoord(0, -8))
        meet(d, a)
        meet(d, b)
        d.getDiplomacyManager(b)!!.declareWar()
        val dBefore = d.ai.diplomacy.memoryOf(a).warmongerAmount
        val cBefore = c.ai.diplomacy.memoryOf(a).warmongerAmount
        val estimated = rules.worldSize(game.gameInfo.tileMap.mapParameters.mapSize.getPredefinedOrNextSmaller().name).estimatedNumCities
        val allCities = game.gameInfo.civilizations.sumOf { it.cities.size }
        // GetWarmongerOffset: 1000 * estimated / (cities in the world * cities the loser keeps (3 - 1))
        val offset = 1000 * estimated / (allCities * 2)
        DiplomacyEvents.onCityConquered(a, b, lost)
        assertEquals(offset, c.ai.diplomacy.memoryOf(a).warmongerAmount - cBefore)
        assertEquals(offset / 2, d.ai.diplomacy.memoryOf(a).warmongerAmount - dBefore)
    }
    //endregion

    //region Approaches

    @Test
    fun `the current approach is sticky and hostility sticks harder`() {
        val fromNeutral = weights(a, b, MajorCivApproach.Neutral)
        val fromHostile = weights(a, b, MajorCivApproach.Hostile)
        // APPROACH_BIAS_FOR_CURRENT 5 moves from Neutral to Hostile, plus APPROACH_HOSTILE_CURRENTLY_HOSTILE 5
        assertEquals(10, fromHostile[MajorCivApproach.Hostile.ordinal] - fromNeutral[MajorCivApproach.Hostile.ordinal])
        assertEquals(-5, fromHostile[MajorCivApproach.Neutral.ordinal] - fromNeutral[MajorCivApproach.Neutral.ordinal])
        // Deceptive also leans to war: +5 deceptive, +2 war
        val fromDeceptive = weights(a, b, MajorCivApproach.Deceptive)
        assertEquals(2, fromDeceptive[MajorCivApproach.War.ordinal] - fromNeutral[MajorCivApproach.War.ordinal])
        assertEquals(5, fromDeceptive[MajorCivApproach.Deceptive.ordinal] - fromNeutral[MajorCivApproach.Deceptive.ordinal])
    }

    @Test
    fun `approach biases and the conquest grand strategy add to the weights`() {
        val base = weights(a, b, null)
        a.ai.setMajorCivApproachBias(MajorCivApproach.Guarded, 6)
        assertEquals(6, weights(a, b, null)[MajorCivApproach.Guarded.ordinal] - base[MajorCivApproach.Guarded.ordinal])
        a.ai.grandStrategy = GrandStrategyAi.CONQUEST
        assertEquals(5, weights(a, b, null)[MajorCivApproach.War.ordinal] - base[MajorCivApproach.War.ordinal])
    }

    @Test
    fun `opinion friendship and denouncements move the approach weights`() {
        val base = weights(a, b, null)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Unforgivable)
        val unforgivable = weights(a, b, null)
        assertEquals(10, unforgivable[MajorCivApproach.War.ordinal] - base[MajorCivApproach.War.ordinal])
        assertEquals(4, unforgivable[MajorCivApproach.Guarded.ordinal] - base[MajorCivApproach.Guarded.ordinal])
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Neutral)

        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        val friends = weights(a, b, null)
        assertEquals(15, friends[MajorCivApproach.Friendly.ordinal] - base[MajorCivApproach.Friendly.ordinal])
        assertEquals(-100, friends[MajorCivApproach.Hostile.ordinal] - base[MajorCivApproach.Hostile.ordinal])

        // A denounced C, and C denounced A: two denouncements
        a.getDiplomacyManager(c)!!.denounce()
        c.getDiplomacyManager(a)!!.denounce()
        val twoDenouncements = weights(c, a, null)
        c.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.Denunciation)
        val oneDenouncement = weights(c, a, null)
        assertEquals(10, twoDenouncements[MajorCivApproach.War.ordinal] - oneDenouncement[MajorCivApproach.War.ordinal])
        assertEquals(-100, twoDenouncements[MajorCivApproach.Friendly.ordinal] - oneDenouncement[MajorCivApproach.Friendly.ordinal])
    }
    //endregion

    //region War goals and peace

    @Test
    fun `peace is wanted after the war goal has been peace for 4 turns`() {
        a.getDiplomacyManager(b)!!.declareWar()
        repeat(3) {
            a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
            DiplomacyTurn.doTurn(a)
            assertEquals(WarGoal.Peace, a.ai.diplomacy.warGoal(b))
        }
        assertFalse(a.ai.diplomacy.isWantsPeaceWith(b))
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Neutral)
        DiplomacyTurn.doTurn(a)
        assertTrue(a.ai.diplomacy.isWantsPeaceWith(b))
        // Wanting war again resets the counter
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        DiplomacyTurn.doTurn(a)
        assertEquals(WarGoal.Damage, a.ai.diplomacy.warGoal(b))
        assertFalse(a.ai.diplomacy.isWantsPeaceWith(b))
    }

    @Test
    fun `a war locked by a coop war is a conquest and no peace treaty is offered`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        DiplomacyTurn.doTurn(a)
        assertEquals(WarGoal.Damage, a.ai.diplomacy.warGoal(b))
        assertTrue(a.ai.diplomacy.treatyWillingToOffer(b) != PeaceTreatyType.None)

        a.ai.diplomacy.memoryOf(c).coopWars[b.civID] = CoopWarEntry(CoopWarState.Accepted, 0)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.War)
        DiplomacyTurn.doTurn(a)
        assertEquals(WarGoal.Conquest, a.ai.diplomacy.warGoal(b))
        assertEquals(PeaceTreatyType.None, a.ai.diplomacy.treatyWillingToOffer(b))
        assertEquals(PeaceTreatyType.None, a.ai.diplomacy.treatyWillingToAccept(b))
    }

    @Test
    fun `a coop war agreed for soon is declared at the soon counter and locks the war`() {
        // partner b, target c; a has no memory of c yet, so locking the war creates it while the memories are being walked
        // (memories of civilizations that no longer exist follow b in the walk and are skipped)
        repeat(30) { a.ai.diplomacy.memories["gone-$it"] = MajorCivMemory() }
        a.ai.diplomacy.memories.remove(c.civID)
        a.ai.diplomacy.memoryOf(b).coopWars[c.civID] = CoopWarEntry(CoopWarState.Soon, rules.define(AiDefine.COOP_WAR_SOON_COUNTER) - 1)
        CoopWarAi.doCoopWarCounters(a, rules)
        assertTrue(a.isAtWarWith(c))
        assertEquals(CoopWarState.Accepted, a.ai.diplomacy.memoryOf(b).coopWars[c.civID]!!.state)
        assertEquals(rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH), a.ai.diplomacy.memoryOf(c).turnsLockedIntoWar)
    }

    @Test
    fun `no war goal at peace`() {
        DiplomacyTurn.doTurn(a)
        assertNull(a.ai.diplomacy.warGoal(b))
    }
    //endregion

    @Test
    fun `an attack is ready when the formation's required slots are filled by healthy non-scout units`() {
        val formation = rules.multiUnitFormations.first { it.name == "MUFORMATION_BASIC_CITY_ATTACK_FORCE" }
        val tile = game.getTile(HexCoord(0, 0))
        game.addUnit("Swordsman", a, tile)
        game.addUnit("Catapult", a, game.getTile(HexCoord(1, 0)))
        // A Scout explores: it fills no slot
        game.addUnit("Scout", a, game.getTile(HexCoord(0, 1)))
        assertFalse(WarAi.isAttackReady(a, formation, bySea = false, rules))
        // A Pikeman can explore too (Unit_AITypes) but its own AI type is not exploring: it fills the attack/counter slot
        val pikeman = game.addUnit("Pikeman", a, game.getTile(HexCoord(1, -1)))
        assertTrue(WarAi.isAttackReady(a, formation, bySea = false, rules))
        // Below AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION (80) it does not count
        pikeman.health = 79
        assertFalse(WarAi.isAttackReady(a, formation, bySea = false, rules))
    }

    //region Friendship and denouncements

    @Test
    fun `declarations of friendship wait 20 turns after meeting`() {
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        game.gameInfo.turns = 19
        assertFalse(DiplomacyAutomation.isDoFAcceptable(a, b))
        game.gameInfo.turns = 20
        // Afraid always accepts once it is not too early
        assertTrue(DiplomacyAutomation.isDoFAcceptable(a, b))
    }

    @Test
    fun `DoF acceptance needs willingness plus approach and opinion of 12 with a 0 to 4 roll`() {
        game.gameInfo.turns = 30
        a.ai.setDiplomacyValue(DiplomacyPersonality.DoFWillingness, 5)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Friendly)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Friend)
        // 5 + 3 + 5 = 13: accepted whatever the roll
        assertEquals(13, DiplomacyAutomation.doFWeight(a, b))
        assertTrue(DiplomacyAutomation.isDoFAcceptable(a, b))

        a.ai.setDiplomacyValue(DiplomacyPersonality.DoFWillingness, 8)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Guarded)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Neutral)
        // 8 - 1 + 0 = 7, at most 11 with the roll
        assertEquals(7, DiplomacyAutomation.doFWeight(a, b))
        assertFalse(DiplomacyAutomation.isDoFAcceptable(a, b))

        a.ai.setDiplomacyValue(DiplomacyPersonality.DoFWillingness, 20)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        assertFalse(DiplomacyAutomation.isDoFAcceptable(a, b))
    }

    @Test
    fun `denouncing needs a weight of 18`() {
        a.ai.setDiplomacyValue(DiplomacyPersonality.DenounceWillingness, 6)
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Enemy)
        // 6 + hostile 6 + enemy 5 = 17; with the bias +3 = 20 >= 18 whatever the roll
        assertEquals(17, DiplomacyAutomation.denounceWeightBeforeRandom(a, b, bias = false))
        assertTrue(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = true))

        a.ai.setDiplomacyValue(DiplomacyPersonality.DenounceWillingness, 4)
        a.ai.diplomacy.setOpinion(b, MajorCivOpinion.Competitor)
        // 4 + 6 + 2 = 12, at most 16 with the roll
        assertEquals(12, DiplomacyAutomation.denounceWeightBeforeRandom(a, b, bias = false))
        assertFalse(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = false))

        // A friend of ours who denounced them adds 4 (DoF), 1 more if a neighbor
        a.getDiplomacyManager(c)!!.signDeclarationOfFriendship()
        a.ai.diplomacy.setOpinion(c, MajorCivOpinion.Friend)
        c.getDiplomacyManager(b)!!.denounce()
        val neighbor = if (DiplomacyTurn.proximity(a, c) == DiplomacyTurn.PROXIMITY_NEIGHBORS) 1 else 0
        assertEquals(16 + neighbor, DiplomacyAutomation.denounceWeightBeforeRandom(a, b, bias = false))

        // Never a friend
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        assertFalse(DiplomacyAutomation.isDenounceAcceptable(a, b, bias = true))
    }
    //endregion
}
