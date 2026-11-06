package com.unciv.logic.city.managers

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.city.City
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap.Companion.NO_UNIQUE_FILTER
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

class CityReligionManager : IsPartOfGameInfoSerialization {
    @Transient
    lateinit var city: City

    // This needs to be kept track of for the
    // "[Stats] when a city adopts this religion for the first time" unique
    private val religionsAtSomePointAdopted: HashSet<String> = hashSetOf()

    private val pressures: Counter<String> = Counter()
    // Cached because using `updateNumberOfFollowers` to get this value resulted in many calls
    @Transient
    private val followers: Counter<String> = Counter()

    @delegate:Transient
    private val pressureFromAdjacentCities: Int by lazy { city.civ.gameInfo.speed.religiousPressureAdjacentCity }

    var religionThisIsTheHolyCityOf: String? = null
    var isBlockedHolyCity = false

    init {
        clearAllPressures()
    }

    fun clone(): CityReligionManager {
        val toReturn = CityReligionManager()
        toReturn.city = city
        toReturn.religionsAtSomePointAdopted.addAll(religionsAtSomePointAdopted)
        toReturn.pressures.putAll(pressures)
        toReturn.followers.putAll(followers)
        toReturn.religionThisIsTheHolyCityOf = religionThisIsTheHolyCityOf
        toReturn.isBlockedHolyCity = isBlockedHolyCity
        return toReturn
    }

    fun setTransients(city: City) {
        this.city = city
        // We don't need to check for changes in the majority religion, and as this
        // loads in the religion, _of course_ the religion changes, but it shouldn't
        // have any effect
        updateNumberOfFollowers(false)
    }

    fun endTurn() {
        getAffectedBySurroundingCities()
    }

    /** CvCityReligions::GetSecondaryReligion: the major religion other than the majority one that has the most followers
     *  here, a tie going to the one with more pressure. Pantheons do not count. */
    @Readonly
    private fun getSecondaryReligion(majority: String): Religion? {
        var best: String? = null
        for ((name, count) in followers) {
            if (name == majority || count <= 0) continue
            if (city.civ.gameInfo.religions[name]?.isMajorReligion() != true) continue
            if (best == null || count > followers[best] || (count == followers[best] && pressures[name] > pressures[best]))
                best = name
        }
        return best?.let { city.civ.gameInfo.religions[it] }
    }

    /** CvCityReligions::GetSecondaryReligionPantheonBelief (CvReligionClasses.cpp:2808): with [UniqueType.SecondaryReligionPantheon]
     *  the first Pantheon belief of the second most popular religion works in the city like one of the majority religion */
    @Readonly
    fun getSecondaryPantheonBelief(): Belief? {
        if (followers.size < 2) return null // cheap test first: this is asked for every unique lookup of a city
        val majority = getMajorityReligionName() ?: return null
        var enabled = false
        city.civ.forEachMatchingUnique(UniqueType.SecondaryReligionPantheon) { enabled = true }
        if (!enabled) return null
        return getSecondaryReligion(majority)?.getBeliefs(BeliefType.Pantheon)?.firstOrNull()
    }

    @Readonly
    @Deprecated(message = "forEachUnique is faster. If not viable, then this can still be used",
        replaceWith = ReplaceWith("forEachUnique"))
    fun getUniques(uniqueType: UniqueType): Sequence<Unique> {
        val majorityReligion = getMajorityReligion() ?: return emptySequence()
        val uniques = majorityReligion.followerBeliefUniqueMap.getUniques(uniqueType)
        val secondary = getSecondaryPantheonBelief() ?: return uniques
        return uniques + secondary.uniqueMap.getUniques(uniqueType)
    }

    @Readonly
    fun forEachMatchingUnique(uniqueType: UniqueType, gameContext: GameContext=city.state, op: (unique: Unique)->Unit)
        = forEachMatchingUnique(uniqueType, gameContext, NO_UNIQUE_FILTER, op)
    @Readonly
    fun forEachMatchingUnique(uniqueType: UniqueType, gameContext: GameContext=city.state, filter:(Unique)->Boolean, op: (unique: Unique)->Unit) {
        val majorityReligion = getMajorityReligion() ?: return
        majorityReligion.followerBeliefUniqueMap.forEachMatchingUnique(uniqueType, gameContext, filter, op)
        getSecondaryPantheonBelief()?.uniqueMap?.forEachMatchingUnique(uniqueType, gameContext, filter, op)
    }

