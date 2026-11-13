package com.arnatech.internetarchive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

/**
 * Internet Archive (archive.org) provider.
 *
 * Only lists items whose uploader declared a Public Domain or Creative Commons
 * licence, and only English-language (or language-unspecified, e.g. silent) items.
 */
class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary
    )

    private val baseFilter =
        "mediatype:movies AND licenseurl:(*publicdomain* OR *creativecommons*) " +
            "AND (language:(eng OR english OR English) OR (*:* -language:[* TO *]))"

    override val mainPage = mainPageOf(
        "feature_films" to "Feature Films",
        "silent_films" to "Silent Films",
        "classic_tv" to "Classic TV",
        "Comedy_Films" to "Comedy",
        "SciFi_Horror" to "Sci-Fi & Horror",
        "Film_Noir" to "Film Noir",
        "classic_cartoons" to "Classic Cartoons",
        "prelinger" to "Vintage Educational Films",
    )

    // ---------- JSON models ----------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchDoc(
        @JsonProperty("identifier") val identifier: String,
        @JsonProperty("title") val title: JsonNode? = null,
        @JsonProperty("year") val year: JsonNode? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchInner(@JsonProperty("docs") val docs: List<SearchDoc> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(@JsonProperty("response") val response: SearchInner? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IaFile(
        @JsonProperty("name") val name: String,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("height") val height: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("track") val track: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Metadata(
        @JsonProperty("metadata") val metadata: JsonNode? = null,
        @JsonProperty("files") val files: List<IaFile> = emptyList(),
    )

    /** Passed from load() to loadLinks(). */
    data class LinkData(
        @JsonProperty("id") val id: String,
        @JsonProperty("stem") val stem: String,
    )

    // ---------- helpers ----------

    private fun JsonNode?.text(): String? = when {
        this == null || isNull -> null
        isArray -> if (size() > 0) get(0).asText() else null
        else -> asText()
    }?.takeIf { it.isNotBlank() }

    private fun JsonNode?.texts(): List<String> = when {
        this == null || isNull -> emptyList()
        isArray -> map { it.asText() }
        else -> asText().split(";").map { it.trim() }
    }.filter { it.isNotBlank() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun thumb(id: String) = "$mainUrl/services/img/$id"

    private fun fileUrl(id: String, name: String) =
        "$mainUrl/download/$id/" + name.split("/").joinToString("/") { enc(it).replace("+", "%20") }

    private val videoExt = listOf(".mp4", ".ogv", ".webm", ".m4v", ".mkv")

    /** Groups derivative files of one video under a common stem. */
    private fun stemOf(name: String): String =
        name.substringBeforeLast(".")
            .removeSuffix("_512kb")
            .removeSuffix(".ia")
            .removeSuffix(".asr")
            .removeSuffix(".en")

    private val fallbackExt = listOf(".mpeg", ".mpg", ".avi")

    private fun hasExt(f: IaFile, exts: List<String>) =
        exts.any { f.name.endsWith(it, ignoreCase = true) } && !f.name.contains(".thumbs/")

    /** Streamable videos; old MPEG/AVI files only count when an item has nothing better. */
    private fun videosOf(files: List<IaFile>): List<IaFile> {
        val primary = files.filter { hasExt(it, videoExt) }
        if (primary.isNotEmpty()) return primary
        return files.filter { hasExt(it, fallbackExt) }
    }

    private suspend fun runSearch(query: String, page: Int, rows: Int = 40): List<SearchResponse> {
        val url = "$mainUrl/advancedsearch.php?q=${enc(query)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
            "&sort%5B%5D=downloads+desc&rows=$rows&page=$page&output=json"
        val res = app.get(url).parsedSafe<SearchResult>() ?: return emptyList()
        return res.response?.docs.orEmpty().map { doc ->
            newMovieSearchResponse(
                doc.title.text() ?: doc.identifier,
                "$mainUrl/details/${doc.identifier}",
                TvType.Movie
            ) {
                posterUrl = thumb(doc.identifier)
                year = doc.year.text()?.take(4)?.toIntOrNull()
            }
        }
    }

    // ---------- MainAPI ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = runSearch("collection:${request.data} AND $baseFilter", page)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.replace(Regex("[\\\\\"():\\[\\]{}^~*?!+-]"), " ").trim()
        if (clean.isBlank()) return emptyList()
        return runSearch("title:($clean) AND $baseFilter", 1, 60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("/details/").substringBefore("/").substringBefore("?")
        val meta = app.get("$mainUrl/metadata/$id").parsedSafe<Metadata>() ?: return null
        val md = meta.metadata ?: return null

        val title = md["title"].text() ?: id
        val plot = md["description"].text()
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val year = (md["year"].text() ?: md["date"].text())?.take(4)?.toIntOrNull()
        val tags = md["subject"].texts().take(10)
        val collections = md["collection"].texts()

        // One entry per distinct video (original + derivatives share a stem)
        val stems = videosOf(meta.files)
            .map { stemOf(it.name) }
            .distinct()
            .sorted()
        if (stems.isEmpty()) return null

        val isCartoon = collections.any { it.contains("cartoon", ignoreCase = true) }
        val isDoc = collections.contains("prelinger")

        return if (stems.size == 1) {
            val type = when {
                isCartoon -> TvType.Cartoon
                isDoc -> TvType.Documentary
                else -> TvType.Movie
            }
            newMovieLoadResponse(title, url, type, LinkData(id, stems.first()).toJson()) {
                posterUrl = thumb(id)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            val episodes = stems.mapIndexed { i, stem ->
                val epTitle = meta.files.firstOrNull { stemOf(it.name) == stem && !it.title.isNullOrBlank() }?.title
                    ?: stem.substringAfterLast("/").replace('_', ' ')
                newEpisode(LinkData(id, stem).toJson()) {
                    name = epTitle
                    episode = i + 1
                    season = 1
                }
            }
            newTvSeriesLoadResponse(title, url, if (isCartoon) TvType.Cartoon else TvType.TvSeries, episodes) {
                posterUrl = thumb(id)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = mapper.readValue(data, LinkData::class.java)
        val meta = app.get("$mainUrl/metadata/${ld.id}").parsedSafe<Metadata>() ?: return false
        val group = meta.files.filter { stemOf(it.name) == ld.stem }
        val videoStems = videosOf(meta.files).map { stemOf(it.name) }.distinct()

        var found = false
        videosOf(group)
            // Prefer h.264 originals, then 512kb, then Ogg/WebM
            .sortedByDescending { it.height?.toIntOrNull() ?: 0 }
            .forEach { f ->
                val label = buildString {
                    append(name)
                    f.format?.let { append(" - ").append(it) }
                }
                callback(
                    newExtractorLink(name, label, fileUrl(ld.id, f.name), ExtractorLinkType.VIDEO) {
                        referer = "$mainUrl/"
                        quality = f.height?.toIntOrNull() ?: Qualities.Unknown.value
                    }
                )
                found = true
            }

        val isSub = { f: IaFile -> f.name.endsWith(".srt", true) || f.name.endsWith(".vtt", true) }
        // Single-video items sometimes name their subtitles differently from the video
        val subs = group.filter(isSub).ifEmpty {
            if (videoStems.size == 1) meta.files.filter(isSub) else emptyList()
        }
        subs.forEach { f ->
                val auto = f.name.contains(".asr.", ignoreCase = true)
                subtitleCallback(
                    newSubtitleFile(if (auto) "English (auto)" else "English", fileUrl(ld.id, f.name))
                )
            }

        return found
    }
}
