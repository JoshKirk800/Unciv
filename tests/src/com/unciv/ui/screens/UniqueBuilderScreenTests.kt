package com.unciv.ui.screens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import com.unciv.ui.components.input.ActivationListener
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real unique builder widgets. Aggregate diagnostics are the labels under "Whole unique:".
 */
@RunWith(GdxTestRunner::class)
class UniqueBuilderScreenTests {
    private lateinit var screen: UniqueBuilderScreen

    @Before
    fun setUp() {
        val game = TestGame()
        UiTestSupport.prepare(game)
        screen = UniqueBuilderScreen(game.ruleset)
    }

    @After
    fun tearDown() {
        if (::screen.isInitialized) screen.dispose()
    }

    @Test
    fun builderInitializesWithWholeUniqueStatus() {
        val labels = wholeUniqueLabels()
        Assert.assertTrue(labels.any { it == "Whole unique:" })
        Assert.assertTrue(labels.any { it == "Errors:" })
    }

    @Test
    fun reportedWorkerModifiersShowIncompatibility() {
        selectMain(UniqueTarget.Unit, UniqueType.BuildImprovements, "Can build [Land] improvements on tiles")
        addModifier(UniqueTarget.UnitActionModifier, UniqueType.UnitActionConsumeUnit, "by consuming this unit")
        addModifier(UniqueTarget.UnitActionModifier, UniqueType.UnitActionLimitedTimes, "[3] times")

        Assert.assertEquals(
            "Can build [Land] improvements on tiles <by consuming this unit> <[3] times>",
            screen.descriptionLabel.text.toString()
        )
        // Error labels go through tr(), which drops placeholder brackets: "[3]" renders as "3".
        Assert.assertEquals(listOf("by consuming this unit", "3 times"), unacceptableModifiers())
        Assert.assertEquals("Copy to clipboard", screen.rightSideButton.text.toString())
        Assert.assertTrue(screen.rightSideButton.isVisible)
        Assert.assertFalse(screen.rightSideButton.isDisabled)
    }

    @Test
    fun aggregateErrorsClearAndReturnWithoutStaleLabels() {
        selectMain(UniqueTarget.Unit, UniqueType.BuildImprovements, "Can build [Land] improvements on tiles")
        addModifier(UniqueTarget.UnitActionModifier, UniqueType.UnitActionConsumeUnit, "by consuming this unit")
        addModifier(UniqueTarget.UnitActionModifier, UniqueType.UnitActionLimitedTimes, "[3] times")
        Assert.assertEquals(listOf("by consuming this unit", "3 times"), unacceptableModifiers())

        selectMain(
            UniqueTarget.Unit,
            UniqueType.ConstructImprovementInstantly,
            "Can instantly construct a [Farm] improvement"
        )
        Assert.assertTrue(unacceptableModifiers().isEmpty())
        Assert.assertTrue(wholeUniqueLabels().any { it == "No errors!" })

        selectMain(UniqueTarget.Unit, UniqueType.BuildImprovements, "Can build [Land] improvements on tiles")
        Assert.assertEquals(listOf("by consuming this unit", "3 times"), unacceptableModifiers())
        Assert.assertFalse(wholeUniqueLabels().any { it == "No errors!" })
    }

    @Test
    fun unknownModifierIsReportedOnTheAssembledUnique() {
        selectMain(UniqueTarget.Unit, UniqueType.BuildImprovements, "Can build [Land] improvements on tiles")
        addModifier(UniqueTarget.UnitActionModifier, UniqueType.UnitActionConsumeUnit, "not a real modifier")

        val labels = wholeUniqueLabels()
        Assert.assertTrue(labels.toString(), labels.any { it.contains("unknown type") && it.contains("not a real modifier") })
    }

