package eu.kanade.tachiyomi.extension.ru.remanga

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.io.IOException
import java.net.URLDecoder

class ReManga : HttpSource(), ConfigurableSource {

    override val name = "ReManga"

    override val baseUrl = "https://remanga.org"

    private val apiUrl = "https://api.remanga.org"

    override val lang = "ru"

    override val supportsLatest = true

    // Fixed so existing libraries keep working (it does not match the generated id).
    override val id = 7584815203666942529L

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimitHost(apiUrl.toHttpUrl(), 4)
        .addInterceptor(::authInterceptor)
        .addInterceptor(::imageFallbackInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private fun apiHeaders() = headersBuilder()
        .add("Accept", "application/json")
        .build()

    // ============================== Auth ==============================

    /**
     * Paid chapters the user owns need their token. It is read from the site's
     * `auth:token` cookie (set after logging in through WebView), or from the
     * token pasted into the extension settings.
     */
    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_HOST || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val token = authToken() ?: return chain.proceed(request)
        val response = chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
        if (response.code == 401) {
            // Expired or wrong token: retry anonymously so free content still loads.
            response.close()
            return chain.proceed(request)
        }
        return response
    }

    private fun authToken(): String? {
        preferences.getString(PREF_TOKEN, null)?.trim()?.removePrefix("Bearer ")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return network.client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == TOKEN_COOKIE || it.name == TOKEN_COOKIE_ENCODED }
            ?.value
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }

    // ============================== Images ==============================

    /**
     * Pages come from a primary image host with a fallback host; image URLs carry the
     * alternative in the fragment (`#alt=...`). Fragments are never sent to the server.
     */
    private fun imageFallbackInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val alt = request.url.fragment?.takeIf { it.startsWith(ALT_PREFIX) }?.removePrefix(ALT_PREFIX)
            ?: return chain.proceed(request)
        val primary = request.newBuilder().url(request.url.newBuilder().fragment(null).build()).build()
        val failure: Exception? = try {
            val response = chain.proceed(primary)
            if (response.isSuccessful) return response
            response.close()
            null
        } catch (e: IOException) {
            e
        }
        val altUrl = alt.toHttpUrlOrNull() ?: failure?.let { throw it } ?: return chain.proceed(primary)
        return chain.proceed(primary.newBuilder().url(altUrl).build())
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    override fun imageRequest(page: Page): Request {
        val headers = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return GET(page.imageUrl!!, headers)
    }

    // ============================== Popular / Latest ==============================

    override fun popularMangaRequest(page: Int): Request =
        catalogRequest(page, FilterList(SortFilter()))

    override fun popularMangaParse(response: Response): MangasPage = catalogParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val sort = SortFilter().apply { state = Filter.Sort.Selection(SortFilter.LATEST, false) }
        return catalogRequest(page, FilterList(sort))
    }

    override fun latestUpdatesParse(response: Response): MangasPage = catalogParse(response)

    // ============================== Search ==============================

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val slug = when {
            query.startsWith(PREFIX_SLUG) -> query.removePrefix(PREFIX_SLUG).trim()
            query.startsWith("http") && "remanga" in query -> ReMangaUtils.slugFromUrl(query)
            else -> null
        }
        if (slug != null) {
            return client.newCall(titleRequest(slug)).asObservableSuccess().map { response ->
                val details = response.parseAs<TitleDetails>()
                MangasPage(listOf(detailsToManga(details)), false)
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // The text search endpoint does not accept catalog filters.
            val url = "$apiUrl/api/v2/search/".toHttpUrl().newBuilder()
                .addQueryParameter("query", query.trim())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("count", PAGE_SIZE.toString())
                .build()
            return GET(url, apiHeaders())
        }
        return catalogRequest(page, filters)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.startsWith("/api/v2/search/catalog")) {
            return catalogParse(response)
        }
        val result = response.parseAs<SearchResponse>()
        val meta = result.meta
        return MangasPage(
            result.results.map(::cardToManga),
            meta != null && meta.page < meta.totalPages,
        )
    }

    private fun catalogRequest(page: Int, filters: FilterList): Request {
        val url = "$apiUrl/api/v2/search/catalog/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("count", PAGE_SIZE.toString())
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull() ?: SortFilter()
        sort.apply(url)
        filters.forEach { filter ->
            when (filter) {
                is CheckBoxGroup -> filter.apply(url)
                is TriStateGroup -> filter.apply(url)
                is MinChaptersFilter -> filter.state.trim().toIntOrNull()?.takeIf { it > 0 }?.let {
                    url.addQueryParameter("count_chapters_gte", it.toString())
                }
                else -> {}
            }
        }
        return GET(url.build(), apiHeaders())
    }

    private fun catalogParse(response: Response): MangasPage {
        val result = response.parseAs<CatalogResponse>()
        return MangasPage(result.results.map(::cardToManga), result.next != null)
    }

    private fun cardToManga(card: TitleCard) = SManga.create().apply {
        url = ReMangaUtils.mangaUrl(card.dir, card.id)
        title = card.mainName?.takeIf { it.isNotBlank() } ?: card.secondaryName ?: card.dir
        thumbnail_url = ReMangaUtils.absoluteImageUrl(baseUrl, card.cover?.high ?: card.cover?.mid)
    }

    // ============================== Details ==============================

    private fun titleRequest(slug: String): Request {
        val url = "$apiUrl/api/v2/titles/".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .addPathSegment("")
            .build()
        return GET(url, apiHeaders())
    }

    private fun slugOf(manga: SManga): String =
        ReMangaUtils.slugFromUrl(manga.url) ?: throw IOException("Не удалось определить тайтл: ${manga.url}")

    override fun mangaDetailsRequest(manga: SManga): Request = titleRequest(slugOf(manga))

    override fun mangaDetailsParse(response: Response): SManga =
        detailsToManga(response.parseAs<TitleDetails>())

    private fun detailsToManga(details: TitleDetails) = SManga.create().apply {
        url = ReMangaUtils.mangaUrl(details.dir, details.id)
        title = details.mainName?.takeIf { it.isNotBlank() } ?: details.secondaryName ?: details.dir
        thumbnail_url = ReMangaUtils.absoluteImageUrl(baseUrl, details.cover?.high ?: details.cover?.mid)
        author = ReMangaUtils.creators(details, CREATOR_AUTHOR)
        artist = ReMangaUtils.creators(details, CREATOR_ARTIST, CREATOR_STUDIO)
        genre = ReMangaUtils.genres(details)
        status = ReMangaUtils.status(details.status?.id, details.translateStatus?.id)
        description = ReMangaUtils.buildDescription(details, details.branches)
        initialized = true
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${slugOf(manga)}/main"

    // ============================== Chapters ==============================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { loadChapters(manga) }

    private fun loadChapters(manga: SManga): List<SChapter> {
        val details = client.newCall(titleRequest(slugOf(manga))).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.parseAs<TitleDetails>()
        }
        val mode = preferences.getString(PREF_BRANCH, ReMangaUtils.BRANCH_ALL)!!
        val branches = ReMangaUtils.selectBranches(details.branches, mode)
        val showLocked = preferences.getString(PREF_PAID, PAID_SHOW) == PAID_SHOW

        return branches.flatMap { branch ->
            val team = branch.publishers.joinToString(", ") { it.name }.ifBlank { null }
            fetchBranchChapters(branch.id)
                .asSequence()
                .filter { it.isPublished }
                .filter { showLocked || !it.isLocked() }
                .map { chapterToSChapter(it, team) }
                .toList()
        }.sortedWith(
            compareByDescending<SChapter> { it.chapter_number }
                .thenByDescending { it.date_upload },
        )
    }

    private fun fetchBranchChapters(branchId: Long): List<ChapterItem> {
        val chapters = mutableListOf<ChapterItem>()
        var page: Int? = 1
        while (page != null) {
            val url = "$apiUrl/api/v2/titles/chapters/".toHttpUrl().newBuilder()
                .addQueryParameter("branch_id", branchId.toString())
                .addQueryParameter("ordering", "-index")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("count", CHAPTER_PAGE_SIZE.toString())
                .build()
            val result = client.newCall(GET(url, apiHeaders())).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.parseAs<ChapterListResponse>()
            }
            chapters += result.results
            page = result.next?.takeIf { result.results.isNotEmpty() && it > page!! }
        }
        return chapters
    }

    private fun ChapterItem.isLocked() = isPaid && isBought != true

    private fun chapterToSChapter(item: ChapterItem, team: String?) =
        SChapter.create().apply {
            url = ReMangaUtils.chapterUrl(item.id)
            val base = ReMangaUtils.chapterName(item.tome, item.chapter, item.name)
            name = if (item.isLocked()) "$LOCK_MARK$base" else base
            chapter_number = ReMangaUtils.chapterNumber(item.chapter)
            date_upload = ReMangaUtils.parseDate(item.uploadDate ?: item.pubDate)
            // The team name lets the app's scanlator filter pick one translation.
            scanlator = item.publishers.joinToString(", ") { it.name }.ifBlank { null } ?: team
        }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException()

    // The chapter's slug is not stored in its URL (kept for history compatibility),
    // so WebView opens the API response, which is better than a 404 page.
    override fun getChapterUrl(chapter: SChapter): String {
        val id = ReMangaUtils.chapterIdFromUrl(chapter.url)
        return "$apiUrl/api/v2/titles/chapters/$id/"
    }

    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        val id = ReMangaUtils.chapterIdFromUrl(chapter.url)
            ?: throw IOException("Неверная ссылка на главу: ${chapter.url}")
        return GET("$apiUrl/api/v2/titles/chapters/$id/", apiHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterPages>()
        val images = result.pages.flatten()
        if (images.isEmpty()) {
            if (result.isPaid && result.isBought != true) {
                throw IOException("Платная глава. Купите её на сайте и войдите в аккаунт через WebView.")
            }
            throw IOException("В главе нет страниц")
        }
        val primaryBase = result.server?.link
        val fallbackBase = result.server?.fallbackLink
        return images.mapIndexed { index, image ->
            val alt = alternativeImageUrl(image.link, primaryBase, fallbackBase)
            val url = if (alt != null) "${image.link}#$ALT_PREFIX$alt" else image.link
            Page(index, imageUrl = url)
        }
    }

    private fun alternativeImageUrl(link: String, primary: String?, fallback: String?): String? {
        if (primary.isNullOrBlank() || fallback.isNullOrBlank()) return null
        return when {
            link.startsWith(primary) -> fallback + link.removePrefix(primary)
            link.startsWith(fallback) -> primary + link.removePrefix(fallback)
            else -> null
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    @Volatile
    private var filterForms: FilterFormsContent? = null

    @Volatile
    private var filterFetchFailed = false

    private fun loadFilterForms() {
        if (filterForms != null) return
        Thread {
            try {
                val url = "$apiUrl/api/forms/titles/?get=genres&get=categories"
                filterForms = client.newCall(GET(url, apiHeaders())).execute().use {
                    it.parseAs<FilterForms>().content
                }
                filterFetchFailed = false
            } catch (_: Exception) {
                filterFetchFailed = true
            }
        }.start()
    }

    override fun getFilterList(): FilterList {
        loadFilterForms()
        val forms = filterForms
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Фильтры не работают вместе с текстовым поиском"),
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            AgeFilter(),
            MinChaptersFilter(),
        )
        if (forms != null) {
            filters += GenreFilter(forms.genres.sortedBy { it.name })
            filters += CategoryFilter(forms.categories.sortedBy { it.name })
        } else {
            filters += Filter.Separator()
            filters += Filter.Header(
                if (filterFetchFailed) {
                    "Не удалось загрузить жанры. Нажмите «Сбросить», чтобы повторить"
                } else {
                    "Жанры загружаются. Нажмите «Сбросить», чтобы они появились"
                },
            )
        }
        return FilterList(filters)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_BRANCH
            title = "Переводы"
            entries = arrayOf("Все переводы", "Только основной перевод")
            entryValues = arrayOf(ReMangaUtils.BRANCH_ALL, ReMangaUtils.BRANCH_MAIN)
            setDefaultValue(ReMangaUtils.BRANCH_ALL)
            summary = "%s\nПри нескольких переводах команда указана у каждой главы — фильтруйте по ней в списке глав."
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PAID
            title = "Платные главы"
            entries = arrayOf("Показывать с пометкой $LOCK_MARK", "Скрывать некупленные")
            entryValues = arrayOf(PAID_SHOW, PAID_HIDE)
            setDefaultValue(PAID_SHOW)
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN
            title = "Токен авторизации"
            summary = "Нужен только для купленных глав, если вход через WebView не сработал. " +
                "Значение cookie «auth:token» с remanga.org."
            dialogTitle = "Токен авторизации"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SLUG = "slug:"

        private const val API_HOST = "api.remanga.org"
        private const val TOKEN_COOKIE = "auth:token"
        private const val TOKEN_COOKIE_ENCODED = "auth%3Atoken"

        private const val PAGE_SIZE = 30
        private const val CHAPTER_PAGE_SIZE = 50 // API maximum

        private const val ALT_PREFIX = "alt="
        private const val LOCK_MARK = "🔒 " // padlock

        private const val CREATOR_AUTHOR = 1
        private const val CREATOR_STUDIO = 2
        private const val CREATOR_ARTIST = 4

        private const val PREF_BRANCH = "pref_branch"
        private const val PREF_PAID = "pref_paid_chapters"
        private const val PREF_TOKEN = "pref_auth_token"
        private const val PAID_SHOW = "show"
        private const val PAID_HIDE = "hide"
    }
}
