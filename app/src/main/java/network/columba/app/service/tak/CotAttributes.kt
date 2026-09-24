package network.columba.app.service.tak

/**
 * One attribute of a CoT start tag, set without disturbing the rest.
 *
 * Walks the tag's attributes in order rather than searching its text, so an
 * attribute name appearing inside another attribute's value is never the one
 * rewritten, and puts the value in escaped and literal. The Kotlin half of
 * `_set_attr` in `tools/tak_files.py` (PR D2 review, note 2).
 */
object CotAttributes {
    private val ATTRIBUTE = Regex("""\s+([A-Za-z_][\w.:-]*)\s*=\s*("[^"]*"|'[^']*')""")

    /** A start tag with [name] set to [value], escaped; added when absent. */
    fun set(tag: String, name: String, value: String): String {
        val head = Regex("^<[A-Za-z_][\\w.:-]*").find(tag) ?: throw IllegalArgumentException("not a start tag")
        val out = StringBuilder(head.value)
        var at = head.range.last + 1
        var found = false
        for (attribute in ATTRIBUTE.findAll(tag, at)) {
            if (attribute.range.first != at) break
            if (attribute.groupValues[1] == name && !found) {
                out.append(" $name=\"${escape(value)}\"")
                found = true
            } else {
                out.append(attribute.value)
            }
            at = attribute.range.last + 1
        }
        if (!found) out.append(" $name=\"${escape(value)}\"")
        return out.append(tag.substring(at)).toString()
    }

    /** A value safe inside a quoted XML attribute, either quote. */
    fun escape(text: String) =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
}
