package tv.vega.tv.data.providers

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Mirrors web ProviderSource. Custom https sources carry their manifestUrl. */
@Serializable
data class ProviderSource(
    val author: String = "",
    val url: String = "",
    val manifestUrl: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class ProviderExtension(
    val value: String = "",
    val display_name: String = "",
    val source: ProviderSourceRef = ProviderSourceRef(),
    val version: String = "",
    val icon: String = "",
    val disabled: Boolean = false,
    val type: String = "global",
    val installed: Boolean = false,
    val hasSettings: Boolean = false,
    val path: String? = null,
    val installedAt: Long = 0L,
    val lastUpdated: Long = 0L,
)

@Serializable
data class ProviderSourceRef(
    val author: String = "",
    val url: String = "",
)

@Serializable
data class ProviderModules(
    val value: String = "",
    val sourceAuthor: String = "",
    val version: String = "",
    val posts: String? = null,
    val meta: String? = null,
    val stream: String? = null,
    val catalog: String? = null,
    val episodes: String? = null,
    val settings: String? = null,
    val cachedAt: Long = 0L,
)

internal fun isNewerProviderVersion(candidate: String, current: String): Boolean {
    fun parts(version: String): List<Int> = version.split('.').map { raw ->
        raw.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val newer = parts(candidate)
    val installed = parts(current)
    for (i in 0 until maxOf(newer.size, installed.size)) {
        val n = newer.getOrElse(i) { 0 }
        val c = installed.getOrElse(i) { 0 }
        if (n > c) return true
        if (n < c) return false
    }
    return false
}

/**
 * Port of web `createProviderSource` / `getProviderFilesUrl` /
 * `isValidProviderPath` (helpers.ts). Accepts a repo or raw URL on GitHub,
 * Codeberg, Bitbucket or GitLab, any other https manifest URL, or a short
 * name: "author" (GitHub), "author@cb" (Codeberg), "author@bb" (Bitbucket)
 * or "author@gl" (GitLab). Short names use the default vega-providers repo
 * on the main branch. Non-GitHub authors carry the host suffix as part of
 * the stored key so the same name on two hosts never collides.
 */
object ProviderUrls {
    const val RAW_HOST = "raw.githubusercontent.com"
    const val GITHUB_HOST = "github.com"
    const val CODEBERG_HOST = "codeberg.org"
    const val BITBUCKET_HOST = "bitbucket.org"
    const val GITLAB_HOST = "gitlab.com"
    const val DEFAULT_REPO = "vega-providers"
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_AUTHOR = "Zenda-Cross"

    private val KNOWN_HOSTS = setOf(RAW_HOST, GITHUB_HOST, CODEBERG_HOST, BITBUCKET_HOST, GITLAB_HOST)
    private val HOST_SUFFIXES = mapOf("gh" to GITHUB_HOST, "cb" to CODEBERG_HOST, "bb" to BITBUCKET_HOST, "gl" to GITLAB_HOST)

    private data class Parsed(
        val host: String,
        val author: String,
        val repo: String,
        val branch: String,
    )

    fun rawBase(host: String, author: String, repo: String = DEFAULT_REPO, branch: String = DEFAULT_BRANCH) =
        when (host) {
            CODEBERG_HOST -> "https://$CODEBERG_HOST/$author/$repo/raw/branch/$branch"
            BITBUCKET_HOST -> "https://$BITBUCKET_HOST/$author/$repo/raw/$branch"
            GITLAB_HOST -> "https://$GITLAB_HOST/$author/$repo/-/raw/$branch"
            else -> "https://$RAW_HOST/$author/$repo/refs/heads/$branch"
        }

    private fun sourceKey(host: String, author: String): String = when (host) {
        CODEBERG_HOST -> "$author@cb"
        BITBUCKET_HOST -> "$author@bb"
        GITLAB_HOST -> "$author@gl"
        else -> author
    }

    fun defaultSource() = ProviderSource(
        author = DEFAULT_AUTHOR,
        url = rawBase(RAW_HOST, DEFAULT_AUTHOR),
        isDefault = true,
    )

    /**
     * Path segments without a trailing "manifest.json", so a pasted link to
     * the manifest file itself also works. HttpUrl decodes segments already.
     */
    private fun segmentsOf(url: okhttp3.HttpUrl): List<String> {
        val segs = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segs.lastOrNull() == "manifest.json") segs.removeAt(segs.size - 1)
        return segs
    }

    private fun joinBranch(segments: List<String>): String =
        if (segments.isEmpty()) DEFAULT_BRANCH else segments.joinToString("/")

    private fun parseRawGithub(url: okhttp3.HttpUrl): Parsed? {
        if (url.host.removePrefix("www.") != RAW_HOST) return null
        val segs = segmentsOf(url)
        if (segs.size < 4) return null
        val author = segs[0]
        val repo = segs[1]
        var branch = DEFAULT_BRANCH
        if (segs[2] == "refs" && segs[3] == "heads" && segs.size > 4) branch = joinBranch(segs.drop(4))
        if (author.isEmpty() || repo.isEmpty()) return null
        return Parsed(GITHUB_HOST, author, repo, branch)
    }

    private fun parseGithubRepo(url: okhttp3.HttpUrl): Parsed? {
        if (url.host.removePrefix("www.") != GITHUB_HOST) return null
        val segs = segmentsOf(url)
        if (segs.size < 2) return null
        val author = segs[0]
        val repo = segs[1]
        var branch = DEFAULT_BRANCH
        if (segs.getOrNull(2) == "tree" && segs.size > 3) branch = joinBranch(segs.drop(3))
        if (author.isEmpty() || repo.isEmpty()) return null
        return Parsed(GITHUB_HOST, author, repo, branch)
    }

    // codeberg.org/{owner}/{repo}[/src|raw/branch/{branch}]
    private fun parseCodeberg(url: okhttp3.HttpUrl): Parsed? {
        if (url.host.removePrefix("www.") != CODEBERG_HOST) return null
        val segs = segmentsOf(url)
        if (segs.size < 2) return null
        val author = segs[0]
        val repo = segs[1]
        var branch = DEFAULT_BRANCH
        val view = segs.getOrNull(2)
        if ((view == "src" || view == "raw") && segs.getOrNull(3) == "branch" && segs.size > 4) {
            branch = joinBranch(segs.drop(4))
        }
        return Parsed(CODEBERG_HOST, author, repo, branch)
    }

    // bitbucket.org/{workspace}/{repo}[/src|raw/{branch}]
    private fun parseBitbucket(url: okhttp3.HttpUrl): Parsed? {
        if (url.host.removePrefix("www.") != BITBUCKET_HOST) return null
        val segs = segmentsOf(url)
        if (segs.size < 2) return null
        val author = segs[0]
        val repo = segs[1]
        var branch = DEFAULT_BRANCH
        val view = segs.getOrNull(2)
        if ((view == "src" || view == "raw") && segs.size > 3) branch = joinBranch(segs.drop(3))
        return Parsed(BITBUCKET_HOST, author, repo, branch)
    }

    // gitlab.com/{group}[/{subgroup}...]/{repo}[/-/tree|raw|blob/{branch}].
    // The namespace may hold subgroups, so the repo is the segment before "-".
    private fun parseGitlab(url: okhttp3.HttpUrl): Parsed? {
        if (url.host.removePrefix("www.") != GITLAB_HOST) return null
        val segs = segmentsOf(url)
        val sep = segs.indexOf("-")
        val projectPath = if (sep >= 0) segs.take(sep) else segs
        if (projectPath.size < 2) return null
        val repo = projectPath.last()
        val author = projectPath.dropLast(1).joinToString("/")
        var branch = DEFAULT_BRANCH
        val view = if (sep >= 0) segs.getOrNull(sep + 1) else null
        if (sep >= 0 && (view == "tree" || view == "raw" || view == "blob") && segs.size > sep + 2) {
            branch = joinBranch(segs.drop(sep + 2))
        }
        return Parsed(GITLAB_HOST, author, repo, branch)
    }

    private fun toProviderSource(p: Parsed) = ProviderSource(
        author = sourceKey(p.host, p.author),
        url = rawBase(p.host, p.author, p.repo, p.branch),
    )

    /**
     * Any other https URL: a manifest file (path ending in .json) or the folder
     * holding manifest.json. Provider files are resolved against that folder,
     * and the author key is the host and folder path, so two sites never share
     * provider data.
     */
    private fun toCustomProviderSource(url: okhttp3.HttpUrl): ProviderSource {
        require(url.scheme == "https") { "Custom provider sources must use https" }
        val segs = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        val manifestFile = if (segs.isNotEmpty() && segs.last().endsWith(".json", ignoreCase = true)) {
            segs.removeAt(segs.size - 1)
        } else {
            "manifest.json"
        }
        val folder = if (segs.isEmpty()) "" else "/" + segs.joinToString("/")
        val base = "${url.scheme}://${url.host}$folder"
        val search = url.encodedQuery?.let { "?$it" } ?: ""
        return ProviderSource(
            author = url.host.lowercase() + folder,
            url = base,
            manifestUrl = "$base/$manifestFile$search",
        )
    }

    /** Accepts @author, repo/raw URLs on the four hosts, or any https URL. */
    fun parse(input: String): ProviderSource {
        val t = input.trim().trimEnd('/')
        require(t.isNotEmpty()) { "Provider source value is required" }
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            val url = t.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid provider source URL")
            for (parser in listOf(::parseRawGithub, ::parseGithubRepo, ::parseCodeberg, ::parseBitbucket, ::parseGitlab)) {
                parser(url)?.let { return toProviderSource(it) }
            }
            if (url.host.lowercase().removePrefix("www.") in KNOWN_HOSTS) {
                throw IllegalArgumentException("Unsupported URL for this host")
            }
            return toCustomProviderSource(url)
        }
        val m = Regex("^@?([^\\s@/]+)(?:@([a-z]+))?$", RegexOption.IGNORE_CASE).matchEntire(t)
            ?: throw IllegalArgumentException("Invalid provider source name")
        val host = HOST_SUFFIXES[(m.groupValues[2].ifEmpty { "gh" }).lowercase()]
            ?: throw IllegalArgumentException("Invalid provider source name")
        return toProviderSource(Parsed(host, m.groupValues[1], DEFAULT_REPO, DEFAULT_BRANCH))
    }

    /**
     * True for a manifest `path` that stays inside the source folder: relative
     * segments only, no "." or "..", no query or backslash. "%2e%2e" is read
     * as ".." by URL parsers, so encoded dots are refused too.
     */
    fun isValidProviderPath(path: String): Boolean {
        if (path.contains('\\') || path.contains('?') || path.contains('#') ||
            path.contains("%2e", ignoreCase = true)
        ) return false
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return false
        if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
        return segs.none { it == "." || it == ".." }
    }

    /**
     * Folder holding a provider's files: the manifest `path` under the source
     * folder ("/providers/netflix" and "providers/netflix" mean the same), or
     * dist/{value} when the manifest gives none.
     */
    fun filesUrl(sourceUrl: String, value: String, path: String? = null): String {
        val base = sourceUrl.trimEnd('/')
        if (path.isNullOrEmpty()) return "$base/dist/$value"
        require(isValidProviderPath(path)) { "Invalid provider path: $path" }
        return "$base/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }
}