    @Readonly
    fun getAllUniques(): Sequence<Unique> {
        val majorityReligion = getMajorityReligion() ?: return emptySequence()
        val uniques = majorityReligion.followerBeliefUniqueMap.getAllUniques()
        val secondary = getSecondaryPantheonBelief() ?: return uniques
        return uniques + secondary.uniqueMap.getAllUniques()
    }

    @Readonly
    fun forEachUnique(filter:(Unique)->Boolean, op: (unique: Unique)->Unit) {
        val majorityReligion = getMajorityReligion() ?: return
        majorityReligion.followerBeliefUniqueMap.forEachUnique(filter, op)
        getSecondaryPantheonBelief()?.uniqueMap?.forEachUnique(filter, op)
    }

    /** CvCityReligions::GetNumReligionsWithFollowers (CvReligionClasses.cpp:2585): the major religions with at least one follower in the city */
    @Readonly
    fun getNumberOfMajorReligionsWithFollowers(): Int =
        followers.count { it.value > 0 && city.civ.gameInfo.religions[it.key]?.isMajorReligion() == true }

    @Readonly fun getPressures(): Counter<String> = pressures.clone()

    private fun clearAllPressures() {
        pressures.clear()
        // We add pressure for following no religion
        // Basically used as a failsafe so that there is always some religion,
        // and we don't suddenly divide by 0 somewhere
        // Should be removed when updating the followers so it never becomes the majority religion,
        // `null` is used for that instead.
        pressures.add(Constants.noReligionName, 100)
    }

    fun addPressure(religionName: String, amount: Int, shouldUpdateFollowers: Boolean = true) {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no pressures
        pressures.add(religionName, amount)

        if (shouldUpdateFollowers) updateNumberOfFollowers()
    }

    /** The pressure of a Spread Religion action that leaves the other religions in place: as [addPressure], and the religion with
     *  [UniqueType.ErodesOtherReligionPressure] takes that share of the added amount away from each other major religion
     *  (CvCityReligions::AddReligiousPressure, CvReligionClasses.cpp:3119; pantheons and atheism are not eroded) */
    fun addPressureFromSpreading(religionName: String, amount: Int) {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no pressures
        var erosionPercent = 0
        city.civ.gameInfo.religions[religionName]?.founderBeliefUniqueMap
            ?.forEachMatchingUnique(UniqueType.ErodesOtherReligionPressure, city.state) { erosionPercent += it.params[0].toInt() }
        if (erosionPercent > 0) {
            val erosion = amount * erosionPercent / 100
            for ((name, pressure) in pressures.toList()) { // a copy, as the pressures change
                if (name == religionName || name == Constants.noReligionName) continue
                if (city.civ.gameInfo.religions[name]?.isMajorReligion() != true) continue
                pressures.add(name, -minOf(pressure, erosion))
            }
        }
        addPressure(religionName, amount)
    }

    /** Removes the pressure of all religions but [religion] and atheism. A religion with [UniqueType.ReligionPressureRetention] keeps
     *  that share of its pressure (CvCityReligions::RemoveOtherReligions, CvReligionClasses.cpp:3381, and AddProphetSpread, 3051) */
    fun removeAllPressuresExceptFor(religion: String) {
        val pressureFromThisReligion = pressures[religion]
        // Atheism is never removed
        val pressureFromAtheism = pressures[Constants.noReligionName]
        val retainedPressures = Counter<String>()
        for ((name, pressure) in pressures) {
            if (name == religion || name == Constants.noReligionName) continue
            val other = city.civ.gameInfo.religions[name] ?: continue
            if (!other.isMajorReligion()) continue
            var retention = 0
            other.founderBeliefUniqueMap.forEachMatchingUnique(UniqueType.ReligionPressureRetention, city.state) { retention += it.params[0].toInt() }
            if (retention > 0) retainedPressures.add(name, pressure * retention.coerceAtMost(100) / 100)
        }
        clearAllPressures()
        pressures.add(religion, pressureFromThisReligion)
        for ((name, pressure) in retainedPressures) pressures.add(name, pressure)
        if (pressureFromAtheism != 0) pressures[Constants.noReligionName] = pressureFromAtheism
        updateNumberOfFollowers()
    }

