package com.napreth.set4u.core.localization

import java.text.Normalizer
import java.util.Collections
import java.util.Locale

sealed interface UiText {
    data class Key(val id: String) : UiText { init { require(id.isNotBlank()) } }
    /** Runtime data, such as an SSID, is never harvested as a translation. */
    data class Literal(val value: String) : UiText
}

class TextResources(translations: Map<String, Map<String, String>>, val defaultLocale: String = "zh-CN") {
    val translations: Map<String, Map<String, String>> = Collections.unmodifiableMap(translations.mapValues { (_, values) -> Collections.unmodifiableMap(LinkedHashMap(values)) })
    fun resolve(text: UiText, locale: String = defaultLocale): String = when (text) {
        is UiText.Literal -> text.value
        is UiText.Key -> translations[locale]?.get(text.id)
            ?: translations.entries.firstOrNull { it.key.substringBefore('-') == locale.substringBefore('-') }?.value?.get(text.id)
            ?: translations[defaultLocale]?.get(text.id)
            ?: error("Missing text resource: ${text.id}")
    }
    fun allTranslations(text: UiText): Set<String> = when (text) {
        is UiText.Literal -> setOf(text.value)
        is UiText.Key -> translations.values.mapNotNull { it[text.id] }.toSet().also { require(it.isNotEmpty()) { "Missing text resource: ${text.id}" } }
    }
    companion object { val Empty = TextResources(emptyMap()) }
}

internal fun searchText(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