    @Test
    fun targetChangeRevalidatesTimedStrengthUnique() {
        val text = "[-5]% Strength <for [3] turns> <upon damaging a [Warrior] unit>"
        selectMain(UniqueTarget.Promotion, UniqueType.Strength, "[-5]% Strength")
        addModifier(UniqueTarget.MetaModifier, UniqueType.ConditionalTimedUnique, "for [3] turns")
        addModifier(UniqueTarget.UnitTriggerCondition, UniqueType.TriggerUponDamagingUnit, "upon damaging a [Warrior] unit")
        Assert.assertEquals(text, screen.descriptionLabel.text.toString())
        Assert.assertTrue(unacceptableModifiers().isEmpty())
        Assert.assertTrue(wholeUniqueLabels().any { it == "No errors!" })

        val mainText = uniqueTextFields().first()
        val selectedUnique = uniqueBoxes().first().selected
        targetBoxes().first().selected = UniqueTarget.Global
        Assert.assertEquals(selectedUnique, uniqueBoxes().first().selected)
        Assert.assertEquals("[-5]% Strength", mainText.text)
        Assert.assertEquals(text, screen.descriptionLabel.text.toString())
        val warned = unacceptableModifiers()
        Assert.assertEquals(warned.toString(), 1, warned.size)
        Assert.assertTrue(warned.single(), warned.single().contains("damaging") && warned.single().contains("Warrior"))

        targetBoxes().first().selected = UniqueTarget.Unit
        Assert.assertEquals(selectedUnique, uniqueBoxes().first().selected)
        Assert.assertEquals("[-5]% Strength", mainText.text)
        Assert.assertEquals(text, screen.descriptionLabel.text.toString())
        Assert.assertTrue(unacceptableModifiers().isEmpty())
        Assert.assertTrue(wholeUniqueLabels().any { it == "No errors!" })
    }

    private fun selectMain(target: UniqueTarget, type: UniqueType, text: String) {
        select(targetBoxes().first(), target)
        select(uniqueBoxes().first(), type)
        setUniqueText(uniqueTextFields().first(), text)
    }

    private fun addModifier(target: UniqueTarget, type: UniqueType, text: String) {
        val button = actors().filterIsInstance<TextButton>().first { it.text.toString() == "Add Modifier" }
        val listener = button.listeners.first { it is ActivationListener } as ActivationListener
        val event = InputEvent()
        event.listenerActor = button
        listener.tap(event, 0f, 0f, 1, 0)

        select(targetBoxes().last(), target)
        select(uniqueBoxes().last(), type)
        setUniqueText(uniqueTextFields().last(), text)
    }

    /** TextField.setText does not notify listeners. Typing does, and that is what refreshes the builder. */
    private fun setUniqueText(field: TextField, text: String) {
        field.text = text
        field.fire(ChangeListener.ChangeEvent())
    }

    private fun <T> select(box: SelectBox<T>, item: T) {
        Assert.assertTrue("$item not in ${box.items}", containsItem(box, item))
        box.selected = item
        Assert.assertEquals(item, box.selected)
    }

    private fun <T> containsItem(box: SelectBox<T>, item: T): Boolean {
        val items = box.items
        for (index in 0 until items.size)
            if (items[index] == item) return true
        return false
    }

    /** Quoted modifier names from the assembled-unique table, in display order. */
    private fun unacceptableModifiers(): List<String> {
        val found = ArrayList<String>()
        for (label in wholeUniqueLabels()) {
            if (!label.contains("not an acceptable modifier")) continue
            val marker = "contains the modifier \""
            val start = label.indexOf(marker)
            Assert.assertTrue(label, start >= 0)
            val nameStart = start + marker.length
            found.add(label.substring(nameStart, label.indexOf('"', nameStart)))
        }
        return found
    }

    private fun wholeUniqueLabels(): List<String> {
        val header = actors().filterIsInstance<Label>().first { it.text.toString() == "Whole unique:" }
        val table = header.parent
        val labels = ArrayList<String>()
        for (child in table.children)
            if (child is Label) labels.add(child.text.toString())
        return labels
    }

    private fun targetBoxes() = selectBoxes<UniqueTarget>()
    private fun uniqueBoxes() = selectBoxes<UniqueType>()

    private inline fun <reified T> selectBoxes(): List<SelectBox<T>> {
        val boxes = ArrayList<SelectBox<T>>()
        for (actor in actors()) {
            if (actor !is SelectBox<*>) continue
            if (actor.items.size == 0 || actor.items.first() !is T) continue
            @Suppress("UNCHECKED_CAST")
            boxes.add(actor as SelectBox<T>)
        }
        return boxes
    }

    private fun uniqueTextFields(): List<TextField> {
        val fields = ArrayList<TextField>()
        for (actor in actors())
            if (actor is TextField && actor.messageText != "Search") fields.add(actor)
        return fields
    }

    private fun actors(): List<Actor> = UiTestSupport.actors(screen.stage)
}
