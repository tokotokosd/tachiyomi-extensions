package eu.kanade.tachiyomi.extension.ru.remanga

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

internal class SortFilter : Filter.Sort(
    "Сортировка",
    SORTS.map { it.first }.toTypedArray(),
    Selection(0, false),
) {
    fun apply(url: HttpUrl.Builder) {
        val selection = state ?: Selection(0, false)
        val field = SORTS[selection.index].second
        url.addQueryParameter("ordering", if (selection.ascending) field else "-$field")
    }

    companion object {
        // Values accepted by /api/v2/search/catalog/ (an unknown value returns no results).
        val SORTS = listOf(
            "Популярность" to "views",
            "Лайки" to "votes",
            "Рейтинг" to "rating",
            "Новизна" to "id",
            "Обновление" to "chapter_date",
            "Количество глав" to "count_chapters",
        )
        const val POPULAR = 0
        const val LATEST = 4
    }
}

internal class CheckBoxOption(name: String, val id: Int) : Filter.CheckBox(name)

internal open class CheckBoxGroup(
    name: String,
    private val param: String,
    options: List<Named>,
) : Filter.Group<CheckBoxOption>(name, options.map { CheckBoxOption(it.name, it.id) }) {
    fun apply(url: HttpUrl.Builder) {
        state.filter { it.state }.forEach { url.addQueryParameter(param, it.id.toString()) }
    }
}

internal class TriStateOption(name: String, val id: Int) : Filter.TriState(name)

/** Include sends `{param}=id`, exclude sends `exclude_{param}=id`. */
internal open class TriStateGroup(
    name: String,
    private val param: String,
    options: List<Named>,
) : Filter.Group<TriStateOption>(name, options.map { TriStateOption(it.name, it.id) }) {
    fun apply(url: HttpUrl.Builder) {
        state.forEach {
            when (it.state) {
                Filter.TriState.STATE_INCLUDE -> url.addQueryParameter(param, it.id.toString())
                Filter.TriState.STATE_EXCLUDE -> url.addQueryParameter("exclude_$param", it.id.toString())
            }
        }
    }
}

internal class TypeFilter : CheckBoxGroup("Тип", "types", TYPES)
internal class StatusFilter : CheckBoxGroup("Статус", "status", STATUSES)
internal class AgeFilter : CheckBoxGroup("Возрастное ограничение", "age_limit", AGE_LIMITS)
internal class GenreFilter(options: List<Named>) : TriStateGroup("Жанры", "genres", options)
internal class CategoryFilter(options: List<Named>) : TriStateGroup("Категории", "categories", options)

internal class MinChaptersFilter : Filter.Text("Минимум глав")

// These three lists are small and stable; genres and categories are loaded from the site.
private val TYPES = listOf(
    Named(1, "Манга"),
    Named(2, "Манхва"),
    Named(3, "Маньхуа"),
    Named(4, "Западный комикс"),
    Named(5, "Рукомикс"),
    Named(6, "Индонезийский комикс"),
    Named(22, "Оригиналы"),
    Named(7, "Другое"),
)

private val STATUSES = listOf(
    Named(2, "Продолжается"),
    Named(1, "Закончен"),
    Named(3, "Заморожен"),
    Named(5, "Анонс"),
    Named(6, "Лицензировано"),
)

private val AGE_LIMITS = listOf(
    Named(0, "Для всех"),
    Named(1, "16+"),
    Named(2, "18+"),
)
