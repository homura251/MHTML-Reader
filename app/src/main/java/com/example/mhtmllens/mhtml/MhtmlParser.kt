package com.example.mhtmllens.mhtml

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Small, dependency-free MHTML reader designed around Chrome/Blink web archives.
 *
 * Important encoding rule: transfer encoding (quoted-printable/base64) is decoded
 * into the original bytes first. Character decoding happens only afterwards, using
 * MIME charset -> BOM -> HTML meta charset -> strict UTF-8, in that order.
 */
object MhtmlParser {

    data class Part(
        val mimeType: String,
        val charsetName: String?,
        val contentLocation: String?,
        val contentId: String?,
        val bytes: ByteArray,
        val headers: Map<String, String>
    )

    data class Archive(
        val root: Part,
        val rootHtml: String,
        val rootCharset: String,
        val rootUrl: String?,
        val title: String,
        val subject: String?,
        val snapshotUrl: String?,
        val parts: List<Part>,
        val aliases: Map<String, Part>
    ) {
        fun findPart(url: String): Part? {
            val key = normalizeUrl(url)
            return aliases[key]
                ?: aliases[stripFragment(key)]
                ?: if (key.startsWith("cid:", ignoreCase = true)) {
                    aliases[normalizeCid(key.removePrefixIgnoreCase("cid:"))]
                } else null
        }
    }

    fun parse(bytes: ByteArray): Archive {
        val topHeaderEnd = findHeaderEnd(bytes, 0)
            ?: throw IllegalArgumentException("Not a MIME archive: top-level headers are missing")
        val topHeaders = parseHeaders(bytes.copyOfRange(0, topHeaderEnd.bodyStart))
        val topContentType = topHeaders["content-type"]
            ?: throw IllegalArgumentException("Not MHTML: Content-Type is missing")
        if (!topContentType.lowercase(Locale.ROOT).startsWith("multipart/related")) {
            throw IllegalArgumentException("Not MHTML: expected multipart/related")
        }
        val boundary = parameter(topContentType, "boundary")
            ?: throw IllegalArgumentException("Not MHTML: MIME boundary is missing")
        val startContentId = parameter(topContentType, "start")?.trim('<', '>', ' ', '\t')

        val rawParts = splitMimeParts(bytes, topHeaderEnd.bodyStart, boundary)
        val parts = rawParts.mapNotNull { raw -> parsePart(raw) }
        if (parts.isEmpty()) throw IllegalArgumentException("MHTML contains no MIME parts")

        val root = when {
            startContentId != null -> parts.firstOrNull {
                it.contentId?.trim('<', '>', ' ', '\t')?.equals(startContentId, ignoreCase = true) == true
            }
            else -> null
        } ?: parts.firstOrNull { it.mimeType.equals("text/html", ignoreCase = true) }
            ?: throw IllegalArgumentException("MHTML contains no HTML root part")

        val rootCharset = detectCharset(root.headers["content-type"], root.bytes, "text/html")
        val rootHtml = decodeText(root.bytes, rootCharset)
        val rootUrl = root.contentLocation
            ?: topHeaders["snapshot-content-location"]
        val title = extractTitle(rootHtml)
            .ifBlank { decodeMimeHeader(topHeaders["subject"].orEmpty()).ifBlank { "Untitled archive" } }

        val aliases = buildAliases(parts, rootUrl)

        return Archive(
            root = root,
            rootHtml = rootHtml,
            rootCharset = rootCharset.name(),
            rootUrl = rootUrl,
            title = title,
            subject = topHeaders["subject"]?.let(::decodeMimeHeader),
            snapshotUrl = topHeaders["snapshot-content-location"],
            parts = parts,
            aliases = aliases
        )
    }


    fun parseHtml(bytes: ByteArray, baseUrl: String? = null, fallbackTitle: String = "HTML document"): Archive {
        val charset = detectCharset(null, bytes, "text/html")
        val html = decodeText(bytes, charset)
        val title = extractTitle(html).ifBlank { fallbackTitle }
        val root = Part(
            mimeType = "text/html",
            charsetName = charset.name(),
            contentLocation = baseUrl,
            contentId = null,
            bytes = bytes,
            headers = mapOf("content-type" to "text/html; charset=${charset.name()}")
        )
        val aliases = linkedMapOf<String, Part>()
        if (!baseUrl.isNullOrBlank()) addAlias(aliases, baseUrl, root)
        return Archive(
            root = root,
            rootHtml = html,
            rootCharset = charset.name(),
            rootUrl = baseUrl,
            title = title,
            subject = null,
            snapshotUrl = null,
            parts = listOf(root),
            aliases = aliases
        )
    }

    fun looksLikeHtml(prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val probe = prefix.copyOfRange(0, minOf(prefix.size, 64 * 1024))
            .toString(StandardCharsets.ISO_8859_1)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase(Locale.ROOT)
        if (probe.startsWith("<!doctype html") || probe.startsWith("<html")) return true
        return probe.contains("<html") && (probe.contains("<head") || probe.contains("<body"))
    }

