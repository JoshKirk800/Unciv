package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Decision-level checks of the city-state part of the CvDiplomacyAI port (approaches, protection, gifts, tribute) against the DLL formulas */
@RunWith(GdxTestRunner::class)
class CityStateAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var cs: Civilization

    private val ignore = MinorCivApproach.Ignore.ordinal
    private val friendly = MinorCivApproach.Friendly.ordinal
    private val protective = MinorCivApproach.Protective.ordinal
    private val bully = MinorCivApproach.Bully.ordinal

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = game.addCiv()
        for (flavor in Flavor.entries) a.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) a.ai.setDiplomacyValue(value, 0)
        for (approach in MinorCivApproach.entries) a.ai.minorCivApproachBiases[approach.name] = 5
        game.addCity(a, game.getTile(HexCoord(0, 0)))
        cs = game.addCiv(cityStateType = "Cultured")
        cs.cityStatePersonality = CityStatePersonality.Neutral
        game.addCity(cs, game.getTile(HexCoord(6, 0)))
        a.diplomacyFunctions.makeCivilizationsMeet(cs)
    }

    private fun influence(value: Float) = cs.getDiplomacyManager(a)!!.setInfluence(value)

    private fun weights(old: MinorCivApproach = MinorCivApproach.Ignore) = CityStateAi.approachWeights(a, cs, old, emptyMap(), null)

    //region Approaches

    @Test
    fun `being friends with a city-state favours Friendly and Protective and disfavours Bully`() {
        val before = weights()
        influence(30f)
        val after = weights()
        // MINOR_APPROACH_FRIENDLY_FRIENDS 4, _PROTECTIVE_FRIENDS 2, bully -10, all × 100
        assertEquals(400, after[friendly] - before[friendly])
        assertEquals(200, after[protective] - before[protective])
        assertEquals(-1000, after[bully] - before[bully])
    }

    @Test
    fun `the diplomatic grand strategy turns away from Ignore and Bully`() {
        val before = weights()
        a.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        val after = weights()
        assertEquals(-1500, after[ignore] - before[ignore])
        assertEquals(-1500, after[bully] - before[bully])
    }

    @Test
    fun `last turn's approach sticks, a Protective one most`() {
        val fromIgnore = weights(MinorCivApproach.Ignore)
        val fromProtective = weights(MinorCivApproach.Protective)
        // bias for current 4 and PROTECTIVE_CURRENTLY_PROTECTIVE 10 instead of the bias on Ignore; bully -10
        assertEquals(1400, fromProtective[protective] - fromIgnore[protective])
        assertEquals(-400, fromProtective[ignore] - fromIgnore[ignore])
        assertEquals(-1000, fromProtective[bully] - fromIgnore[bully])
    }

    @Test
    fun `the leader's minor civ approach bias adds directly`() {
        val before = weights()
        a.ai.minorCivApproachBiases[MinorCivApproach.Bully.name] = 9
        assertEquals(400, weights()[bully] - before[bully])
    }

    @Test
    fun `Protective is not chosen where we cannot pledge`() {
        a.ai.minorCivApproachBiases[MinorCivApproach.Protective.name] = 20
        assertTrue(weights()[protective] > 0)
        influence(-10f)
        assertEquals(0, weights()[protective])
    }

    @Test
    fun `Bully is not chosen toward our ally`() {
        a.ai.minorCivApproachBiases[MinorCivApproach.Bully.name] = 30
        influence(70f)
        cs.cityStateFunctions.updateAllyCivForCityState()
        assertEquals(a, cs.allyCiv)
        assertEquals(0, weights()[bully])
    }

    @Test
    fun `the best approach is the highest weight, the first one on a tie`() {
        assertEquals(MinorCivApproach.Friendly, CityStateAi.bestOf(intArrayOf(1, 5, 5, 0, 2)))
        assertEquals(MinorCivApproach.Bully, CityStateAi.bestOf(intArrayOf(1, 5, 5, 0, 6)))
    }

    @Test
    fun `the yearly update stores an approach for every met city-state`() {
        a.ai.minorCivApproachBiases[MinorCivApproach.Friendly.name] = 30
        CityStateAi.updateMinorCivApproaches(a)
        assertEquals(MinorCivApproach.Friendly, a.ai.minorCivApproaches[cs.civID])
    }
    //endregion

    //region Protection

    @Test
    fun `a Protective AI pledges and a non-protective one withdraws once the lock ends`() {
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Protective)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        // Locked for 10 turns after pledging
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Ignore)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        cs.getDiplomacyManager(a)!!.removeFlag(DiplomacyFlags.RecentlyPledgedProtection)
        CityStateAi.updateMinorCivProtection(a, cs, MinorCivApproach.Friendly)
        assertFalse(CityStateAi.isProtectedBy(cs, a))
    }
    //endregion

    //region Gifts

    @Test
    fun `gift size is small for a quick boost, else large, else medium`() {
        assertEquals(250, CityStateAi.giftAmount(1500, true, 250, 500, 1000))
        assertEquals(1000, CityStateAi.giftAmount(1500, false, 250, 500, 1000))
        assertEquals(500, CityStateAi.giftAmount(900, false, 250, 500, 1000))
        assertEquals(0, CityStateAi.giftAmount(400, false, 250, 500, 1000))
        assertEquals(0, CityStateAi.giftAmount(200, true, 250, 500, 1000))
    }

    @Test
    fun `only Friendly or Protective city-states get gifts, Protective ones a bit more`() {
        assertNull(CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Ignore, CityStateAi.GoldGift(cs)))
        assertNull(CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Bully, CityStateAi.GoldGift(cs)))
        val friendlyValue = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs))!!
        val protectiveValue = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Protective, CityStateAi.GoldGift(cs))!!
        assertEquals(10, protectiveValue - friendlyValue)
    }

    @Test
    fun `an ally is gifted only when close to losing the alliance, with a quick boost`() {
        influence(75f)
        cs.cityStateFunctions.updateAllyCivForCityState()
        assertEquals(0, CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs)))
        influence(62f)
        val gift = CityStateAi.GoldGift(cs)
        val value = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, gift)!!
        assertTrue(value >= 100 + 250)
        assertTrue(gift.quickBoost)
    }

    @Test
    fun `a city-state we bullied recently is worth 100 less`() {
        val before = CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs))!!
        cs.getDiplomacyManager(a)!!.setFlag(DiplomacyFlags.Bullied, 20)
        assertEquals(before - 100, CityStateAi.goldGiftValue(a, cs, MinorCivApproach.Friendly, CityStateAi.GoldGift(cs)))
    }

    @Test
    fun `a diplomatic AI pledges to and gifts a Protective city-state the large gift`() {
        a.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Protective
        a.addGold(1000 - a.gold)
        val influenceBefore = cs.getDiplomacyManager(a)!!.getInfluence()
        val expectedGain = cs.cityStateFunctions.influenceGainedByGift(a, 1000)
        CityStateAi.contactMinorCivs(a)
        assertTrue(CityStateAi.isProtectedBy(cs, a))
        assertEquals(0, a.gold)
        assertEquals(influenceBefore + expectedGain, cs.getDiplomacyManager(a)!!.getInfluence(), 0.01f)
    }

    @Test
    fun `without enough gold for a gift the AI saves for it`() {
        a.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Protective
        a.addGold(100 - a.gold)
        CityStateAi.contactMinorCivs(a)
        assertEquals(100, a.gold)
        assertTrue(a.ai.purchaseSavings.isSavingForThisPurchase(com.unciv.logic.automation.strategy.PurchaseType.MinorCivGift))
    }
    //endregion

    //region Tribute

    private fun intimidate() {
        val tiles = cs.getCapital()!!.getCenterTile().getTilesAtDistance(2).filter { it.getOwner() == null }.take(3).toList()
        for (tile in tiles) game.addUnit("Musketman", a, tile)
    }

    @Test
    fun `gold tribute is wanted from a Bully city-state that is not a friend`() {
        intimidate()
        val value = CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully)!!
        assertTrue(value > 100)
        assertNull(CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Friendly))
        a.ai.deals.bulliedMinors.add(cs.civID)
        assertEquals(value + 20, CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully))
        influence(30f)
        assertEquals(0, CityStateAi.bullyGoldValue(a, cs, MinorCivApproach.Bully))
    }

    @Test
    fun `no worker is demanded from a city-state below size 4`() {
        intimidate()
        assertNull(CityStateAi.bullyUnitValue(a, cs, MinorCivApproach.Bully))
    }

    @Test
    fun `a gold-minded AI takes tribute from a Bully city-state`() {
        intimidate()
        a.ai.setPersonalityFlavor(Flavor.Gold, 6)
        a.ai.minorCivApproaches[cs.civID] = MinorCivApproach.Bully
        val goldBefore = a.gold
        CityStateAi.contactMinorCivs(a)
        assertEquals(goldBefore + cs.cityStateFunctions.goldGainedByTribute(), a.gold)
        assertTrue(cs.civID in a.ai.deals.bulliedMinors)
    }
    //endregion

    //region Initial flavors

    @Test
    fun `a Hostile city-state's defense flavors move by up to 2, a Friendly one's go to 10`() {
        val flavors = IntArray(Flavor.count) { 5 }
        cs.cityStatePersonality = CityStatePersonality.Hostile
        CityStateAi.pickInitialFlavors(cs, flavors, Random(1))
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) assertTrue(flavors[flavor.ordinal] in 3..7)
        assertEquals(5, flavors[Flavor.Growth.ordinal])
        val friendlyFlavors = IntArray(Flavor.count) { 5 }
        cs.cityStatePersonality = CityStatePersonality.Friendly
        CityStateAi.pickInitialFlavors(cs, friendlyFlavors, Random(1))
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) assertEquals(10, friendlyFlavors[flavor.ordinal])
    }
    //endregion
}
