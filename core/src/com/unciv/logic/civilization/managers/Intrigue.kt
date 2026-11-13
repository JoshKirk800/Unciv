package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.ArmyType
import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.HexCoord
import com.unciv.models.Spy
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.random.Random

/** CvIntrigueType: what a spy uncovered */
enum class IntrigueType {
    /** A civ is lying to another civ about its intentions */
    Deception,
    /** A civ is amassing an army */
    BuildingArmy,
    /** A civ is amassing an army to attack over the water */
    BuildingAmphibiousArmy,
    /** A civ is sending an army toward another civ */
    ArmySneakAttack,
    /** A civ is sending a land invasion across the water toward another civ */
    AmphibiousSneakAttack,
    /** A civ is constructing a wonder or project */
    ConstructingWonder,
}

/**
 *  IntrigueNotificationMessage: one piece of intrigue a civilization knows, uncovered by its own spy or told to it by an AI
 *  (the list is [EspionageManager.intrigueMessages], CvPlayerEspionage::m_aIntrigueNotificationMessages).
 *  The civs are civIDs.
 */
class IntrigueMessage() : IsPartOfGameInfoSerialization {
    /** m_eDiscoveringPlayer: who found it out - the owner of the list, or the AI that told the owner */
    var discoverer = ""
    /** m_eSourcePlayer: who is plotting */
    var source = ""
    /** m_eTargetPlayer: who is plotted against, null = NO_PLAYER (not known to the discoverer) */
    var target: String? = null
    /** m_eTargetPlayer == MAX_MAJOR_CIVS: the spy was too low in rank to be told the target */
    var targetHidden = false
    /** m_eBuilding / m_eProject: the wonder or project being built, by name */
    var construction: String? = null
    var type = IntrigueType.Deception
    /** m_iCityX / m_iCityY: the city the intrigue is about, null = none or not known */
    var cityLocation: HexCoord? = null
    /** m_iTurnNum */
    var turn = 0
    /** m_strSpyName: the name of the spy of the discoverer, empty when told by someone else */
    var spyName = ""
    /** m_bShared */
    var shared = false

    fun clone(): IntrigueMessage {
        val copy = IntrigueMessage()
        copy.discoverer = discoverer; copy.source = source; copy.target = target; copy.targetHidden = targetHidden
        copy.construction = construction; copy.type = type; copy.cityLocation = cityLocation
        copy.turn = turn; copy.spyName = spyName; copy.shared = shared
        return copy
    }
}

/**
 *  What a spy uncovers in a foreign major civilization's city: CvPlayerEspionage::UncoverIntrigue and the notifications of
 *  CvPlayerEspionage::AddIntrigueMessage. The messages are kept by [EspionageManager]; the AI tells them to others in
 *  [com.unciv.logic.automation.diplomacy.IntrigueAi].
 */
object IntrigueFunctions {
    /** The ranks of CvSpyRank as the DLL's rank plus the influence bonus, in Unciv's ranks (the first is 1): SPY_RANK_RECRUIT, SPY_RANK_AGENT */
    private const val RECRUIT_RANK = 1
    private const val AGENT_RANK = 2

