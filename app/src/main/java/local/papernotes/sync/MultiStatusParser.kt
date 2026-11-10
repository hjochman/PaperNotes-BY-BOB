package local.papernotes.sync

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Ein Eintrag aus einer PROPFIND-Antwort: Name im Ordner, Art, Größe und Änderungszeit in ms (0 = unbekannt). */
data class DavEntry(val name: String, val isCollection: Boolean, val size: Long, val modifiedMs: Long)

/**
 * Liest eine WebDAV-`207 Multi-Status`-Antwort. Antworten mit DOCTYPE oder Entity-Deklaration werden abgelehnt (kein
 * XXE, kein "Billion Laughs"), die Größe begrenzt der Aufrufer. Einträge außerhalb des angefragten Ordners und der Ordner
 * selbst werden ausgelassen.
 */
object MultiStatusParser {
    private const val DAV = "DAV:"

    /** Wirft [IllegalArgumentException] bei unlesbarem oder unzulässigem XML. [selfPath] = dekodierter Pfad des angefragten Ordners. */
    fun parse(xml: ByteArray, selfPath: String): List<DavEntry> {
        val text = String(xml, StandardCharsets.UTF_8)
        require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) { "doctype" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw IllegalArgumentException("xml", e)
        }
        val self = selfPath.trimEnd('/')
        val result = ArrayList<DavEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = firstText(response, "href") ?: continue
            val path = try {
                URI(href.trim()).path ?: continue
            } catch (e: URISyntaxException) {
                continue
            }.trimEnd('/')
            if (!path.startsWith("$self/")) continue
            val name = path.substring(self.length + 1)
            if (name.isEmpty() || name.contains('/')) continue
            val prop = okProp(response) ?: continue
            val collection = prop.getElementsByTagNameNS(DAV, "collection").length > 0
            val size = firstText(prop, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
            val modified = firstText(prop, "getlastmodified")?.let { parseDate(it.trim()) } ?: 0L
            result.add(DavEntry(name, collection, size, modified))
        }
        return result
    }

    private fun parseDate(text: String): Long = try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: RuntimeException) {
        0L
    }

    private fun firstText(parent: Element, localName: String): String? {
        val list = parent.getElementsByTagNameNS(DAV, localName)
        return if (list.length == 0) null else list.item(0).textContent
    }

    /** Das `prop`-Element des `propstat` mit Status 200. */
    private fun okProp(response: Element): Element? {
        val stats = response.getElementsByTagNameNS(DAV, "propstat")
        for (i in 0 until stats.length) {
            val stat = stats.item(i) as Element
            val status = firstText(stat, "status") ?: continue
            if (!status.contains(" 200")) continue
            var child: Node? = stat.firstChild
            while (child != null) {
                if (child is Element && child.localName == "prop" && child.namespaceURI == DAV) return child
                child = child.nextSibling
            }
        }
        return null
    }
}
