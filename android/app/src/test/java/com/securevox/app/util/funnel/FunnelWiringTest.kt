package com.securevox.app.util.funnel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the hook points: a step that is declared but called from nowhere (a hook deleted in a refactor) fails here.
 * Reads the source tree, like NewStringsLocalizedTest reads the resources.
 */
class FunnelWiringTest {
    private fun mainSrc(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private val allSource: List<Pair<String, String>> by lazy {
        mainSrc().walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to it.readText() }.toList()
    }

    private fun usedOutside(skip: Set<String>, needle: String): Boolean =
        allSource.any { (path, text) -> skip.none { path.endsWith(it) } && text.contains(needle) }

    @Test
    fun everyStepExceptTheRatingSeamIsRecordedSomewhere() {
        val skip = setOf("util/funnel/Funnel.kt", "util/funnel/RatingSeam.kt")
        val ratingSteps = setOf(Step.RATING_ELIGIBLE, Step.RATING_BLOCKED, Step.RATING_ASK, Step.RATING_FLOW_FAILED, Step.RATING_FLOW_RESULT)
        val missing = Step.entries.filter { it !in ratingSteps && !usedOutside(skip, "Step.${it.name}") }
        assertTrue("steps with no hook: $missing", missing.isEmpty())
    }

    @Test
    fun theHookHelpersAreCalledFromTheApp() {
        val skip = setOf("util/funnel/FunnelHooks.kt")
        val helpers = listOf(
            "onAppOpened", "onRecordingStarted", "onImportStarted", "onTranscriptionStarted", "onTranscriptionCompleted",
            "onTranscriptionFailed", "onDownloadStarted", "onDownloadCompleted"
        )
        val missing = helpers.filter { !usedOutside(skip, "FunnelHooks.$it(") }
        assertTrue("helpers never called: $missing", missing.isEmpty())
    }

    @Test
    fun noRatingKitListenerIsReferencedYet() {
        // The seam must not depend on the unmerged RatingKit change.
        assertTrue(allSource.none { (_, text) -> text.contains("setEventListener") && !text.contains("RatingSeam") && !text.contains("RatingFunnelAdapter") })
    }
}
