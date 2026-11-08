package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

/** CvUnit::canSellExoticGoods / sellExoticGoods: a Nau can sell its cargo beside peaceful foreign territory. */
object UnitActionsExoticGoods {
    internal fun getActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        val unique = unit.getMatchingUniques(UniqueType.SellExoticGoods).firstOrNull() ?: return emptySequence()
        val maxSales = unique.params[0].toInt()
        if (unit.abilityToTimesUsed.getOrDefault(unique.text, 0) >= maxSales) return emptySequence()
        val isAvailable = canSell(unit, tile)
        return sequenceOf(UnitAction(UnitActionType.SellExoticGoods, associatedUnique = unique,
            action = {
                if (unit.abilityToTimesUsed.getOrDefault(unique.text, 0) < maxSales && canSell(unit, unit.getTile())) {
                    val mapSize = unit.civ.gameInfo.tileMap.mapParameters.mapSize
                    val threshold = ((mapSize.width + mapSize.height) / 2).coerceAtLeast(1)
                    val capital = unit.civ.getCapital()
                    val distance = if (capital == null) 0 else unit.getTile().aerialDistanceTo(capital.getCenterTile())
                    unit.civ.addGold((100 + 300 * distance / threshold).coerceAtMost(400))
                    unit.promotions.XP += (10 + 20 * distance / threshold).coerceAtMost(30)
                    unit.abilityToTimesUsed[unique.text] = unit.abilityToTimesUsed.getOrDefault(unique.text, 0) + 1
                }
            }.takeIf { isAvailable }))
    }

    /** CvUnit::calculateExoticGoodsDistanceFactor: how far [tile] is from our capital as a share of the reward cap's distance (0 to 1; 0 without a capital) */
    fun distanceFactor(unit: MapUnit, tile: Tile): Float {
        val capital = unit.civ.getCapital() ?: return 0f
        val mapSize = unit.civ.gameInfo.tileMap.mapParameters.mapSize
        val threshold = ((mapSize.width + mapSize.height) / 2).coerceAtLeast(1)
        return (tile.aerialDistanceTo(capital.getCenterTile()).toFloat() / threshold).coerceAtMost(1f)
    }

    /** CvHomelandAI::ExecuteExplorerMoves sells only when at least halfway to the reward cap. */
    fun tryAutoSell(unit: MapUnit) {
        if (distanceFactor(unit, unit.getTile()) < 0.5f) return
        getActions(unit, unit.getTile()).firstOrNull()?.action?.invoke()
    }

    /** CvUnit::canSellExoticGoods: [tile] is next to the territory of a civilization we are not at war with */
    fun canSell(unit: MapUnit, tile: Tile) = tile.neighbors.any { neighbor ->
        val owner = neighbor.getOwner()
        owner != null && owner != unit.civ && !unit.civ.isAtWarWith(owner)
    }
}
