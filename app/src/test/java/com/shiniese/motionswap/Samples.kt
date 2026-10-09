package com.shiniese.motionswap

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Locates the real-device files under `samples/` and reads them.
 *
 * The samples are untracked (107 MB of camera output), so they are present on some machines
 * and absent on others. Every test that needs one calls [read] or [require] first, which turns
 * "this checkout has no samples" into a skipped test rather than a failure. That distinction
 * matters: a missing sample is not a broken build, but a *present* sample that fails is.
 */
object Samples {

    /**
     * The repository's `samples/` directory, found by walking up from the working directory.
     *
     * Gradle runs tests with the module directory as the working directory, which varies by
     * task and IDE, so a fixed relative path is not dependable. An override is honoured first
     * for cases where the samples live elsewhere entirely.
     */
    val root: File? by lazy {
        System.getenv("MOTIONSWAP_SAMPLES")?.let { File(it) }?.takeIf { it.isDirectory }
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "samples") }
                .firstOrNull { it.isDirectory }
    }

    /** Bytes of the named sample, or a skipped test when it is not in this checkout. */
    fun read(name: String): ByteArray {
        val file = file(name)
        assumeTrue(file != null, "sample not available: samples/$name")
        return file!!.readBytes()
    }

    /**
     * The named sample's file, or null when absent.
     *
     * Use when a test needs the size or a subrange rather than the whole file — the larger
     * samples run to 17 MB and there is no reason to pull one into memory to check its length.
     */
    fun file(name: String): File? {
        val dir = root ?: return null
        val direct = File(dir, name)
        if (direct.isFile) return direct
        // `name` may carry a subdirectory path; nothing under samples/ is nested right now,
        // but this keeps a caller written against the old layout working.
        return null
    }

    /** The named sample's size in bytes, skipping the test when it is absent. */
    fun size(name: String): Long {
        val file = file(name)
        assumeTrue(file != null, "sample not available: samples/$name")
        return file!!.length()
    }

    /** The names of every sample present, for tests that sweep the whole set. */
    fun available(): List<String> =
        root?.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()
}