package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization

/** The original's PurchaseType, in its order (the order breaks priority ties in [PurchaseSavings]) */
enum class PurchaseType { Tile, UnitUpgrade, MinorCivGift, MajorCivTrade, Unit, Building }

/** One savings request (CvPurchaseRequest) */
class PurchaseRequest() : IsPartOfGameInfoSerialization {
    var amount = 0
    var priority = 0

    constructor(amount: Int, priority: Int) : this() {
        this.amount = amount
        this.priority = priority
    }
}

/**
 *  The gold reservation ledger of CvEconomicAI (`civ.ai.purchaseSavings`): one request per [PurchaseType] with an amount and a priority.
 *  Requests of other types with a higher priority (ties: the earlier [PurchaseType]) reserve their amount from the treasury before a
 *  withdrawal of a lower one is allowed. Serialized with the civilization; old saves start with no requests.
 */
class PurchaseSavings : IsPartOfGameInfoSerialization {
    /** By [PurchaseType] name; a missing entry is amount 0, priority 0 */
    var requests = LinkedHashMap<String, PurchaseRequest>()

    fun clone(): PurchaseSavings {
        val toReturn = PurchaseSavings()
        for ((type, request) in requests) toReturn.requests[type] = PurchaseRequest(request.amount, request.priority)
        return toReturn
    }

    /** CvEconomicAI::StartSaveForPurchase */
    fun startSaveForPurchase(type: PurchaseType, amount: Int, priority: Int) {
        requests[type.name] = PurchaseRequest(amount, priority)
    }

    /** CvEconomicAI::IsSavingForThisPurchase */
    fun isSavingForThisPurchase(type: PurchaseType): Boolean = (requests[type.name]?.amount ?: 0) > 0

    /** CvEconomicAI::CancelSaveForPurchase */
    fun cancelSaveForPurchase(type: PurchaseType) {
        requests.remove(type.name)
    }

    /**
     *  CvEconomicAI::CanWithdrawMoneyForPurchase: whether [amount] is left for [type] once every request ranked before it (by priority,
     *  descending, stable over [PurchaseType] order) has reserved its amount. A [priority] other than -1 first becomes [type]'s priority.
     */
    fun canWithdrawMoneyForPurchase(civ: Civilization, type: PurchaseType, amount: Int, priority: Int = -1): Boolean {
        if (priority != -1) {
            val request = requests.getOrPut(type.name) { PurchaseRequest() }
            request.priority = priority
        }
        val available = availableAfterHigherPriorities(civ, type) ?: return false
        return available >= amount
    }

    /** CvEconomicAI::AmountAvailableForPurchase: the treasury minus what the requests ranked before [type] reserve (0 if nothing is left) */
    fun amountAvailableForPurchase(civ: Civilization, type: PurchaseType): Int = availableAfterHigherPriorities(civ, type) ?: 0

    /** The balance left when the loop of the original reaches [type]; null when it ran out of money (balance <= 0) before */
    private fun availableAfterHigherPriorities(civ: Civilization, type: PurchaseType): Int? {
        var balance = civ.gold
        val ownPriority = requests[type.name]?.priority ?: 0
        for (other in PurchaseType.entries) {
            if (other == type) continue
            // The original keeps a request (amount 0, priority 0) for every type: those count in the ranking too
            val request = requests[other.name]
            val otherPriority = request?.priority ?: 0
            // std::stable_sort by descending priority: ranked before [type] if higher, or equal and earlier in the enum
            val rankedBefore = otherPriority > ownPriority || (otherPriority == ownPriority && other.ordinal < type.ordinal)
            if (!rankedBefore) continue
            balance -= request?.amount ?: 0
            if (balance <= 0) return null
        }
        return balance
    }
}

/** Empire ratios of CvEconomicAI that other AI parts read */
object EconomicAiRatios {
    /** CvEconomicAI::GetWorkersToCitiesRatio: land workers (UNITAI_WORKER, including those being trained) per city */
    fun workersToCitiesRatio(civ: Civilization): Double =
        UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.WORKER, includeBeingTrained = true, includeWater = false) / civ.cities.size.toDouble()

    /** CvEconomicAI::GetImprovedToImprovablePlotsRatio: owned land tiles (not impassable, mountain or city) with an unpillaged improvement */
    fun improvedToImprovablePlotsRatio(civ: Civilization): Double {
        var valid = 0
        var improved = 0
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!isImprovablePlot(tile)) continue
            valid++
            if (tile.improvement != null && !tile.improvementIsPillaged) improved++
        }
        return if (valid <= 0) 1.0 else improved / valid.toDouble()
    }

    /** The plots CvEconomicAI counts as improvable: land, passable, not a mountain, not a city */
    internal fun isImprovablePlot(tile: com.unciv.logic.map.tile.Tile): Boolean =
        !tile.isWater && !tile.isImpassible() && tile.baseTerrain != com.unciv.Constants.mountain && !tile.isCityCenter()
}
