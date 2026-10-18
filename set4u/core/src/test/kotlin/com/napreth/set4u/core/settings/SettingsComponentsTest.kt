package com.napreth.set4u.core.settings

import com.napreth.set4u.core.*
import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.presentation.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class SettingsComponentsTest {
    @Test fun settingsWithoutEntriesRemainAvailableToBusinessCalls() = runTest {
        val definition = flagDefinition()
        val catalog = SettingsCatalog(emptyMap(), definitions = listOf(definition))
        assertTrue(catalog.entries.forSetting(definition.id).isEmpty())
        assertTrue(catalog.search.search(definition.id).isEmpty())
        withSession(catalog = catalog) { session ->
            session.settings.set(Target, definition, Enabled).await()
            assertEquals(Enabled, session.settings.read(Target, definition).snapshot.value)
        }
    }
    @Test fun oneCanonicalEntryIsSharedByDifferentPagePresentations() = runTest {
        val definition = flagDefinition()
        val entry = entryOf(definition)
        val catalog = SettingsCatalog(testPage("a", "A", listOf(entry)), testPage("b", "B", listOf(entry)))
        assertSame(entry, catalog.entries[definition.id])
        assertEquals(listOf(entry), catalog.describe("a", Target).entries)
        assertEquals(listOf(entry), catalog.describe("b", Target).entries)
    }
    @Test fun differentEntryIdsCanPresentTheSameSettingOnDifferentPages() = runTest {
        val definition = flagDefinition()
        val first = SettingEntry(definition, UiText.Literal("First"), id = "first")
        val second = SettingEntry(definition, UiText.Literal("Second"), id = "second")
        val catalog = SettingsCatalog(testPage("a", "A", listOf(first)), testPage("b", "B", listOf(second)))
        assertEquals(listOf(first, second), catalog.entries.forSetting(definition.id))
        assertEquals(listOf(first), catalog.describe("a", Target).entries)
        assertEquals(listOf(second), catalog.describe("b", Target).entries)
    }
    @Test fun entryIdsAreUniqueEvenWhenTheyReferenceTheSameSetting() = runTest {
        val definition = flagDefinition()
        expectFailure<IllegalArgumentException> { SettingsCatalog(testPage("a", "A", listOf(entryOf(definition))), testPage("b", "B", listOf(SettingEntry(definition, UiText.Literal("Other"))))) }
    }
    @Test fun differentEntriesCannotReplaceTheCanonicalSettingImplementation() = runTest {
        val definition = flagDefinition()
        val first = SettingEntry(definition, UiText.Literal("First"), id = "first")
        val second = SettingEntry(definition.copy(binding = FlagHandler()), UiText.Literal("Second"), id = "second")
        expectFailure<IllegalArgumentException> { SettingsCatalog(testPage("a", "A", listOf(first, second))) }
    }
    @Test fun searchKeepsSeparateEntryDestinationsForOneSetting() {
        val definition = flagDefinition()
        val first = SettingEntry(definition, UiText.Literal("Language options"), id = "language.options")
        val second = SettingEntry(definition, UiText.Literal("Language summary"), id = "language.summary")
        val catalog = SettingsCatalog(testPage("a", "A", listOf(first)), testPage("b", "B", listOf(second)))
        val results = catalog.search.search("language").associateBy { it.entryId }
        assertEquals(setOf(first.id, second.id), results.keys)
        assertEquals(setOf(definition.id), results.values.map { it.settingId }.toSet())
        assertEquals(setOf("a"), results.getValue(first.id).pageIds)
        assertEquals(setOf("b"), results.getValue(second.id).pageIds)
    }
    @Test fun entrySnapshotsFreezeBusinessListsAndTranslationTables() {
        val choices = mutableListOf("one")
        val labels = mutableMapOf("title" to "One")
        val entry = SettingEntry(SettingDefinition("choice", ValueType.Choice, FlagHandler(), choices = choices), UiText.Key("title"), choices = listOf(EntryChoice("one", UiText.Key("title"))))
        val resources = TextResources(mapOf("en" to labels), "en")
        choices.clear(); labels.clear()
        assertEquals(listOf("one"), entry.setting.choices)
        assertEquals("One", resources.resolve(entry.title))
    }
    @Test fun missingAndExtraChoiceLabelsAreRejected() = runTest {
        val definition = SettingDefinition("choice", ValueType.Choice, FlagHandler(), choices = listOf("one"))
        expectFailure<IllegalArgumentException> { SettingEntry(definition, UiText.Literal("Title")) }
        expectFailure<IllegalArgumentException> { SettingEntry(definition, UiText.Literal("Title"), choices = listOf(EntryChoice("other", UiText.Literal("Other")))) }
    }
    @Test fun boundsAndChoiceValidationBelongToTheBusinessDefinition() {
        val integer = SettingDefinition("timeout", ValueType.Integer, FlagHandler(), minimum = 1, maximum = 60)
        assertNull(integer.validationError(SettingValue.IntegerValue(30)))
        assertNotNull(integer.validationError(SettingValue.IntegerValue(0)))
        assertNotNull(integer.validationError(SettingValue.IntegerValue(61)))
        assertNotNull(integer.validationError(SettingValue.TextValue("30")))
        val single = SettingDefinition("single", ValueType.Choice, FlagHandler(), choices = listOf("default"))
        val multiple = SettingDefinition("multiple", ValueType.MultiChoice, FlagHandler(), choices = listOf("default"))
        assertNull(single.validationError(SettingValue.ChoiceValue("default")))
        assertNotNull(single.validationError(SettingValue.ChoiceValue("missing")))
        assertNull(multiple.validationError(SettingValue.MultiChoiceValue(emptySet())))
        assertNotNull(multiple.validationError(SettingValue.MultiChoiceValue(setOf("missing"))))
    }
    @Test fun parameterizedActionsAreValidatedAndDoNotPrintTheirValues() {
        val definition = SettingDefinition("wifi.connect", ValueType.Action, FlagHandler(), actionParameters = mapOf("network" to ActionParameter(maxLength = 100)))
        val input = mutableMapOf("network" to "sensitive-ssid")
        val value = SettingValue.ActionValue(input)
        input.clear()
        assertNull(definition.validationError(value))
        assertFalse(value.toString().contains("sensitive-ssid"))
        assertNotNull(definition.validationError(SettingValue.Action))
        assertNotNull(definition.validationError(SettingValue.ActionValue(mapOf("unknown" to "x"))))
    }
    @Test fun multilingualSearchUsesTheEntryAndNotRuntimeRows() {
        val definition = flagDefinition()
        val texts = TextResources(mapOf("zh-CN" to mapOf("language" to "语言"), "en" to mapOf("language" to "Language")))
        val entry = SettingEntry(definition, UiText.Key("language"))
        val catalog = SettingsCatalog(testPage("language", "Language", listOf(entry)), texts = texts)
        val hit = catalog.search.search("language", "zh-CN").single()
        assertEquals("语言", hit.title)
        assertEquals(definition.id, hit.entryId)
        assertEquals(setOf("language"), hit.pageIds)
    }
    @Test fun missingTranslationsFailBeforeOpeningAPage() = runTest {
        val entry = SettingEntry(flagDefinition(), UiText.Key("missing"))
        expectFailure<IllegalArgumentException> { SettingsCatalog(testPage("sample", "Sample", listOf(entry))) }
    }
    @Test fun selectionValuesDoNotRetainMutableInput() {
        val source = mutableSetOf("one")
        val value = SettingValue.MultiChoiceValue(source)
        source.add("two")
        assertEquals(setOf("one"), value.ids)
        assertEquals(SettingValue.MultiChoiceValue(setOf("one")), value)
    }
}
