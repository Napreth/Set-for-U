package com.napreth.set4u.core.metadata

import com.napreth.set4u.core.localization.*
import com.napreth.set4u.core.settings.network.ApplicationSettings
import com.napreth.set4u.core.settings.presentation.searchDocument
import java.io.File
import java.security.MessageDigest

/** Build entry point. It reads declared metadata, without opening a session or collecting dynamic sources. */
object UiMetadataExporter {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val catalog = ApplicationSettings.catalog()
        val directory = File(args[0], "metadata").apply { mkdirs() }
        val entries = catalog.entries.all().map { entry -> mapOf(
            "id" to entry.id, "settingId" to entry.setting.id,
            "title" to (entry.title as? UiText.Key)?.id,
            "pages" to catalog.search.destinationsFor(entry.id).sorted(),
            "resources" to entry.textReferences().filterIsInstance<UiText.Key>().map { it.id }.distinct(),
            "terms" to searchDocument(entry, catalog.texts),
        ) }
        File(directory, "texts.json").writeText(json(catalog.texts.translations), Charsets.UTF_8)
        File(directory, "settings-index.json").writeText(json(mapOf("schema" to 1, "version" to args[1], "entries" to entries)), Charsets.UTF_8)
        val properties = catalog.entries.all().joinToString("\n") { entry ->
            val document = searchDocument(entry, catalog.texts)
            "${entry.id}.hash=${fingerprint(document)}\n${entry.id}.terms=${property(document)}"
        }
        File(directory, "settings-search.properties").writeText(properties + "\n", Charsets.UTF_8)
        println("UI metadata: ${entries.size} entries, ${catalog.texts.translations.size} languages")
    }
    private fun property(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        else -> buildString {
            append('"')
            value.toString().forEach { char -> when (char) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
            } }
            append('"')
        }
    }
}
internal fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