    /**
     *  CvPlayerEspionage::UncoverIntrigue, called where the DLL's ProcessSpy calls it: a spy that has just established its network or sits
     *  in surveillance, one that completed a theft and survived, and a diplomat schmoozing. In the city of a civ that is not
     *  human, in this order and each only if the one before it found nothing to stop at:
     *  - the first civ (in a random order) that the owner has a sneak attack operation against: an army marching, the spy's rank decides whether
     *    the target civ and city are revealed (Recruits learn no target; the target must also be a civ the spy's owner has met);
     *  - an army being built (land or naval invasion);
     *  - the first civ (same order) that the owner is friendly or neutral to on the surface but deceptive or at war with in truth,
     *    unless already at war;
     *  and for every owner, a world wonder or project in production.
     */
    fun uncover(spy: Spy) {
        val city = spy.getCityOrNull() ?: return
        val civ = spy.civInfo
        val owner = city.civ
        if (owner == civ || !owner.isMajorCiv) return
        val gameInfo = civ.gameInfo

        val majors = gameInfo.civilizations.filter { it.isMajorCiv && it.isAlive() }.toMutableList()
        val random = Random(city.location.x * 31 + city.location.y + 123 * gameInfo.turns + spy.name.hashCode())
        for (i in majors.indices) { // the DLL's shuffle: swap each slot with a random one
            val other = random.nextInt(majors.size)
            val temp = majors[i]
            majors[i] = majors[other]
            majors[other] = temp
        }

        if (!owner.isHuman()) {
            val spyRank = spy.getEffectiveRank()
            for (target in majors) {
                if (target == owner) continue
                val operation = MilitaryOperations.sneakAttackOperation(owner, target) ?: continue
                val targetCity = if (spyRank >= AGENT_RANK) operation.targetTile
                    ?.let { gameInfo.tileMap[it] }?.takeIf { it.isCityCenter() }?.getCity() else null
                val hidden = spyRank <= RECRUIT_RANK
                val revealed = if (!hidden && (target == civ || civ.knows(target))) target else null
                val type = when (operation.type) {
                    AiOperationType.SNEAK_CITY_ATTACK -> IntrigueType.ArmySneakAttack
                    AiOperationType.NAVAL_SNEAK_ATTACK -> IntrigueType.AmphibiousSneakAttack
                    else -> null
                }
                if (type != null) civ.espionageManager.addIntrigueMessage(
                    civ, owner, revealed, hidden, null, type, targetCity, notify = true, spy = spy)
                break // the DLL reports one operation only
            }

            when (owner.ai.military.armyTypeBeingBuilt) {
                ArmyType.Land -> civ.espionageManager.addIntrigueMessage(
                    civ, owner, null, false, null, IntrigueType.BuildingArmy, city, notify = true, spy = spy)
                ArmyType.NavalInvasion -> civ.espionageManager.addIntrigueMessage(
                    civ, owner, null, false, null, IntrigueType.BuildingAmphibiousArmy, city, notify = true, spy = spy)
                null -> {}
            }

            for (other in majors) {
                if (other == owner) continue
                val honest = owner.ai.diplomacy.approachTowards(other, hideTrueFeelings = false)
                val surface = owner.ai.diplomacy.approachTowards(other, hideTrueFeelings = true)
                if (honest != MajorCivApproach.Deceptive && honest != MajorCivApproach.War) continue
                if (surface != MajorCivApproach.Friendly && surface != MajorCivApproach.Neutral) continue
                if (owner.isAtWarWith(other)) continue // already at war: not notable
                val known = other == civ || civ.knows(other)
                civ.espionageManager.addIntrigueMessage(
                    civ, owner, if (known) other else null, false, null, IntrigueType.Deception, city, notify = true, spy = spy)
                break // intrigue reported
            }
        }

        if (isWonderOrProject(city)) civ.espionageManager.addIntrigueMessage(
            civ, owner, null, false, city.cityConstructions.currentConstructionName(), IntrigueType.ConstructingWonder, city, notify = true, spy = spy)
    }

    /**
     *  A world wonder or a project in production. The DLL notifies about every project and every building of a world wonder class;
     *  Unciv's projects are the two national wonders that trigger a global alert (the Manhattan Project and the Apollo Program) and the
     *  spaceship parts, which are units.
     */
    private fun isWonderOrProject(city: City): Boolean {
        val ruleset = city.getRuleset()
        val name = city.cityConstructions.currentConstructionName()
        ruleset.buildings[name]?.let { building ->
            return building.isWonder || building.isNationalWonder && building.hasUnique(UniqueType.TriggersAlertOnCompletion)
        }
        return ruleset.units[name]?.hasUnique(UniqueType.SpaceshipPart) == true
    }

