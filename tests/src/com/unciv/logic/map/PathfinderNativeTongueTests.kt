package com.unciv.logic.map

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.AlertType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class PathfinderNativeTongueTests {
    private fun game() = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW).apply {
        makeHexagonalMap(5)
        UncivGame.Current.files = UncivFiles(Gdx.files)
    }

    @Test
    fun `human Native Tongue chooses one reward after consuming a ruin`() {
        val game = game()
        val civ = game.addCiv(isPlayer = true)
        val unit = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(0, 0), UniqueType.ChooseAncientRuinsReward.text)
        val ruin = game.getTile(1, 0).apply { setImprovement("Ancient ruins") }
        val cultureBefore = civ.policies.storedCulture
        val goldBefore = civ.gold

        unit.movement.moveToTile(ruin)
        assertNull(ruin.improvement)
        assertEquals(1, civ.popupAlerts.count { it.type == AlertType.RuinsReward })
        assertEquals(ruin, unit.getTile())
        val rewards = civ.ruinsManager.getEligibleRewards(unit).map { it.name }
        assertTrue("discover cultural artifacts" in rewards)
        assertTrue("a stash of gold" in rewards)
        assertEquals(cultureBefore, civ.policies.storedCulture)
        assertEquals(goldBefore, civ.gold)
        unit.movement.moveToTile(game.getTile(2, 0))
        assertEquals("the explorer waits at the ruin until a reward is chosen", ruin, unit.getTile())

        assertTrue(civ.ruinsManager.chooseReward(unit, "discover cultural artifacts"))
        assertEquals(cultureBefore + 20, civ.policies.storedCulture)
        assertEquals(goldBefore, civ.gold)
        assertFalse(civ.ruinsManager.hasPendingChoice(unit))
        assertFalse(civ.ruinsManager.chooseReward(unit, "a stash of gold"))
        assertEquals(goldBefore, civ.gold)
    }

    @Test
    fun `picker excludes rewards barred by difficulty turn conditions and recent rewards`() {
        val game = game()
        val civ = game.addCiv(isPlayer = true)
        val unit = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(0, 0), UniqueType.ChooseAncientRuinsReward.text)
        game.getTile(1, 0).setImprovement("Ancient ruins")
        unit.movement.moveToTile(game.getTile(1, 0))
        val names = civ.ruinsManager.getEligibleRewards(unit).map { it.name }
        assertFalse("squatters willing to work for you" in names)
        assertFalse("squatters wishing to settle under your rule" in names)
        assertFalse("your exploring unit receives training" in names)
        assertFalse("discover holy symbols" in names)
        assertFalse(civ.ruinsManager.chooseReward(unit, "squatters willing to work for you"))
        assertFalse(civ.ruinsManager.chooseReward(unit, "your exploring unit receives training"))
        assertTrue(civ.ruinsManager.hasPendingChoice(unit))

        assertTrue(civ.ruinsManager.chooseReward(unit, "a stash of gold"))
        game.getTile(2, 0).setImprovement("Ancient ruins")
        unit.movement.moveToTile(game.getTile(2, 0))
        assertFalse("a stash of gold" in civ.ruinsManager.getEligibleRewards(unit).map { it.name })
        assertFalse(civ.ruinsManager.chooseReward(unit, "a stash of gold"))
        assertTrue(civ.ruinsManager.hasPendingChoice(unit))
    }

    @Test
    fun `AI Native Tongue keeps ordinary weighted ruin reward instead of deferring choice`() {
        val game = game()
        val civ = game.addCiv()
        val unit = game.addDefaultMeleeUnitWithUniques(civ, game.getTile(0, 0), UniqueType.ChooseAncientRuinsReward.text)
        civ.ruinsManager.validRewards = listOf(game.ruleset.ruinRewards["a stash of gold"]!!)
        val ruin = game.getTile(1, 0).apply { setImprovement("Ancient ruins") }
        val goldBefore = civ.gold

        unit.movement.moveToTile(ruin)
        assertNull(ruin.improvement)
        assertFalse(civ.ruinsManager.hasPendingChoice(unit))
        assertTrue(civ.gold in goldBefore + 50..goldBefore + 100)
    }
}
