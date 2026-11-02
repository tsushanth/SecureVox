package com.securevox.app.whisper

/** What the first-run setup card should say besides the download button. */
enum class SetupNotice {
    /** Ready / idle: nothing to flag. */
    NONE,
    /** "You are offline". */
    OFFLINE,
    /** The last attempt failed for a reason other than being offline. */
    ERROR
}

/**
 * Pure decisions behind the setup screen, so they can be unit tested without Android.
 *
 * The "offline" error left behind by a failed attempt is stale as soon as the network is back, so it must not
 * keep the screen in the offline state until the user taps Retry.
 */
object SetupStatus {

    /** True only for a link that is known to work. UNKNOWN (captive portal, still validating) is not "online". */
    fun isOnline(network: NetworkState): Boolean =
        network == NetworkState.METERED || network == NetworkState.UNMETERED

    /**
     * @param network the live network state.
     * @param errorProblem the problem of the last failed download, or null when there is none.
     */
    fun notice(network: NetworkState, errorProblem: DownloadProblem?): SetupNotice = when {
        network == NetworkState.OFFLINE -> SetupNotice.OFFLINE
        errorProblem == null -> SetupNotice.NONE
        // Stale: the attempt failed for lack of a connection, and the connection is back.
        errorProblem == DownloadProblem.OFFLINE -> if (isOnline(network)) SetupNotice.NONE else SetupNotice.OFFLINE
        else -> SetupNotice.ERROR
    }

    /**
     * True when the download state holds an offline failure that the returned connection has made stale.
     * Never true for Downloading / Completed / Idle or any other failure, so an in-flight download is untouched.
     */
    fun shouldClearOfflineError(state: DownloadState, network: NetworkState): Boolean =
        state is DownloadState.Error && state.problem == DownloadProblem.OFFLINE && isOnline(network)

    /**
     * Bytes still to fetch when a partly downloaded file exists, else null. Used so the button says how much
     * is left instead of the full size.
     */
    fun remainingWhenResuming(model: WhisperModel, partialBytes: Long): Long? =
        if (partialBytes > 0L && partialBytes < model.sizeBytes) DownloadPolicy.remainingBytes(model, partialBytes) else null
}
