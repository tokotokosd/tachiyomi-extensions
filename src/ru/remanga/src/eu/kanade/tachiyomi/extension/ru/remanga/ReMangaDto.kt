package eu.kanade.tachiyomi.extension.ru.remanga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// Shapes of api.remanga.org v2 responses (checked 2026-11-07).
// Only the fields the extension reads are declared; the shared Json instance ignores the rest.

// ============================== Lists ==============================

/** `/api/v2/search/catalog/`: `next` is a relative URL or null. */
@Serializable
class CatalogResponse(
    val next: String? = null,
    val results: List<TitleCard> = emptyList(),
)

/** `/api/v2/search/`: page-number pagination in `meta`. */
@Serializable
class SearchResponse(
    val meta: SearchMeta? = null,
    val results: List<TitleCard> = emptyList(),
)

@Serializable
class SearchMeta(
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
class TitleCard(
    val id: Long,
    val dir: String,
    @SerialName("main_name") val mainName: String? = null,
    @SerialName("secondary_name") val secondaryName: String? = null,
    val cover: Cover? = null,
)

@Serializable
class Cover(
    val low: String? = null,
    val mid: String? = null,
    val high: String? = null,
)

// ============================== Details ==============================

/** `/api/v2/titles/{dir}/` */
@Serializable
class TitleDetails(
    val id: Long,
    val dir: String,
    @SerialName("main_name") val mainName: String? = null,
    @SerialName("secondary_name") val secondaryName: String? = null,
    @SerialName("another_name") val anotherName: String? = null,
    val description: String? = null,
    @SerialName("issue_year") val issueYear: Int? = null,
    @SerialName("avg_rating") val avgRating: JsonPrimitive? = null,
    @SerialName("count_rating") val countRating: Int? = null,
    val cover: Cover? = null,
    val type: Named? = null,
    val status: Named? = null,
    @SerialName("translate_status") val translateStatus: Named? = null,
    @SerialName("age_limit") val ageLimit: Named? = null,
    val genres: List<Named> = emptyList(),
    val categories: List<Named> = emptyList(),
    val creators: List<Creator> = emptyList(),
    val branches: List<Branch> = emptyList(),
    @SerialName("is_licensed") val isLicensed: Boolean = false,
    @SerialName("count_chapters") val countChapters: Int? = null,
)

@Serializable
class Named(
    val id: Int,
    val name: String,
)

/** `type`: 1 author, 2 studio, 3 publisher (platform), 4 artist; see `/api/v2/titles/creators/{id}/`. */
@Serializable
class Creator(
    val name: String? = null,
    val type: Int? = null,
)

@Serializable
class Branch(
    val id: Long,
    @SerialName("count_chapters") val countChapters: Int = 0,
    @SerialName("total_votes") val totalVotes: Long = 0,
    val publishers: List<Publisher> = emptyList(),
)

@Serializable
class Publisher(
    val name: String,
)

// ============================== Chapters ==============================

/** `/api/v2/titles/chapters/?branch_id=`: `next` is a page number or null. */
@Serializable
class ChapterListResponse(
    val next: Int? = null,
    val results: List<ChapterItem> = emptyList(),
)

@Serializable
class ChapterItem(
    val id: Long,
    val tome: Int? = null,
    val chapter: String,
    val name: String? = null,
    val index: Int? = null,
    @SerialName("upload_date") val uploadDate: String? = null,
    @SerialName("pub_date") val pubDate: String? = null,
    @SerialName("is_paid") val isPaid: Boolean = false,
    @SerialName("is_bought") val isBought: Boolean? = null,
    @SerialName("is_published") val isPublished: Boolean = true,
    val publishers: List<Publisher> = emptyList(),
)

// ============================== Pages ==============================

/**
 * `/api/v2/titles/chapters/{id}/`. `pages` is a list of pages, each split into
 * one or more vertical slices; empty for paid chapters the user has not bought.
 */
@Serializable
class ChapterPages(
    val id: Long,
    @SerialName("is_paid") val isPaid: Boolean = false,
    @SerialName("is_bought") val isBought: Boolean? = null,
    val pages: List<List<PageImage>> = emptyList(),
    val server: ImageServer? = null,
)

@Serializable
class PageImage(
    val link: String,
)

@Serializable
class ImageServer(
    val link: String? = null,
    @SerialName("fallback_link") val fallbackLink: String? = null,
)

// ============================== Filters ==============================

/** `/api/forms/titles/?get=...` */
@Serializable
class FilterForms(
    val content: FilterFormsContent,
)

@Serializable
class FilterFormsContent(
    val genres: List<Named> = emptyList(),
    val categories: List<Named> = emptyList(),
    val types: List<Named> = emptyList(),
    val status: List<Named> = emptyList(),
    @SerialName("age_limit") val ageLimit: List<Named> = emptyList(),
)
