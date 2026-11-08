package com.unciv.models.ruleset.ai

import com.unciv.models.stats.INamed

/**
 *  The prerequisites of a technology (Technology_PrereqTechs, by Unciv tech names) in the order of the XML rows - the order of the original's
 *  CvTechEntry::GetPrereqAndTechs, which CvTechAI::PropagateWeights depends on. Unciv's [com.unciv.models.ruleset.tech.Technology.prerequisites]
 *  is a set and does not keep it.
 */
class TechPrereqsDef : INamed {
    override var name = ""
    var prereqs = ArrayList<String>()
}