    fun previewHtmlTitle(prefix: ByteArray): String? {
        if (!looksLikeHtml(prefix)) return null
        val charset = detectCharset(null, prefix, "text/html")
        return extractTitle(decodeText(prefix, charset)).takeIf { it.isNotBlank() }
    }

    fun looksLikeMhtml(prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val ascii = prefix.toString(StandardCharsets.ISO_8859_1)
        val lower = ascii.lowercase(Locale.ROOT)
        val mime = lower.contains("mime-version:") &&
            lower.contains("content-type: multipart/related") &&
            lower.contains("boundary=")
        val blink = lower.contains("from: <saved by blink>") &&
            lower.contains("snapshot-content-location:")
        return mime || blink
    }

    fun previewTitle(prefix: ByteArray): String? {
        if (!looksLikeMhtml(prefix)) return null
        // Chrome puts the HTML root first. Decoding quoted-printable in the prefix is
        // enough for most <title> elements and does not mutate the source file.
        val transfer = String(prefix, StandardCharsets.ISO_8859_1)
        val decoded = decodeQuotedPrintable(prefix)
        val candidates = listOf(
            String(decoded, StandardCharsets.UTF_8),
            transfer
        )
        return candidates.firstNotNullOfOrNull { text ->
            extractTitle(text).takeIf { it.isNotBlank() }
        }
    }

    fun decodePartText(part: Part, fallbackMimeType: String = part.mimeType): Pair<String, Charset> {
        val charset = detectCharset(part.headers["content-type"], part.bytes, fallbackMimeType)
        return decodeText(part.bytes, charset) to charset
    }

    private data class HeaderEnd(val bodyStart: Int)

