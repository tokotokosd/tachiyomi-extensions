package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Pure helpers with no extensions-lib runtime calls, so unit tests can run them on the JVM.

internal object ReMangaUtils {

    // ============================== URLs ==============================

    /**
     * Title slug from anything the user or an older version of the extension stored:
     * `/manga/{dir}/{id}`, `/manga/{dir}/{id}?branch=1`, `/manga/{dir}`,
     * `https://remanga.org/manga/{dir}/main`, or a bare `{dir}`.
     */
    fun slugFromUrl(url: String): String? {
        val noQuery = url.trim().substringBefore('#').substringBefore('?')
        val isAbsolute = "://" in noQuery
        val path = if (isAbsolute) noQuery.substringAfter("://").substringAfter('/', "") else noQuery
        val parts = path.split('/').filter { it.isNotEmpty() }
        return when {
            parts.firstOrNull() == "manga" -> parts.getOrNull(1)
            !isAbsolute && !noQuery.startsWith("/") && parts.size == 1 -> parts[0]
            else -> null
        }
    }

    fun mangaUrl(dir: String, id: Long) = "/manga/$dir/$id"

    /** Chapter URLs stay `/chapter/{id}` so existing libraries keep their read history. */
    fun chapterUrl(id: Long) = "/chapter/$id"

    fun chapterIdFromUrl(url: String): Long? =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').toLongOrNull()

    fun absoluteImageUrl(baseUrl: String, path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        path.startsWith("/") -> baseUrl + path
        else -> "$baseUrl/media/$path"
    }

    // ============================== Chapters ==============================

    fun chapterName(tome: Int?, chapter: String, name: String?): String = buildString {
        if (tome != null && tome > 0) append("Том ").append(tome).append(". ")
        append("Глава ").append(chapter)
        val extra = name?.trim().orEmpty()
        if (extra.isNotEmpty()) append(": ").append(extra)
    }

    fun chapterNumber(chapter: String): Float =
        chapter.replace(',', '.').toFloatOrNull() ?: -1f

    // The API returns Moscow wall-clock time, sometimes with microseconds.
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val trimmed = date.substringBefore('.').substringBefore('Z').substringBefore('+')
        return try {
            synchronized(dateFormat) { dateFormat.parse(trimmed)?.time ?: 0L }
        } catch (_: Exception) {
            0L
        }
    }

    // ============================== Details ==============================

    /** Status ids from `/api/forms/titles/`: 1 completed, 2 ongoing, 3 frozen, 5 announced, 6 licensed. */
    fun status(statusId: Int?, translateStatusId: Int?): Int = when (statusId) {
        1 -> if (translateStatusId == null || translateStatusId == 1) SManga.COMPLETED else SManga.PUBLISHING_FINISHED
        2 -> SManga.ONGOING
        3 -> SManga.ON_HIATUS
        5 -> SManga.ONGOING
        6 -> SManga.LICENSED
        else -> SManga.UNKNOWN
    }

    /** Converts the site's HTML description into plain text with paragraph breaks kept. */
    fun htmlToText(html: String?): String {
        if (html.isNullOrBlank()) return ""
        val body = Jsoup.parseBodyFragment(html).body()
        val sb = StringBuilder()
        appendText(body, sb)
        return sb.toString()
            .replace(' ', ' ')
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun appendText(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.text())
            is Element -> {
                val tag = node.normalName()
                if (tag == "br") {
                    sb.append('\n')
                    return
                }
                node.childNodes().forEach { appendText(it, sb) }
                if (tag == "p" || tag == "div" || tag == "li") sb.append("\n\n")
            }
        }
    }

    fun buildDescription(details: TitleDetails, branches: List<Branch>): String = buildString {
        val text = htmlToText(details.description)
        if (text.isNotEmpty()) append(text)

        val meta = mutableListOf<String>()
        details.secondaryName?.takeIf { it.isNotBlank() }?.let { meta += "Оригинальное название: $it" }
        details.anotherName?.takeIf { it.isNotBlank() }?.let { meta += "Другие названия: $it" }
        details.issueYear?.takeIf { it > 0 }?.let { meta += "Год выпуска: $it" }
        val rating = details.avgRating?.content?.toDoubleOrNull()
        if (rating != null && rating > 0) {
            val votes = details.countRating?.let { " ($it оценок)" }.orEmpty()
            meta += "Рейтинг: $rating$votes"
        }
        details.translateStatus?.name?.let { meta += "Перевод: $it" }
        if (branches.size > 1) {
            meta += "Переводы: " + branches.joinToString("; ") { branchLabel(it) }
        }
        if (meta.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(meta.joinToString("\n"))
        }
        if (branches.isEmpty() || branches.all { it.countChapters == 0 }) {
            if (isNotEmpty()) append("\n\n")
            append("Главы недоступны")
            if (details.isLicensed) append(" (тайтл лицензирован)")
            append('.')
        }
    }

    fun branchLabel(branch: Branch): String {
        val names = branch.publishers.joinToString(", ") { it.name }.ifBlank { "Перевод ${branch.id}" }
        return "$names — ${branch.countChapters} гл."
    }

    fun genres(details: TitleDetails): String? = buildList {
        details.type?.name?.let(::add)
        details.genres.forEach { add(it.name) }
        details.categories.forEach { add(it.name) }
        details.ageLimit?.name?.takeIf { it.endsWith("+") }?.let(::add)
    }.distinct().joinToString().ifEmpty { null }

    fun creators(details: TitleDetails, vararg types: Int): String? =
        types.flatMap { type -> details.creators.filter { it.type == type } } // in the order of [types]
            .mapNotNull { it.name?.takeIf(String::isNotBlank) }
            .distinct().joinToString().ifEmpty { null }

    // ============================== Branches ==============================

    /** In [BRANCH_MAIN] mode only the most popular branch is used; otherwise all branches with chapters. */
    fun selectBranches(branches: List<Branch>, mode: String): List<Branch> {
        val withChapters = branches.filter { it.countChapters > 0 }
        if (mode != BRANCH_MAIN) return withChapters
        return listOfNotNull(
            withChapters.maxWithOrNull(compareBy<Branch>({ it.totalVotes }, { it.countChapters })),
        )
    }

    const val BRANCH_ALL = "all"
    const val BRANCH_MAIN = "main"
}
