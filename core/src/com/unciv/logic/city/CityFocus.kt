package com.unciv.logic.city

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.city.CitizenAutomation
import com.unciv.models.stats.Stat
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.screens.cityscreen.CitizenManagementTable
import yairm210.purity.annotations.Pure

/**
 *  Controls automatic worker-to-tile assignment: the original's CityAIFocusTypes
 *  @param  label Display label, formatted for tr()
 *  @param  tableEnabled Whether to show or hide in CityScreen's [CitizenManagementTable]
 *  @param  stat Which stat the focus emphasizes
 *  @param  binding Bindable keyboard key in UI - this is an override, by default matching enum names in [KeyboardBinding] are assigned automatically
 *  @see    CitizenAutomation.Valuation for what each focus weighs
 *  Order matters for building the [CitizenManagementTable]
 */
enum class CityFocus(
    val label: String,
    val tableEnabled: Boolean,
    val stat: Stat? = null,
    binding: KeyboardBinding? = null
) : IsPartOfGameInfoSerialization {
    // region Enum values
    NoFocus("Default", true, null),
    /** Never clears existing assignments, only assigns new population using default focus, implemented in [City.reassignPopulation] */
    Manual("Manual", true, null),
    FoodFocus(Stat.Food),
    ProductionFocus(Stat.Production),
    GoldFocus(Stat.Gold),
    ScienceFocus(Stat.Science),
    CultureFocus(Stat.Culture),
    HappinessFocus(Stat.Happiness, false),
    FaithFocus(Stat.Faith),
    GoldGrowthFocus("${Stat.Gold.character} ${Stat.Food.character}", true),
    ProductionGrowthFocus("${Stat.Production.character} ${Stat.Food.character}", true),
    //GreatPersonFocus

    ;
    // endregion Enum values

    constructor(stat: Stat, tableEnabled: Boolean = true) : this("${stat.character}", tableEnabled, stat)

    val binding: KeyboardBinding =
        binding ?:
        KeyboardBinding.entries.firstOrNull { it.name == name } ?:
        KeyboardBinding.None

    companion object {
        @Pure fun safeValueOf(stat: Stat): CityFocus = entries.firstOrNull { it.stat == stat } ?: NoFocus
    }
}