    fun updatePressureOnPopulationChange(populationChangeAmount: Int) {
        val majorityReligion =
            if (getMajorityReligionName() != null) getMajorityReligionName()!!
            else Constants.noReligionName

        if (populationChangeAmount > 0) {
            addPressure(majorityReligion, 100 * populationChangeAmount)
        } else {
            updateNumberOfFollowers()
        }
    }

    private fun triggerReligionAdoption(newMajorityReligion: String) {
        val newMajorityReligionObject = city.civ.gameInfo.religions[newMajorityReligion]!!
        city.civ.addNotification("Your city [${city.name}] was converted to [${newMajorityReligionObject.getReligionDisplayName()}]!",
            city.location, NotificationCategory.Religion, NotificationIcon.Faith)

        if (newMajorityReligion in religionsAtSomePointAdopted) return

        val religionOwningCiv = newMajorityReligionObject.foundingCiv
        if (religionOwningCiv.hasUnique(UniqueType.StatsWhenAdoptingReligion)) {
            val statsGranted =
                religionOwningCiv.getMatchingUniques(UniqueType.StatsWhenAdoptingReligion).map { it.stats.times(if (!it.isModifiedByGameSpeed()) 1f else city.civ.gameInfo.speed.modifier) }
                .reduce { acc, stats -> acc + stats }

            for ((key, value) in statsGranted)
                religionOwningCiv.addStat(key, value.toInt())
            if (religionOwningCiv.hasExplored(city.getCenterTile()))
                religionOwningCiv.addNotification(
                    "You gained [$statsGranted] as your religion was spread to [${city.name}]",
                    city.location,
                    NotificationCategory.Religion,
                    NotificationIcon.Faith
                )
            else
                religionOwningCiv.addNotification(
                    "You gained [$statsGranted] as your religion was spread to an unknown city",
                    NotificationCategory.Religion,
                    NotificationIcon.Faith
                )
        }
        religionsAtSomePointAdopted.add(newMajorityReligion)
    }

    private fun updateNumberOfFollowers(checkForReligionAdoption: Boolean = true) {
        val oldMajorityReligion =
            if (checkForReligionAdoption) getMajorityReligionName()
            else null

        val previousFollowers = followers.clone()
        followers.clear()

        if (city.population.population <= 0) return

        val remainders = HashMap<String, Float>()
        val pressurePerFollower = pressures.values.sum() / city.population.population

        // First give each religion an approximate share based on pressure
        for ((religion, pressure) in pressures) {
            val followersOfThisReligion = (pressure.toFloat() / pressurePerFollower).toInt()
            followers.add(religion, followersOfThisReligion)
            remainders[religion] = pressure.toFloat() - followersOfThisReligion * pressurePerFollower
        }

        var unallocatedPopulation = city.population.population - followers.values.sum()

        // Divide up the remaining population
        while (unallocatedPopulation > 0) {
            val largestRemainder = remainders.maxByOrNull { it.value }
            if (largestRemainder == null) {
                followers.add(Constants.noReligionName, unallocatedPopulation)
                break
            }
            followers.add(largestRemainder.key, 1)
            remainders[largestRemainder.key] = 0f
            unallocatedPopulation -= 1
        }

        followers.remove(Constants.noReligionName)


        if (checkForReligionAdoption) {
            val newMajorityReligion = getMajorityReligionName()
            if (oldMajorityReligion != newMajorityReligion && newMajorityReligion != null) {
                triggerReligionAdoption(newMajorityReligion)
            }
            if (oldMajorityReligion != newMajorityReligion)
                city.civ.cache.updateCivResources() // follower uniques can provide resources
            if (followers != previousFollowers)
                city.cityStats.update()
        }
    }

    @Readonly fun getNumberOfFollowers(): Counter<String> = followers.clone()
    @Readonly fun getFollowersOf(religion: String): Int = followers[religion]

    @Readonly
    fun getFollowersOfMajorityReligion(): Int {
        val majorityReligion = getMajorityReligionName() ?: return 0
        return followers[majorityReligion]
    }

    @Readonly
    fun getFollowersOfOtherReligionsThan(religion: String): Int {
        return followers.filterNot { it.key == religion }.values.sum()
    }

