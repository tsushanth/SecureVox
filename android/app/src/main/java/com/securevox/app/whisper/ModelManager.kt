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
        private const val BUFFER_SIZE = 8192

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
        refreshModelList()
    }

    /**
     * Refresh the list of available models and their download status.
     */
    fun refreshModelList() {
        val models = WhisperModel.entries.map { model ->
            val file = getModelFile(model)
            ModelInfo(
                model = model,
                isDownloaded = file.exists(),
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
     * Check if a model is downloaded.
     */
    fun isModelDownloaded(model: WhisperModel): Boolean {
        return getModelFile(model).exists()
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
     */
    suspend fun downloadModel(model: WhisperModel): Result<File> = withContext(Dispatchers.IO) {
        val file = getModelFile(model)

        if (file.exists()) {
            Log.i(TAG, "Model already exists: ${model.fileName}")
            return@withContext Result.success(file)
        }

        _downloadState.value = DownloadState.Downloading(model, 0, model.sizeBytes)

        try {
            val url = URL("$BASE_URL/${model.fileName}")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP $responseCode")
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            val tempFile = File(modelsDir, "${model.fileName}.tmp")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        _downloadState.value = DownloadState.Downloading(
                            model = model,
                            downloadedBytes = totalBytesRead,
                            totalBytes = contentLength
                        )
                    }
                }
            }

            // Rename temp file to final name
            tempFile.renameTo(file)

            Log.i(TAG, "Model downloaded successfully: ${model.fileName}")
            _downloadState.value = DownloadState.Completed(model)
            refreshModelList()

            Result.success(file)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${model.fileName}", e)
            _downloadState.value = DownloadState.Error(model, e.message ?: "Download failed")

            // Clean up partial download
            File(modelsDir, "${model.fileName}.tmp").delete()

            Result.failure(e)
        }
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

        if (deleted) {
            Log.i(TAG, "Model deleted: ${model.fileName}")
            refreshModelList()
        }

        return deleted
    }

    /**
     * Cancel ongoing download.
     */
    fun cancelDownload() {
        _downloadState.value = DownloadState.Idle
        // Clean up any temp files
        modelsDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
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
     * Copy bundled model from assets on first launch.
     */
    suspend fun ensureDefaultModelExists(): Boolean = withContext(Dispatchers.IO) {
        val tinyModel = WhisperModel.TINY
        val file = getModelFile(tinyModel)

        if (file.exists()) {
            return@withContext true
        }

        // Check if bundled in assets
        try {
            context.assets.open(tinyModel.fileName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied bundled model from assets")
            refreshModelList()
            return@withContext true
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
