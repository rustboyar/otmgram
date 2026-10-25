package dev.otmgram.ui.embed

import java.net.URI

/**
 * A link to a video or post the app plays in the site's own embed player: YouTube videos
 * and Shorts, Instagram posts and reels, TikTok videos.
 */
data class MediaLink(
    val site: Site,
    /** The video's or post's id; null for a TikTok short link, which has to be followed first. */
    val id: String?,
    /** The link as sent, opened in the site's app or the browser on request. */
    val url: String,
    /** Shorts, reels and TikToks are upright. */
    val upright: Boolean,
    /** Where a YouTube link starts, in seconds. */
    val start: Int = 0,
) {
    enum class Site { YouTube, Instagram, TikTok }

    /** The page with the site's player; null until a short link is followed. */
    val embedUrl: String?
        get() = id?.let { id ->
            when (site) {
                // The no-cookie host sets no cookies until the video plays.
                Site.YouTube -> "https://www.youtube-nocookie.com/embed/$id?autoplay=1&playsinline=1" + if (start > 0) "&start=$start" else ""
                Site.Instagram -> "https://www.instagram.com/p/$id/embed/"
                // The player TikTok's own embed code uses.
                Site.TikTok -> "https://www.tiktok.com/player/v1/$id?embed_from=oembed&music_info=1&description=1&autoplay=1"
            }
        }

    companion object {
        /** The media link [url] is, or null for any other link. */
        fun parse(url: String): MediaLink? {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val path = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            return when (host) {
                "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> {
                    val (id, upright) = when (path.firstOrNull()) {
                        "watch" -> query["v"] to false
                        "shorts" -> path.getOrNull(1) to true
                        "live", "embed", "v" -> path.getOrNull(1) to false
                        else -> null to false
                    }
                    id?.takeIf(YOUTUBE_ID::matches)?.let { MediaLink(Site.YouTube, it, url, upright, startOf(query)) }
                }
                "youtu.be" -> path.firstOrNull()?.takeIf(YOUTUBE_ID::matches)?.let { MediaLink(Site.YouTube, it, url, upright = false, start = startOf(query)) }
                "instagram.com" -> {
                    // instagram.com/p/CODE, /reel/CODE, and the same after a user name.
                    val at = path.indexOfFirst { it in INSTAGRAM_KINDS }
                    path.getOrNull(at + 1)?.takeIf { at >= 0 && INSTAGRAM_CODE.matches(it) }?.let {
                        MediaLink(Site.Instagram, it, url, upright = path[at] != "p")
                    }
                }
                "tiktok.com" -> when {
                    path.firstOrNull() == "t" && path.size >= 2 -> MediaLink(Site.TikTok, null, url, upright = true)
                    else -> tiktokId(path)?.let { MediaLink(Site.TikTok, it, url, upright = true) }
                }
                "vm.tiktok.com", "vt.tiktok.com" -> path.firstOrNull()?.let { MediaLink(Site.TikTok, null, url, upright = true) }
                else -> null
            }
        }

        /** tiktok.com/@user/video/ID, /v/ID.html, /embed/v2/ID, /player/v1/ID. */
        internal fun tiktokId(path: List<String>): String? {
            val candidate = when {
                path.size >= 3 && path[1] == "video" -> path[2]
                path.firstOrNull() == "v" -> path.getOrNull(1)?.removeSuffix(".html")
                path.firstOrNull() == "embed" -> path.lastOrNull()
                path.firstOrNull() == "player" -> path.getOrNull(2)
                else -> null
            }
            return candidate?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        }

        /** "t=90", "t=1m30s" or "start=90", in seconds. */
        private fun startOf(query: Map<String, String>): Int {
            val value = query["t"] ?: query["start"] ?: return 0
            value.toIntOrNull()?.let { return it.coerceAtLeast(0) }
            val match = TIME.matchEntire(value) ?: return 0
            val (h, m, s) = match.destructured
            return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        }

        private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
        private val INSTAGRAM_CODE = Regex("[A-Za-z0-9_-]{5,}")
        private val INSTAGRAM_KINDS = setOf("p", "reel", "reels", "tv")
        private val TIME = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
    }
}
