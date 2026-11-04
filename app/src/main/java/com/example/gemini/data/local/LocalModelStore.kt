package com.example.gemini.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class LocalModel(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val importedAt: Long
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024L * 1024L * 1024L ->
                "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024L * 1024L ->
                "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
            else -> "$sizeBytes bytes"
        }
}

class LocalModelStore(context: Context) {

    private val appContext = context.applicationContext

    private val modelDirectory =
        File(appContext.filesDir, "local_models")

    private val preferences =
        appContext.getSharedPreferences("local_model_store", Context.MODE_PRIVATE)

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())

    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    init {
        modelDirectory.mkdirs()
        refresh()
    }

    suspend fun importGguf(uri: Uri): LocalModel =
        withContext(Dispatchers.IO) {

            val displayName = queryDisplayName(uri)
                ?: "model-${System.currentTimeMillis()}.gguf"

            if (!displayName.lowercase().endsWith(".gguf")) {
                throw IOException("Please select a .gguf model file.")
            }

            val safeName = displayName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "model.gguf" }

            val id = UUID.randomUUID().toString()

            val destination =
                File(modelDirectory, "${id}_$safeName")

            val temporary =
                File(modelDirectory, "${id}_$safeName.part")

            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open selected file.")

                input.use { source ->
                    temporary.outputStream().use { target ->
                        source.copyTo(target)
                    }
                }

                if (!temporary.exists() || temporary.length() <= 0L) {
                    throw IOException("Selected model file is empty.")
                }

                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }

                val model = LocalModel(
                    id = id,
                    name = safeName,
                    path = destination.absolutePath,
                    sizeBytes = destination.length(),
                    importedAt = System.currentTimeMillis()
                )

                val updated = _models.value + model

                saveModels(updated)
                _models.value = updated

                model

            } catch (error: Exception) {
                temporary.delete()
                destination.delete()
                throw error
            }
        }

    fun refresh() {
        val stored = readModels()

        val valid = stored.filter {
            File(it.path).isFile
        }

        _models.value = valid

        if (valid.size != stored.size) {
            saveModels(valid)
        }
    }

    suspend fun deleteModel(modelId: String): Boolean =
        withContext(Dispatchers.IO) {

            val model = _models.value.firstOrNull {
                it.id == modelId
            } ?: return@withContext false

            val file = File(model.path)

            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete ${model.name}.")
            }

            val updated = _models.value.filterNot {
                it.id == modelId
            }

            saveModels(updated)
            _models.value = updated

            true
        }

    fun getModelFile(modelId: String): File? {
        val model = _models.value.firstOrNull {
            it.id == modelId
        } ?: return null

        return File(model.path).takeIf { it.isFile }
    }

    private fun queryDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

                if (column >= 0) {
                    return cursor.getString(column)
                }
            }
        }

        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    private fun saveModels(models: List<LocalModel>) {
        val array = JSONArray()

        models.forEach { model ->
            array.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("path", model.path)
                    put("sizeBytes", model.sizeBytes)
                    put("importedAt", model.importedAt)
                }
            )
        }

        preferences.edit()
            .putString("models_json", array.toString())
            .apply()
    }

    private fun readModels(): List<LocalModel> {
        val raw = preferences.getString("models_json", null)
            ?: return emptyList()

        return try {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)

                    add(
                        LocalModel(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            path = item.getString("path"),
                            sizeBytes = item.getLong("sizeBytes"),
                            importedAt = item.getLong("importedAt")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}