    /** Removes all pantheons except for the one founded by the current owner of the city
     *  Should be called whenever a city changes hands, e.g. conquering and trading
     */
    fun removeUnknownPantheons() {
        for (pressure in pressures.keys.toList()) {  // Copy the keys because we might modify
            if (pressure == Constants.noReligionName) continue
            val correspondingReligion = city.civ.gameInfo.religions[pressure]!!
            if (correspondingReligion.isPantheon()
                && correspondingReligion.foundingCiv != city.civ
            ) {
                pressures.remove(pressure)
            }
        }
        updateNumberOfFollowers()
    }

    @Readonly
    fun getMajorityReligionName(): String? {
        if (followers.isEmpty()) return null
        val religionWithMaxPressure = followers.maxByOrNull { it.value }!!.key
        return when {
            religionWithMaxPressure == Constants.noReligionName -> null
            followers[religionWithMaxPressure] >= city.population.population / 2 -> religionWithMaxPressure
            else -> null
        }
    }

    @Readonly
    fun getMajorityReligion(): Religion? {
        val majorityReligionName = getMajorityReligionName() ?: return null
        return city.civ.gameInfo.religions[majorityReligionName]
    }

    private fun getAffectedBySurroundingCities() {
        if (!city.civ.gameInfo.isReligionEnabled()) return // No religion, no spreading
        // We don't update the amount of followers yet, as only the end result should matter
        // If multiple religions would become the majority religion due to pressure,
        // this will make it so we only receive a notification for the last one.
        // Also, doing it like this increases performance :D
        if (city.isHolyCity()) {
            addPressure(religionThisIsTheHolyCityOf!!,5 * pressureFromAdjacentCities, false)
        }

        // CvGameReligions::SpreadReligionToOneCity (CvReligionClasses.cpp:334): the spies of a religion with Underground Sect, first
        for ((religionName, times) in getSpyPressureTimes())
            if (pressures.containsKey(religionName)) // the original only adds to a presence that is already there
                addPressure(religionName, times * pressureFromAdjacentCities, false)

        val tradeConnections = getTradeConnections()
        for (otherCity in city.civ.gameInfo.getCities()) {
            if (otherCity == city) continue
            val (religionName, pressure) = getPressureFrom(otherCity, tradeConnections) ?: continue
            addPressure(religionName, pressure, false)
        }

        updateNumberOfFollowers()
    }

    /** For each religion whose founder has a living spy in this city: how many times the natural pressure it adds, by [UniqueType.SpyReligionPressure] */
    @Readonly
    private fun getSpyPressureTimes(): Counter<String> {
        val result = Counter<String>()
        for (spyCiv in city.civ.gameInfo.civilizations) {
            val religion = spyCiv.religionManager.religion?.takeIf { it.isMajorReligion() } ?: continue
            var times = 0
            religion.founderBeliefUniqueMap.forEachMatchingUnique(UniqueType.SpyReligionPressure, spyCiv.state) { times += it.params[0].toInt() }
            if (times > 0 && spyCiv.espionageManager.getSpiesInCity(city).any { it.isAlive() })
                result.add(religion.name, times)
        }
        return result
    }

    /** The cities that a trade route of anyone links this city to, in either direction (CvGameTrade::IsCityConnectedToCity, CvTradeClasses.cpp:661),
     *  each with whether a route starts there and ends here (IsCityConnectedFromCityToCity, 691) */
    @Readonly
    private fun getTradeConnections(): Map<City, Boolean> {
        @LocalState val connections = HashMap<City, Boolean>()
        for (civ in city.civ.gameInfo.civilizations) {
            for (route in civ.tradeRoutes.routes) {
                val origin = route.getOriginCity() ?: continue
                val destination = route.getDestinationCity() ?: continue
                if (destination == city) connections[origin] = true
                else if (origin == city) connections.putIfAbsent(destination, false)
            }
        }
        return connections
    }

