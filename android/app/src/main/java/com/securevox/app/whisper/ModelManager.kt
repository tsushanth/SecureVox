package com.securevox.app.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Whisper model downloads and storage.
 * Models are downloaded from HuggingFace and stored in app's internal storage.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PART_SUFFIX = ".part"
        private const val LEGACY_TMP_SUFFIX = ".tmp"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        // HuggingFace model URLs
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    }

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    init {
        cleanLegacyTempFiles()
        refreshModelList()
    }

    /**
     * Remove `.tmp` files left by app versions that did not support resuming.
     */
    private fun cleanLegacyTempFiles() {
        modelsDir.listFiles()
            ?.filter { it.name.endsWith(LEGACY_TMP_SUFFIX) }
            ?.forEach {
                Log.i(TAG, "Removing legacy partial download: ${it.name}")
                it.delete()
            }
    }

    /**
     * Refresh the list of available models and their download status.
     */
    fun refreshModelList() {
        val models = WhisperModel.entries.map { model ->
            val file = getModelFile(model)
            val installed = ModelIntegrity.isInstalled(file, model)

            if (installed) {
                // Models installed before digest tracking have no marker yet.
                ModelIntegrity.verifyInBackground(file, model) { ok ->
                    if (!ok) refreshModelList()
                }
            }

            ModelInfo(
                model = model,
                isDownloaded = installed,
                fileSizeBytes = if (file.exists()) file.length() else 0L
            )
        }
        _availableModels.value = models
    }

    /**
     * Get the file path for a model.
     */
    fun getModelPath(model: WhisperModel): String {
        return getModelFile(model).absolutePath
    }

    /**
     * Check if a model is installed and passes size verification.
     */
    fun isModelDownloaded(model: WhisperModel): Boolean {
        return ModelIntegrity.isInstalled(getModelFile(model), model)
    }

    /**
     * Get the default model (tiny, always available).
     */
    fun getDefaultModel(): WhisperModel {
        return WhisperModel.TINY
    }

    /**
     * Get the best available downloaded model.
     */
    fun getBestAvailableModel(): WhisperModel {
        // Prefer larger models if downloaded
        return WhisperModel.entries
            .sortedByDescending { it.sizeBytes }
            .firstOrNull { isModelDownloaded(it) }
            ?: WhisperModel.TINY
    }

    /**
     * Download a model from HuggingFace.
     *
     * Downloads are resumable: partial data is kept in a `.part` file and
     * continued with an HTTP Range request on the next attempt. The digest is
     * verified before the file is promoted into place, so a partial or corrupt
     * download can never be mistaken for an installed model.
     */
    suspend fun downloadModel(model: WhisperModel): Result<File> = withContext(Dispatchers.IO) {
        val file = getModelFile(model)

        if (ModelIntegrity.isInstalled(file, model)) {
            Log.i(TAG, "Model already installed: ${model.fileName}")
            return@withContext Result.success(file)
        }

        // A file of the wrong size is unusable; start over rather than trust it.
        if (file.exists()) {
            Log.w(TAG, "Removing invalid model file: ${model.fileName}")
            file.delete()
            ModelIntegrity.deleteMarker(file)
        }

        val partFile = File(modelsDir, "${model.fileName}$PART_SUFFIX")
        var resumeFrom = if (partFile.exists()) partFile.length() else 0L

        if (resumeFrom > model.sizeBytes) {
            partFile.delete()
            resumeFrom = 0L
        }

        // A previous attempt may have finished downloading but not been promoted.
        if (resumeFrom == model.sizeBytes) {
            if (ModelIntegrity.verify(partFile, model) && promote(partFile, file, model)) {
                return@withContext Result.success(file)
            }
            partFile.delete()
            resumeFrom = 0L
        }

        _downloadState.value = DownloadState.Downloading(model, resumeFrom, model.sizeBytes)

        try {
            val connection = openConnection(model, resumeFrom)
            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                connection.disconnect()
                throw Exception("Server returned HTTP $responseCode")
            }

            // 200 means the server ignored the Range header, so restart from zero.
            val appending = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (!appending && resumeFrom > 0) {
                Log.i(TAG, "Server does not support resume, restarting ${model.fileName}")
                partFile.delete()
                resumeFrom = 0L
            }

            connection.inputStream.use { input ->
                FileOutputStream(partFile, appending).use { output ->
                    if (!appending) output.channel.truncate(0)

                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalBytesRead = resumeFrom

                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        _downloadState.value = DownloadState.Downloading(
                            model = model,
                            downloadedBytes = totalBytesRead,
                            totalBytes = model.sizeBytes
                        )
                    }
                }
            }
            connection.disconnect()

            if (partFile.length() != model.sizeBytes) {
                throw Exception(
                    "Incomplete download: got ${partFile.length()} of ${model.sizeBytes} bytes"
                )
            }

            if (!ModelIntegrity.verify(partFile, model)) {
                partFile.delete()
                throw Exception("Downloaded model failed checksum verification")
            }

            if (!promote(partFile, file, model)) {
                throw Exception("Could not move model into place")
            }

            Log.i(TAG, "Model downloaded successfully: ${model.fileName}")
            _downloadState.value = DownloadState.Completed(model)
            refreshModelList()

            Result.success(file)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${model.fileName}", e)
            _downloadState.value = DownloadState.Error(model, e.message ?: "Download failed")
            Result.failure(e)
        }
    }

    private fun openConnection(model: WhisperModel, resumeFrom: Long): HttpURLConnection {
        val url = URL("$BASE_URL/${model.fileName}")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        return connection
    }

    /**
     * Move a verified part file into place and record its digest.
     */
    private fun promote(partFile: File, target: File, model: WhisperModel): Boolean {
        if (!partFile.renameTo(target)) {
            Log.w(TAG, "Rename failed for ${model.fileName}, falling back to copy")
            try {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Copy failed for ${model.fileName}", e)
                return false
            }
        }
        ModelIntegrity.verify(target, model)
        return target.exists() && target.length() == model.sizeBytes
    }

    /**
     * Fully re-verify an installed model, repairing it if the file is corrupt.
     */
    suspend fun repairModel(model: WhisperModel): Result<File> = withContext(Dispatchers.IO) {
        val file = getModelFile(model)
        if (ModelIntegrity.verify(file, model)) {
            refreshModelList()
            return@withContext Result.success(file)
        }
        Log.w(TAG, "Model is corrupt, removing: ${model.fileName}")
        file.delete()
        ModelIntegrity.deleteMarker(file)
        refreshModelList()
        downloadModel(model)
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(model: WhisperModel): Boolean {
        // Don't allow deleting the tiny model if it's the only one
        if (model == WhisperModel.TINY) {
            val otherModelsExist = WhisperModel.entries
                .filter { it != WhisperModel.TINY }
                .any { isModelDownloaded(it) }

            if (!otherModelsExist) {
                Log.w(TAG, "Cannot delete tiny model - it's the only available model")
                return false
            }
        }

        val file = getModelFile(model)
        val deleted = file.delete()
        ModelIntegrity.deleteMarker(file)
        File(modelsDir, "${model.fileName}$PART_SUFFIX").delete()

        if (deleted) {
            Log.i(TAG, "Model deleted: ${model.fileName}")
            refreshModelList()
        }

        return deleted
    }

    /**
     * Cancel the current download.
     *
     * Partial data is intentionally kept so the next attempt can resume.
     * Use [deleteModel] to discard a download entirely.
     */
    fun cancelDownload() {
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Get total storage used by models.
     */
    fun getTotalStorageUsed(): Long {
        return modelsDir.listFiles()
            ?.filter { it.name.endsWith(".bin") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    /**
     * Ensure the default model is present, preferring a bundled copy.
     */
    suspend fun ensureDefaultModelExists(): Boolean = withContext(Dispatchers.IO) {
        val tinyModel = WhisperModel.TINY
        val file = getModelFile(tinyModel)

        if (ModelIntegrity.isInstalled(file, tinyModel)) {
            return@withContext true
        }

        // Check if bundled in assets
        try {
            context.assets.open(tinyModel.fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (ModelIntegrity.verify(file, tinyModel)) {
                Log.i(TAG, "Copied bundled model from assets")
                refreshModelList()
                return@withContext true
            }
            Log.w(TAG, "Bundled model failed verification, discarding")
            file.delete()
            ModelIntegrity.deleteMarker(file)
        } catch (e: Exception) {
            Log.d(TAG, "No bundled model in assets, will need to download")
        }

        // Download if not bundled
        val result = downloadModel(tinyModel)
        result.isSuccess
    }

    private fun getModelFile(model: WhisperModel): File {
        return File(modelsDir, model.fileName)
    }
}

/**
 * Information about a model and its download status.
 */
data class ModelInfo(
    val model: WhisperModel,
    val isDownloaded: Boolean,
    val fileSizeBytes: Long
)

/**
 * State of model download.
 */
sealed class DownloadState {
    object Idle : DownloadState()

    data class Downloading(
        val model: WhisperModel,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState() {
        val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        val progressPercent: Int get() = (progress * 100).toInt()
    }

    data class Completed(val model: WhisperModel) : DownloadState()

    data class Error(val model: WhisperModel, val message: String) : DownloadState()
}