    /** The text of the notification a spy's own find causes: AddIntrigueMessage with bShowNotification, in the texts of the original's XML */
    fun notificationText(civ: Civilization, message: IntrigueMessage): String {
        val gameInfo = civ.gameInfo
        val spy = message.spyName
        fun civName(id: String) = gameInfo.getCivilizationOrNull(id)?.civName ?: id
        val source = civName(message.source)
        val city = message.cityLocation?.let { gameInfo.tileMap[it].getCity() }
        val cityName = city?.name
        val targetId = message.target
        val target = targetId?.let { civName(it) }
        return when (message.type) {
            IntrigueType.Deception -> when (targetId) {
                civ.civID -> "[$spy] in [$cityName] uncovered that [$source] is secretly plotting against you!"
                null -> "[$spy] in [$cityName] uncovered that [$source] is secretly plotting against an unmet civilization."
                else -> "[$spy] in [$cityName] uncovered that [$source] is secretly plotting against [$target]!"
            }
            IntrigueType.BuildingArmy ->
                "[$spy] uncovered that [$source] is building up an army for a sneak attack against another civilization."
            IntrigueType.BuildingAmphibiousArmy ->
                "[$spy] uncovered that [$source] is building up an army with naval escorts for a sneak attack against another civilization."
            IntrigueType.ArmySneakAttack -> when {
                targetId == civ.civID && cityName != null ->
                    "[$spy] discovered that an army of [$source] is currently marching toward your city of [$cityName] to launch a surprise attack!"
                targetId == civ.civID ->
                    "[$spy] discovered that an army of [$source] is currently marching toward you to launch a surprise attack!"
                target != null && cityName != null ->
                    "[$spy] discovered that an army of [$source] is currently marching toward [$cityName], a city of [$target], to launch a surprise attack."
                target != null ->
                    "[$spy] discovered that an army of [$source] is currently marching toward [$target] to launch a surprise attack."
                message.targetHidden ->
                    "[$spy] discovered that an army of [$source] is currently marching a surprise attack against another civilization. We do not know which civ they are targeting."
                else ->
                    "[$spy] discovered that an army of [$source] is currently marching toward a civilization you have not met to launch a surprise attack."
            }
            IntrigueType.AmphibiousSneakAttack -> when {
                targetId == civ.civID && cityName != null ->
                    "[$spy] discovered that an army of [$source] with naval escorts is currently sailing toward your city of [$cityName] to launch a surprise attack!"
                targetId == civ.civID ->
                    "[$spy] discovered that an army of [$source] with naval escorts is currently sailing toward you to launch a surprise attack!"
                target != null && cityName != null ->
                    "[$spy] discovered that an army of [$source] with naval escorts is currently sailing toward [$cityName], a city of [$target], to launch a surprise attack."
                target != null ->
                    "[$spy] discovered that an army of [$source] with naval escorts is currently sailing toward [$target] to launch a surprise attack."
                message.targetHidden ->
                    "[$spy] discovered that an army of [$source] with naval escorts has set sail for a sneak attack. We do not know which civ they are targeting."
                else ->
                    "[$spy] discovered that an army of [$source] with naval escorts is currently sailing toward a civilization you have not met to launch a sneak attack."
            }
            IntrigueType.ConstructingWonder ->
                "[$spy] discovered that [$source] is constructing [${message.construction}] in [$cityName]."
        }
    }

    /** What a click on the notification does: show the city, and for intrigue about another AI civ the way to warn it */
    fun notificationActions(civ: Civilization, message: IntrigueMessage): List<NotificationAction> {
        val actions = ArrayList<NotificationAction>()
        message.cityLocation?.let { actions.add(LocationAction(it)) }
        val target = message.target?.let { civ.gameInfo.getCivilizationOrNull(it) }
        if (target != null && target != civ && !target.isHuman()) actions.add(DiplomacyAction(target))
        return actions
    }

    fun notify(civ: Civilization, message: IntrigueMessage) {
        if (!civ.isHuman()) return
        val icons = ArrayList<String>()
        icons.add(NotificationIcon.Spy)
        icons.add(civ.gameInfo.getCivilizationOrNull(message.source)?.civName ?: message.source)
        civ.addNotification(notificationText(civ, message), notificationActions(civ, message), NotificationCategory.Espionage, *icons.toTypedArray())
    }
}
