package com.securevox.app.util.funnel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The seam between the rating prompt library and the ledger.
 *
 * The library ("RatingKit") is consumed by local path and its event listener (a single
 * `RatingKit.setEventListener`) is still an unmerged change, so this app does NOT depend on it. Instead the
 * ledger declares the library-neutral events below. When that listener ships, wire it in one place,
 * right after `RatingKit.init(...)` in SecureVoxApp.onCreate:
 *
 * ```
 * val seam = RatingFunnelAdapter(funnel(), System::currentTimeMillis, ZoneId.systemDefault())
 * RatingKit.setEventListener { e -> seam.onEvent(e.toFunnelEvent()) }   // map the library event to RatingFunnelEvent
 * ```
 *
 * The listener replaces any earlier one, so the app (not the library) owns composition. Keep the mapping
 * closed-set: unknown reasons go to [RatingReason.OTHER], never through as text.
 */
sealed interface RatingFunnelEvent {
    data object Eligible : RatingFunnelEvent
    data class Blocked(val reason: RatingReason) : RatingFunnelEvent
    data object AskAttempted : RatingFunnelEvent
    data object FlowRequestFailed : RatingFunnelEvent
    data class FlowResult(val likelyShown: Boolean) : RatingFunnelEvent
}

class RatingFunnelAdapter(
    private val funnel: Funnel,
    private val clock: () -> Long,
    private val zone: ZoneId
) {
    private val blockedToday = HashMap<RatingReason, LocalDate>()

    fun onEvent(event: RatingFunnelEvent) {
        when (event) {
            RatingFunnelEvent.Eligible -> funnel.step(Step.RATING_ELIGIBLE)
            // The library reports a block after every finished transcription; one per reason per day is plenty.
            is RatingFunnelEvent.Blocked -> {
                val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
                if (blockedToday[event.reason] != today) {
                    blockedToday[event.reason] = today
                    funnel.step(Step.RATING_BLOCKED, event.reason)
                }
            }
            RatingFunnelEvent.AskAttempted -> funnel.step(Step.RATING_ASK)
            RatingFunnelEvent.FlowRequestFailed -> funnel.step(Step.RATING_FLOW_FAILED)
            is RatingFunnelEvent.FlowResult ->
                funnel.step(Step.RATING_FLOW_RESULT, if (event.likelyShown) RatingShown.SHOWN else RatingShown.NOT_SHOWN)
        }
    }
}
