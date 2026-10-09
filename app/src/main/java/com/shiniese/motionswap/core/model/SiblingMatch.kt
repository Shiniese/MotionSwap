package com.shiniese.motionswap.core.model

/** A video that shares its base name with a still image. */
data class VideoCandidate(
    /** Folder-relative path, e.g. `Camera/`. Blank when MediaStore would not report it. */
    val relativePath: String,
    val dateTakenMillis: Long,
)

/** What the caller knows about the still image it is trying to pair. */
data class StillRef(
    val relativePath: String,
    val dateTakenMillis: Long,
)

/**
 * Chooses which same-named video belongs to a still image.
 *
 * Pure and Android-free on purpose: the pairing rule is the one part of merge that can go
 * quietly wrong, because a wrong pairing still produces a file that opens fine — it just
 * shows the wrong video. So it is kept testable off-device rather than buried in a
 * `ContentResolver` query.
 *
 * The rule, in order:
 *
 * 1. **Folder known** — only candidates in that exact folder are eligible, then the one
 *    closest in capture time wins. `IMG_0001.mp4` exists in dozens of camera rolls, so
 *    crossing folders here would be a guess, and a guess produces a plausible-looking,
 *    wrong motion photo.
 * 2. **Folder unknown** — a photo-picked URI frequently reports no `RELATIVE_PATH`. Refusing
 *    outright would make the system picker useless, so capture time becomes the evidence
 *    instead of the folder: the strictly closest candidate wins, and only when it is actually
 *    closer. Candidates tied on capture time stay unpaired, because then nothing is left to
 *    tell them apart.
 *
 * Capture time beats file order because re-saving an image rewrites its row without
 * touching the video's, so the video's `DATE_TAKEN` stays closest to the original shot. It is
 * also the only evidence left when the folder is unknown, which is why the fallback still
 * refuses on a tie rather than picking whichever row the cursor happened to return first.
 */
object SiblingMatch {

    /** Index of the winning candidate, or null when nothing is safe to pair. */
    fun pickIndex(still: StillRef, candidates: List<VideoCandidate>): Int? {
        if (candidates.isEmpty()) return null

        val folderKnown = still.relativePath.isNotBlank()
        val folder = normalize(still.relativePath)

        val eligible = candidates.indices.filter { index ->
            val candidate = candidates[index]
            !folderKnown || normalize(candidate.relativePath) == folder
        }
        if (eligible.isEmpty()) return null

        // Folder is known, so capture time is a genuine tie-break.
        if (folderKnown) {
            return eligible.minByOrNull { index -> gap(candidates[index], still) }
        }

        // Folder is unknown. Refusing here is what made every picked photo report "no sibling
        // video", so capture time is the fallback evidence — but it has to actually decide
        // something. A strict `<` means a tie between two candidates pairs neither.
        var best = -1
        var bestGap = Long.MAX_VALUE
        var tied = false
        for (index in eligible) {
            val candidateGap = gap(candidates[index], still)
            when {
                candidateGap < bestGap -> {
                    best = index
                    bestGap = candidateGap
                    tied = false
                }
                candidateGap == bestGap -> tied = true
            }
        }
        return if (best >= 0 && !tied) best else null
    }

    fun pick(still: StillRef, candidates: List<VideoCandidate>): VideoCandidate? =
        pickIndex(still, candidates)?.let { candidates[it] }

    /**
     * Whether a video file name is a same-base-name sibling of an image.
     *
     * The comparison is on the full base name, never a prefix: a `LIKE 'IMG_1.%'` prefilter
     * will also return `IMG_10.mp4`, and pairing those would silently attach the wrong video.
     * `MediaImage.baseName` and `SiblingVideoFinder` both derive the base name by cutting at
     * the last dot, so `a.b.c` pairs with `a.b.mp4` and not with `a.mp4`.
     */
    fun matchesBaseName(videoDisplayName: String, imageBaseName: String): Boolean =
        videoDisplayName.substringBeforeLast('.', videoDisplayName) == imageBaseName

    /**
     * Escapes the LIKE wildcards in a file name so it can be used inside a `LIKE` pattern.
     *
     * File names are user-controlled: a photo named `100%_off.jpg` would otherwise match every
     * video starting with `100`. The caller must pair this with an `ESCAPE '\'` clause and
     * still verify with [matchesBaseName].
     */
    fun likePatternFor(imageBaseName: String): String = buildString {
        imageBaseName.forEach { c ->
            if (c == '%' || c == '_' || c == '\\') append('\\')
            append(c)
        }
        append(".%")
    }

    private fun gap(candidate: VideoCandidate, still: StillRef): Long =
        kotlin.math.abs(candidate.dateTakenMillis - still.dateTakenMillis)

    /**
     * MediaStore reports folder paths with a trailing slash (`Camera/`), but picker-provided
     * paths are not guaranteed to agree, so compare them slash-normalised.
     */
    internal fun normalize(path: String): String = path.trim().trimEnd('/')
}
