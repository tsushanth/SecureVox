package com.securevox.app.speakers

/** The speaker count a user can ask for: null/0 = automatic, otherwise 1..[MAX]. */
object SpeakerCount {
    const val MAX = 8
    /** Counts offered as chips before "More". */
    const val QUICK_MAX = 6

    /** Normalises a stored or requested value: anything outside 1..MAX means automatic (null). */
    fun normalize(n: Int?): Int? = n?.takeIf { it in 1..MAX }

    /** sherpa-onnx FastClusteringConfig.numClusters: -1 = automatic (threshold), else the fixed count. */
    fun clusters(n: Int?): Int = normalize(n) ?: -1
}
