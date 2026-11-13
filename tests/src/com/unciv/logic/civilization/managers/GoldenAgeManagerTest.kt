package com.unciv.logic.civilization.managers

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class GoldenAgeManagerTest {

    val testGame = TestGame()
    val civ = testGame.addCiv()
    val goldenAgeManager = GoldenAgeManager()

    @Before
    fun setUp() {
        goldenAgeManager.civInfo = civ
    }

    @Test
    fun `should gain golden age points when happy`() {
        // when
        goldenAgeManager.endTurn(10)

        // then
        assertEquals(10, goldenAgeManager.storedHappiness)
    }

    @Test
    fun `should lose golden age points when unhappy`() {
        // given
        goldenAgeManager.storedHappiness = 100

        // when
        goldenAgeManager.endTurn(-10)

        // then
        assertEquals(90, goldenAgeManager.storedHappiness)
    }

    @Test
    fun `should not go into negative golden age points`() {
        // given
        goldenAgeManager.storedHappiness = 5

        // when
        goldenAgeManager.endTurn(-10)

        // then
        assertEquals(0, goldenAgeManager.storedHappiness)
    }

    @Test
    fun `should not store excess happiness when already in golden age`() {
        // given
        goldenAgeManager.storedHappiness = 5
        goldenAgeManager.enterGoldenAge()

        // when
        goldenAgeManager.endTurn(10)

        // then
        assertEquals(5, goldenAgeManager.storedHappiness)
    }

    @Test
    fun `should decrease golden age duration on next turn`() {
        // given
        goldenAgeManager.enterGoldenAge(10)

        // when
        goldenAgeManager.endTurn(0)

        // then
        assertEquals(9, goldenAgeManager.turnsLeftForCurrentGoldenAge)
    }

    @Test
    fun `should go in golden age with enough happiness`() {
        // given
        goldenAgeManager.storedHappiness = 700

        // when
        goldenAgeManager.endTurn(0)

        // then
        assertTrue(goldenAgeManager.isGoldenAge())
    }

    @Test
    fun `should increase golden age cost each time is triggered by happiness`() {
        // given
        val happinessRequiredForFirstGoldenAge = goldenAgeManager.happinessRequiredForNextGoldenAge()
        goldenAgeManager.storedHappiness = happinessRequiredForFirstGoldenAge

        // when
        goldenAgeManager.endTurn(10)

        // then
        assertTrue(goldenAgeManager.happinessRequiredForNextGoldenAge() > happinessRequiredForFirstGoldenAge)
    }

    @Test
    fun `should not increase golden age cost when triggered by outside factors`() {
        // given
        val happinessRequiredForFirstGoldenAge = goldenAgeManager.happinessRequiredForNextGoldenAge()
        goldenAgeManager.enterGoldenAge(10)

        // when
        goldenAgeManager.endTurn(10)

        // then
        assertEquals(happinessRequiredForFirstGoldenAge, goldenAgeManager.happinessRequiredForNextGoldenAge())
    }

    @Test
    fun `should increase golden age cost with more cities`() {
        // given
        testGame.makeHexagonalMap(1)
        testGame.addCity(civ, testGame.getTile(HexCoord.Zero), initialPopulation = 10)
        val happinessRequiredForGoldenAgeOneCity = goldenAgeManager.happinessRequiredForNextGoldenAge()

        // when
        testGame.addCity(civ, testGame.getTile(1,0), initialPopulation = 10)

        // then
        val happinessRequiredForGoldenAgeTwoCities = goldenAgeManager.happinessRequiredForNextGoldenAge()
        assertTrue(happinessRequiredForGoldenAgeOneCity < happinessRequiredForGoldenAgeTwoCities)
    }

    @Test
    fun `should increase golden age lenght due to uniques`() {
        // given
        val civ = testGame.addCiv("[+50]% Golden Age length")
        goldenAgeManager.civInfo = civ

        // when
        goldenAgeManager.enterGoldenAge(10)

        // then
        assertEquals(15, goldenAgeManager.turnsLeftForCurrentGoldenAge)
    }

    /** CvPlayer::GetGoldenAgeProgressThreshold: (500 + 250 per golden age) + 1% of it per city after the first, x GreatPeoplePercent, down to a multiple of 5 */
    @Test
    fun `the golden age threshold follows the original's integer arithmetic`() {
        testGame.makeHexagonalMap(3)
        testGame.addCity(civ, testGame.getTile(HexCoord.Zero))
        assertEquals("one city, standard speed", 500, goldenAgeManager.happinessRequiredForNextGoldenAge())
        testGame.addCity(civ, testGame.getTile(2, 0))
        testGame.addCity(civ, testGame.getTile(-2, 0))
        assertEquals("three cities: 500 + 10", 510, goldenAgeManager.happinessRequiredForNextGoldenAge())
        goldenAgeManager.storedHappiness = goldenAgeManager.happinessRequiredForNextGoldenAge()
        goldenAgeManager.endTurn(10) // the first golden age
        // 750 + int(750 x 2 x 0.01) = 765
        assertEquals(765, goldenAgeManager.happinessRequiredForNextGoldenAge())
        testGame.setSpeed("Quick")
        // 765 x 67 / 100 = 512, down to a multiple of 5
        assertEquals(510, goldenAgeManager.happinessRequiredForNextGoldenAge())
        testGame.setSpeed("Epic")
        // 765 x 150 / 100 = 1147, down to a multiple of 5
        assertEquals(1145, goldenAgeManager.happinessRequiredForNextGoldenAge())
    }
}
