package com.shiniese.motionswap.core.model

/**
 * Decides which file name to trust when two sources disagree.
 *
 * The sibling lookup keys off the image's base name, and a `content://media/picker/...` row is
 * not always reporting the name the file has on disk. Where that happens the search runs
 * `DISPLAY_NAME LIKE '<name>.%'` against the video collection and matches nothing at all, so
 * the image reports no sibling video no matter how the folder and timestamps turn out. Album
 * import reads the collection directly, which is why the two paths behaved differently for what
 * looked like one bug.
 *
 * The rule follows the principle the Swift extractor is built on: metadata is evidence, not
 * fact. A row read back out of MediaStore is closer to the file than whatever the picker chose
 * to hand over, so it wins — but only when it is actually a name, never to overwrite a good name
 * with a blank one.
 */
object SiblingName {

    /**
     * Whether [name] could be the name of a real photo on disk.
     *
     * A picker row whose name the provider declined to identify, or which is cloud-backed,
     * reports a placeholder (`image_42`, a bare id) with no extension. This app only merges
     * JPEGs, so a name with no extension is evidence the string is synthetic rather than the
     * file being exotic — which is what makes it safe to go looking for the real name instead.
     */
    fun isUsableFileName(name: String): Boolean =
        name.isNotBlank() && name.contains('.') && !name.endsWith('.')

    /**
     * The name to search the video collection with.
     *
     * @param pickedName the name the picker URI's own row reported.
     * @param collectionName the name the same id reports in `MediaStore.Images`, or null when
     *   that lookup found nothing.
     * @return the collection's name whenever it is usable, else the picker's, else the best
     *   available string — never blank when either source had something to say.
     */
    fun authoritative(pickedName: String, collectionName: String?): String =
        collectionName?.takeIf { isUsableFileName(it) }
            ?: pickedName.takeIf { isUsableFileName(it) }
            ?: collectionName?.takeIf { it.isNotBlank() }
            ?: pickedName
}