package network.columba.app.service.tak

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reading and rewriting a single CoT event.
 *
 * The Kotlin half of the event handling in `tools/cot_endpoint.py`. This input
 * arrives from a socket and from the mesh, so every entry point treats it as
 * hostile and every failure leaves as IllegalArgumentException.
 */
object CotEvent {
    /**
     * True when this event is the endpoint's own, echoed back.
     *
     * ATAK and the mesh both rebroadcast, so an endpoint that forwarded
     * everything it received would feed its own position back into the mesh
     * and grow a loop that looks exactly like a busy network.
     */
    fun isSelfAddressed(cotXml: String, ownUid: String?): Boolean {
        if (ownUid.isNullOrEmpty()) return false
        // Only the root start tag counts. A marker's <creator uid="..."> names
        // whoever dropped it, and treating that as our own would silently stop
        // us forwarding their markers.
        val end = startTagEnd(cotXml)
        val head = if (end < 0) cotXml else cotXml.substring(0, end)
        val span = attributeValueSpan(head, "uid") ?: return false
        return head.substring(span.first, span.second) == ownUid
    }

    /**
     * Give our own self-reports a Reticulum-rooted UID before they leave.
     *
     * ATAK reports itself as ANDROID-xxxx, which is a device identifier: it
     * cannot be verified, cannot be reversed to address the peer, and changes
     * if the app's data is cleared. Peers should see the UID derived from this
     * node's destination instead -- pivot 1 of TAKIntegrationPivots.md.
     *
     * Only *self-reports* are rewritten. An object placed on the map carries
     * its own UID and is a distinct thing that happens to have been created
     * here; rewriting those would collapse every marker this node ever dropped
     * into one track.
     */
    fun rewriteSelfUid(cotXml: String, atakUid: String?, ourUid: String?): String {
        if (atakUid.isNullOrEmpty() || ourUid.isNullOrEmpty()) return cotXml
        val event = parse(cotXml)
        if (event.getAttribute("uid") != atakUid) return cotXml
        val end = startTagEnd(cotXml)
        val head = if (end < 0) cotXml else cotXml.substring(0, end)
        val span = attributeValueSpan(head, "uid") ?: return cotXml
        // Substituted in place rather than re-serialised from the DOM. A round
        // trip through a Transformer is free to reorder attributes, and the
        // tier 2 dictionary was built from the attribute order ATAK actually
        // emits -- reordering would cost airtime on every position report for
        // no benefit. It also keeps the event byte-identical apart from the
        // one value we mean to change.
        return cotXml.substring(0, span.first) + escapeAttribute(ourUid) +
            cotXml.substring(span.second)
    }

    /**
     * The UID this ATAK calls itself, learned from a self-report.
     *
     * Nothing configures it: ATAK announces its own identifier in every
     * position report, and asking an operator to type it would be one more
     * setting that can be wrong. Returns null for anything that is not a
     * self-report, which includes every marker and every relayed event.
     */
    fun learnAtakUid(cotXml: String): String? {
        val event = try {
            parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val uid = event.getAttribute("uid")
        if (uid.isEmpty()) return null
        // A self-report describes a unit; ATAK's carries <takv>, which
        // identifies the reporting software. A marker never does, which keeps
        // a marker created here from being mistaken for the device that made it.
        val detail = childElement(event, "detail") ?: return null
        return if (childElement(detail, "takv") != null) uid else null
    }

    /** Parse a CoT event, refusing the XML features CoT never needs. */
    fun parse(cotXml: String): Element {
        // Declarations and entities are what turn an XML parser into a denial
        // of service, and no legitimate CoT event contains either. The parser
        // is configured to refuse them as well; this check makes the intent
        // legible and does not depend on which parser Android ships.
        require(!cotXml.contains("<!")) { "XML declarations and entities are not accepted" }
        val document = try {
            builderFactory().newDocumentBuilder().parse(InputSource(StringReader(cotXml)))
        } catch (error: Exception) {
            throw IllegalArgumentException("malformed CoT: ${error.message}", error)
        }
        val root = document.documentElement ?: throw IllegalArgumentException("empty CoT")
        require(root.tagName == "event") { "not a CoT event" }
        return root
    }

    private fun builderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            setFeatureQuietly(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        // Android's parser and the JVM's do not support the same feature set,
        // and an unsupported hardening feature must not stop the endpoint from
        // starting. The check above is what actually holds the line.
        runCatching { setFeature(name, value) }
    }

    private fun childElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == name) return child
        }
        return null
    }

    /**
     * Index of the character closing the root start tag, or -1.
     *
     * Quote-aware, because a closing angle bracket is legal unescaped inside
     * an XML attribute value. Scanning to the first one would cut the tag
     * short and lose the uid attribute -- and losing it in [isSelfAddressed]
     * means forwarding our own event, which is the loop this check exists to
     * prevent.
     */
    private fun startTagEnd(xml: String): Int {
        var quote = ' '
        for (index in xml.indices) {
            val character = xml[index]
            when {
                quote != ' ' -> if (character == quote) quote = ' '
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index
            }
        }
        return -1
    }

    /**
     * Half-open span of the named attribute's *value* within a start tag.
     *
     * Names are matched as whole tokens, so a parent_uid attribute is not
     * mistaken for uid.
     */
    private fun attributeValueSpan(startTag: String, name: String): Pair<Int, Int>? {
        var index = startTag.indexOf(name)
        while (index >= 0) {
            val before = if (index == 0) ' ' else startTag[index - 1]
            var after = index + name.length
            while (after < startTag.length && startTag[after].isWhitespace()) after++
            if (before.isWhitespace() && after < startTag.length && startTag[after] == '=') {
                var value = after + 1
                while (value < startTag.length && startTag[value].isWhitespace()) value++
                if (value < startTag.length && (startTag[value] == '"' || startTag[value] == '\'')) {
                    val close = startTag.indexOf(startTag[value], value + 1)
                    if (close > 0) return (value + 1) to close
                }
            }
            index = startTag.indexOf(name, index + 1)
        }
        return null
    }

    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
}