    private fun findHeaderEnd(bytes: ByteArray, start: Int): HeaderEnd? {
        var i = start
        while (i + 3 < bytes.size) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
            ) return HeaderEnd(i + 4)
            i++
        }
        i = start
        while (i + 1 < bytes.size) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                return HeaderEnd(i + 2)
            }
            i++
        }
        return null
    }

    private fun splitMimeParts(bytes: ByteArray, bodyStart: Int, boundary: String): List<ByteArray> {
        val delimiter = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        val starts = mutableListOf<Int>()
        var i = bodyStart
        while (i <= bytes.size - delimiter.size) {
            if ((i == bodyStart || i == 0 || bytes[i - 1] == '\n'.code.toByte()) &&
                matchesAt(bytes, delimiter, i)
            ) {
                starts += i
                i += delimiter.size
            } else i++
        }
        if (starts.isEmpty()) return emptyList()

        val result = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val markerStart = starts[index]
            var afterMarker = markerStart + delimiter.size
            if (afterMarker + 1 < bytes.size && bytes[afterMarker] == '-'.code.toByte() && bytes[afterMarker + 1] == '-'.code.toByte()) {
                break
            }
            while (afterMarker < bytes.size && bytes[afterMarker] != '\n'.code.toByte()) afterMarker++
            if (afterMarker < bytes.size) afterMarker++
            val nextMarker = starts.getOrNull(index + 1) ?: bytes.size
            var end = nextMarker
            // The CRLF immediately before the next boundary belongs to MIME framing.
            if (end > afterMarker && bytes[end - 1] == '\n'.code.toByte()) end--
            if (end > afterMarker && bytes[end - 1] == '\r'.code.toByte()) end--
            if (end > afterMarker) result += bytes.copyOfRange(afterMarker, end)
        }
        return result
    }

    private fun parsePart(raw: ByteArray): Part? {
        val end = findHeaderEnd(raw, 0) ?: return null
        val headers = parseHeaders(raw.copyOfRange(0, end.bodyStart))
        val contentType = headers["content-type"] ?: "application/octet-stream"
        val mimeType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val transfer = headers["content-transfer-encoding"]?.trim()?.lowercase(Locale.ROOT)
        val body = raw.copyOfRange(end.bodyStart, raw.size)
        val decoded = when (transfer) {
            "quoted-printable" -> decodeQuotedPrintable(body)
            "base64" -> try {
                Base64.getMimeDecoder().decode(body)
            } catch (_: IllegalArgumentException) {
                body
            }
            else -> body
        }
        return Part(
            mimeType = mimeType,
            charsetName = parameter(contentType, "charset"),
            contentLocation = headers["content-location"]?.trim(),
            contentId = headers["content-id"]?.trim()?.trim('<', '>'),
            bytes = decoded,
            headers = headers
        )
    }

    private fun parseHeaders(bytes: ByteArray): Map<String, String> {
        val raw = bytes.toString(StandardCharsets.ISO_8859_1)
            .replace(Regex("\\r?\\n[ \\t]+"), " ")
        val result = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                result[line.substring(0, colon).trim().lowercase(Locale.ROOT)] =
                    line.substring(colon + 1).trim()
            }
        }
        return result
    }

    private fun parameter(headerValue: String, name: String): String? {
        val regex = Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^;\\s]+))", RegexOption.IGNORE_CASE)
        val match = regex.find(headerValue) ?: return null
        return (match.groupValues[1].ifBlank {
            match.groupValues[2].ifBlank { match.groupValues[3] }
        }).trim()
    }

    private fun detectCharset(contentType: String?, bytes: ByteArray, mimeType: String): Charset {
        parameter(contentType.orEmpty(), "charset")?.let(::safeCharset)?.let { return it }

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE

        if (mimeType.equals("text/html", ignoreCase = true) || mimeType.equals("text/css", ignoreCase = true)) {
            val probe = bytes.copyOfRange(0, minOf(bytes.size, 32 * 1024)).toString(StandardCharsets.ISO_8859_1)
            Regex("charset\\s*=\\s*[\\\"']?\\s*([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE)
                .find(probe)?.groupValues?.getOrNull(1)?.let(::safeCharset)?.let { return it }
        }

        if (isStrictUtf8(bytes)) return StandardCharsets.UTF_8
        return safeCharset("windows-1252") ?: StandardCharsets.ISO_8859_1
    }

    private fun safeCharset(name: String): Charset? = try {
        Charset.forName(name.trim().trim('"', '\'', ' '))
    } catch (_: Exception) {
        null
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun decodeText(bytes: ByteArray, charset: Charset): String {
        val offset = when {
            charset == StandardCharsets.UTF_8 && bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
            (charset == StandardCharsets.UTF_16LE || charset == StandardCharsets.UTF_16BE) && bytes.size >= 2 -> 2
            else -> 0
        }
        return String(bytes, offset, bytes.size - offset, charset)
    }

    private fun decodeQuotedPrintable(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i]
            if (b == '='.code.toByte()) {
                if (i + 2 < input.size && input[i + 1] == '\r'.code.toByte() && input[i + 2] == '\n'.code.toByte()) {
                    i += 3
                    continue
                }
                if (i + 1 < input.size && input[i + 1] == '\n'.code.toByte()) {
                    i += 2
                    continue
                }
                if (i + 2 < input.size) {
                    val hi = hex(input[i + 1])
                    val lo = hex(input[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        out.write((hi shl 4) or lo)
                        i += 3
                        continue
                    }
                }
            }
            out.write(b.toInt() and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun extractTitle(html: String): String {
        val raw = Regex("<title(?:\\s[^>]*)?>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1).orEmpty()
        return decodeHtmlEntities(raw.replace(Regex("\\s+"), " ").trim())
    }

    private fun decodeHtmlEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&apos;", "'", ignoreCase = true)
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#x([0-9A-Fa-f]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
    }

    private fun decodeMimeHeader(value: String): String {
        if (!value.contains("=?")) return value
        val encodedWord = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        return encodedWord.replace(value) { match ->
            val charset = safeCharset(match.groupValues[1]) ?: StandardCharsets.UTF_8
            val mode = match.groupValues[2]
            val payload = match.groupValues[3]
            val decoded = try {
                if (mode.equals("B", ignoreCase = true)) {
                    Base64.getDecoder().decode(payload)
                } else {
                    decodeQuotedPrintable(payload.replace('_', ' ').toByteArray(StandardCharsets.ISO_8859_1))
                }
            } catch (_: Exception) {
                return@replace match.value
            }
            String(decoded, charset)
        }
    }

    private fun buildAliases(parts: List<Part>, rootUrl: String?): Map<String, Part> {
        val map = linkedMapOf<String, Part>()
        for (part in parts) {
            part.contentLocation?.takeIf { it.isNotBlank() }?.let { location ->
                addAlias(map, location, part)
                if (rootUrl != null) resolve(rootUrl, location)?.let { addAlias(map, it, part) }
            }
            part.contentId?.takeIf { it.isNotBlank() }?.let { id ->
                map[normalizeCid(id)] = part
                map[normalizeUrl("cid:${id.trim('<', '>')}" )] = part
            }
        }
        return map
    }

    private fun addAlias(map: MutableMap<String, Part>, url: String, part: Part) {
        val normalized = normalizeUrl(url)
        map[normalized] = part
        map[stripFragment(normalized)] = part
        // WebView may percent-escape literal spaces before requesting.
        map[normalized.replace(" ", "%20")] = part
    }

    private fun resolve(base: String, location: String): String? = try {
        URI(base).resolve(location).toString()
    } catch (_: Exception) {
        null
    }

    private fun normalizeCid(id: String): String = "cid:" + id.trim().trim('<', '>').lowercase(Locale.ROOT)

    private fun normalizeUrl(url: String): String = stripFragment(url.trim())

    private fun stripFragment(url: String): String = url.substringBefore('#')

    private fun matchesAt(source: ByteArray, needle: ByteArray, index: Int): Boolean {
        if (index < 0 || index + needle.size > source.size) return false
        for (i in needle.indices) if (source[index + i] != needle[i]) return false
        return true
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
