package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import org.junit.Assert.assertTrue
import org.junit.Test
import com.unciv.testing.TestGame
import org.junit.runner.RunWith
import com.unciv.testing.BaseTestRunner

/** The ported diplomacy AI divides by, and clamps with, values of the ruleset's AI data: each Civ V ruleset must give them valid values */
@RunWith(BaseTestRunner::class)
class AiRulesetDataTests {
    @Test
    fun `every Civ V ruleset has positive game speed durations and a positive assist bound`() {
        for (base in listOf(BaseRuleset.Civ_V_Vanilla, BaseRuleset.Civ_V_GnK, BaseRuleset.Civ_V_BNW)) {
            val ai = TestGame(baseRuleset = base).ruleset.ai
            for (speed in listOf("Marathon", "Epic", "Standard", "Quick")) {
                val def = ai.gameSpeedDiplomacy(speed)
                assertTrue("${base.fullName} $speed opinion duration ${def.opinionDurationPercent}", def.opinionDurationPercent > 0)
                assertTrue("${base.fullName} $speed relationship duration ${def.relationshipDuration}", def.relationshipDuration > 0)
            }
            assertTrue("${base.fullName} OPINION_WEIGHT_ASSIST_MAX", ai.define(AiDefine.OPINION_WEIGHT_ASSIST_MAX) > 0)
        }
    }
}