    /** The religion that [sender] spreads to this city naturally every turn and how much pressure that is, if any
     *  (CvGameReligions::GetAdjacentCityReligiousPressure, CvReligionClasses.cpp:1662): the sender must be close enough, or be connected by a trade route
     *  in either direction; a city that is both makes the pressure only once */
    @Readonly
    private fun getPressureFrom(sender: City, tradeConnections: Map<City, Boolean>): Pair<String, Int>? {
        val majorityReligion = sender.religion.getMajorityReligion() ?: return null
        if (!majorityReligion.isMajorReligion()) return null
        val tradeConnection = tradeConnections[sender]
        if (tradeConnection == null
            && sender.getCenterTile().aerialDistanceTo(city.getCenterTile()) > sender.religion.getSpreadRange()) return null
        return majorityReligion.name to sender.religion.pressureAmountToAdjacentCities(city, tradeConnection == true)
    }

    @Readonly
    private fun getSpreadRange(): Int {
        var spreadRange = 10

        city.forEachMatchingUnique(UniqueType.ReligionSpreadDistance) { unique ->
            spreadRange += unique.params[0].toInt()
        }

        val majorityReligion = getMajorityReligion()
        if (majorityReligion != null) {
            majorityReligion.foundingCiv.forEachMatchingUnique(UniqueType.ReligionSpreadDistance) { unique ->
                spreadRange += unique.params[0].toInt()
            }
        }

        return spreadRange
    }

    /** Doesn't update the pressures, only returns what they are if the update were to happen right now */
    @Readonly
    fun getPressuresFromSurroundingCities(): Counter<String> {
        val addedPressure = Counter<String>()
        if (city.isHolyCity()) {
            addedPressure[religionThisIsTheHolyCityOf!!] = 5 * pressureFromAdjacentCities
        }
        for ((religionName, times) in getSpyPressureTimes())
            if (pressures.containsKey(religionName))
                addedPressure.add(religionName, times * pressureFromAdjacentCities)
        val tradeConnections = getTradeConnections()
        for (otherCity in city.civ.gameInfo.getCities()) {
            if (otherCity == city) continue
            val (religionName, pressure) = getPressureFrom(otherCity, tradeConnections) ?: continue
            addedPressure.add(religionName, pressure)
        }
        return addedPressure
    }

    @Readonly
    fun isProtectedByInquisitor(fromReligion: String? = null): Boolean {
        for (tile in city.getCenterTile().getTilesInDistance(1)) {
            for (unit in listOf(tile.civilianUnit, tile.militaryUnit)) {
                if (unit?.religion != null
                    && (fromReligion == null || unit.religion != fromReligion)
                    && unit.hasUnique(UniqueType.PreventSpreadingReligion)
                ) return true
            }
        }
        return false
    }

    @Readonly
    private fun pressureAmountToAdjacentCities(pressuredCity: City, viaTradeRouteFromHere: Boolean = false): Int {
        var pressure = pressureFromAdjacentCities.toFloat()

        // A trade route that starts here (CvReligionClasses.cpp:1705): the trait of the civilization, Arabia
        if (viaTradeRouteFromHere)
            city.civ.forEachMatchingUnique(UniqueType.TradeRouteReligionPressure) { unique ->
                pressure *= unique.params[0].toPercent()
            }

        // Follower beliefs of this religion
        city.forEachMatchingUnique(UniqueType.NaturalReligionSpreadStrength) { unique ->
            if (pressuredCity.matchesFilter(unique.params[1]))
                pressure *= unique.params[0].toPercent()
        }

        // Founder beliefs of this religion
        val majorityReligion = getMajorityReligion()
        if (majorityReligion != null) {
            majorityReligion.foundingCiv.forEachMatchingUnique(UniqueType.NaturalReligionSpreadStrength) { unique ->
                if (pressuredCity.matchesFilter(unique.params[1]))
                    pressure *= unique.params[0].toPercent()
            }
            // World Religion (LR 4.3.10): the pressure of the chosen religion is stronger
            val spreadPercent = city.civ.gameInfo.league?.getReligionSpreadPercent(majorityReligion.name) ?: 0
            if (spreadPercent != 0) pressure *= (100 + spreadPercent) / 100f
        }

        return pressure.toInt()
    }

    /** Calculates how much pressure this religion is lacking compared to the majority religion
     * That is, if we gain more than this, we'll be the majority */
    @Readonly
    fun getPressureDeficit(otherReligion: String?): Int {
        val pressures = getPressures()
        return (pressures[getMajorityReligionName()] ?: 0) - (pressures[otherReligion] ?: 0)
    }
}
