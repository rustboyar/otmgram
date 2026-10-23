package dev.otmgram.testing

import dev.otmgram.R
import dev.otmgram.core.Strings
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** [Strings] read from the app's resource files, so tests check the English the app shows. */
object TestStrings : Strings {
    private val names: Map<Int, String> by lazy {
        (R.string::class.java.fields + R.plurals::class.java.fields).associate { it.getInt(null) to it.name }
    }
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        // Unit tests run in the module's directory.
        File("src/main/res/values").listFiles { file -> file.name.startsWith("strings") }!!.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            root.children("string").forEach { strings[it.getAttribute("name")] = unescape(it.textContent) }
            root.children("plurals").forEach { plural ->
                plurals[plural.getAttribute("name")] = plural.children("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
    }

    override fun get(id: Int, vararg args: Any): String {
        val text = strings.getValue(names.getValue(id))
        return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
    }

    /** English plurals: "one" for 1, "other" for everything else. */
    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val forms = plurals.getValue(names.getValue(id))
        val text = forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
        return String.format(Locale.US, text, *args)
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    /** Android's escapes, as aapt reads them. */
    private fun unescape(text: String) = text
        .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
