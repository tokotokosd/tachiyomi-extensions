package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReMangaTest {

    // Same settings the app's shared Json uses.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private inline fun <reified T> fixture(name: String): T {
        val text = javaClass.classLoader!!.getResource(name)!!.readText()
        return json.decodeFromString(text)
    }

    // ============================== JSON models ==============================

    @Test
    fun catalogParses() {
        val page = fixture<CatalogResponse>("catalog.json")
        assertEquals(30, page.results.size)
        assertTrue(page.next != null)
        val first = page.results.first()
        assertTrue(first.dir.isNotBlank())
        assertTrue(first.cover?.high!!.startsWith("/media/"))
    }

    @Test
    fun searchParses() {
        val page = fixture<SearchResponse>("search.json")
        assertTrue(page.results.isNotEmpty())
        assertEquals(1, page.meta!!.page)
        assertTrue(page.meta!!.totalPages > 1)
    }

    @Test
    fun titleParses() {
        val title = fixture<TitleDetails>("title.json")
        assertEquals("martial-master", title.dir)
        assertEquals(6394L, title.id)
        assertEquals(6, title.status!!.id)
        assertEquals(1, title.branches.size)
        assertEquals(548, title.branches[0].countChapters)
        assertEquals("7.1", title.avgRating!!.content)
    }

    @Test
    fun creatorsMapToAuthorAndArtist() {
        val title = fixture<TitleDetails>("title_creators.json")
        assertEquals("SunWoon", ReMangaUtils.creators(title, 1))
        assertEquals("2salang, REDICE studio", ReMangaUtils.creators(title, 4, 2))
    }

    @Test
    fun chaptersParse() {
        val list = fixture<ChapterListResponse>("chapters.json")
        assertEquals(30, list.results.size)
        assertEquals(2, list.next)
        val first = list.results.first()
        assertEquals("299", first.chapter)
        assertTrue(first.isPaid)
        assertEquals(false, first.isBought)
        assertEquals("Kuaikanmanhua", first.publishers.single().name)
    }

    @Test
    fun freePagesParse() {
        val pages = fixture<ChapterPages>("pages.json")
        assertFalse(pages.isPaid)
        assertEquals(38, pages.pages.flatten().size)
        assertTrue(pages.server!!.fallbackLink!!.startsWith("https://"))
    }

    @Test
    fun paidPagesAreEmpty() {
        val pages = fixture<ChapterPages>("pages_paid.json")
        assertTrue(pages.isPaid)
        assertTrue(pages.pages.flatten().isEmpty())
    }

    @Test
    fun filterFormsParse() {
        val forms = fixture<FilterForms>("forms.json").content
        assertTrue(forms.genres.size > 20)
        assertTrue(forms.categories.size > 20)
    }

    // ============================== Helpers ==============================

    @Test
    fun slugFromStoredAndUserUrls() {
        assertEquals("solo", ReMangaUtils.slugFromUrl("/manga/solo/123"))
        assertEquals("solo", ReMangaUtils.slugFromUrl("/manga/solo/123?branch=55"))
        assertEquals("solo", ReMangaUtils.slugFromUrl("/manga/solo"))
        assertEquals("solo", ReMangaUtils.slugFromUrl("https://remanga.org/manga/solo/main"))
        assertEquals("solo", ReMangaUtils.slugFromUrl("https://remanga.org/manga/solo/780367?p=1"))
        assertEquals("solo", ReMangaUtils.slugFromUrl("solo"))
        assertEquals("<29.04.2026>omniscient-reader_", ReMangaUtils.slugFromUrl("/manga/<29.04.2026>omniscient-reader_/1"))
        assertNull(ReMangaUtils.slugFromUrl("https://remanga.org/catalog"))
        assertNull(ReMangaUtils.slugFromUrl("/chapter/5"))
    }

    @Test
    fun chapterIds() {
        assertEquals(780367L, ReMangaUtils.chapterIdFromUrl("/chapter/780367"))
        assertEquals(780367L, ReMangaUtils.chapterIdFromUrl(ReMangaUtils.chapterUrl(780367)))
    }

    @Test
    fun chapterNames() {
        assertEquals("Том 1. Глава 299", ReMangaUtils.chapterName(1, "299", ""))
        assertEquals("Том 2. Глава 10.5: Финал", ReMangaUtils.chapterName(2, "10.5", " Финал "))
        assertEquals("Глава 3", ReMangaUtils.chapterName(null, "3", null))
        assertEquals(10.5f, ReMangaUtils.chapterNumber("10.5"))
        assertEquals(-1f, ReMangaUtils.chapterNumber("экстра"))
    }

    @Test
    fun datesAreMoscowTime() {
        // 2026-11-04T09:41:28 in Moscow (UTC+3) is 06:41:28 UTC.
        assertEquals(1793774488000L, ReMangaUtils.parseDate("2026-11-04T09:41:28.580533"))
        assertEquals(1713128400000L, ReMangaUtils.parseDate("2024-04-15T00:00:00"))
        assertEquals(0L, ReMangaUtils.parseDate(null))
        assertEquals(0L, ReMangaUtils.parseDate("garbage"))
    }

    @Test
    fun statuses() {
        assertEquals(SManga.ONGOING, ReMangaUtils.status(2, 2))
        assertEquals(SManga.COMPLETED, ReMangaUtils.status(1, 1))
        assertEquals(SManga.PUBLISHING_FINISHED, ReMangaUtils.status(1, 2))
        assertEquals(SManga.ON_HIATUS, ReMangaUtils.status(3, null))
        assertEquals(SManga.LICENSED, ReMangaUtils.status(6, 1))
        assertEquals(SManga.UNKNOWN, ReMangaUtils.status(null, null))
    }

    @Test
    fun descriptionKeepsParagraphs() {
        val text = ReMangaUtils.htmlToText("<p>Первый&nbsp;абзац.</p><p>Второй<br>строка</p>")
        assertEquals("Первый абзац.\n\nВторой\nстрока", text)
    }

    @Test
    fun descriptionMentionsLicensedWithoutChapters() {
        val title = fixture<TitleDetails>("title.json")
        val text = ReMangaUtils.buildDescription(title, emptyList())
        assertTrue(text.contains("Год выпуска: 2013"))
        assertTrue(text.endsWith("Главы недоступны (тайтл лицензирован)."))
    }

    @Test
    fun branchSelection() {
        val branches = listOf(
            Branch(1, countChapters = 10, totalVotes = 5),
            Branch(2, countChapters = 50, totalVotes = 100),
            Branch(3, countChapters = 0, totalVotes = 1000),
        )
        assertEquals(listOf(1L, 2L), ReMangaUtils.selectBranches(branches, ReMangaUtils.BRANCH_ALL).map { it.id })
        assertEquals(listOf(2L), ReMangaUtils.selectBranches(branches, ReMangaUtils.BRANCH_MAIN).map { it.id })
        assertTrue(ReMangaUtils.selectBranches(emptyList(), ReMangaUtils.BRANCH_MAIN).isEmpty())
    }
}
