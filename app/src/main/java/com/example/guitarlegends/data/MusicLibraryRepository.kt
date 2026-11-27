package com.example.guitarlegends.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.guitarlegends.analysis.AudioAnalyzer
import com.example.guitarlegends.analysis.PcmAudioAnalyzer
import com.example.guitarlegends.model.AudioClassification
import com.example.guitarlegends.model.BpmConfidence
import com.example.guitarlegends.model.Playlist
import com.example.guitarlegends.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Repositório central de gerenciamento e persistência da Biblioteca Musical.
 * - Valida extensão permitida, MIME type e decodificabilidade real antes de aceitar a música.
 * - Permite clipes curtos legítimos (< 15s) com classificação explícita em vez de rejeição automática.
 * - Analisa áudio real via PcmAudioAnalyzer (RMS + transientes + BPM).
 * - Grava o manifesto com substituição atômica por arquivo temporário e propagação real de falhas.
 */
class MusicLibraryRepository(private val context: Context) {

    private val storageDir: File by lazy {
        File(context.filesDir, "imported_audio").apply {
            if (!exists()) mkdirs()
        }
    }

    private val manifestFile: File by lazy {
        File(context.filesDir, "library_manifest.json")
    }

    private val playlistsFile: File by lazy {
        File(context.filesDir, "playlists.json")
    }

    private val allowedExtensions = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")

    suspend fun loadAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        val importedTracks = loadImportedTracksFromManifest()
        val verifiedImported = mutableListOf<Track>()
        val canonicalIdByHash = mutableMapOf<String, String>()
        val duplicateIds = mutableMapOf<String, String>()
        val duplicateUris = mutableListOf<String>()
        val legacyFilesToDelete = mutableListOf<File>()
        var manifestChanged = false

        for (track in importedTracks) {
            val uriStr = track.uriString ?: continue
            if (!trackExists(uriStr)) {
                manifestChanged = true
                continue
            }

            var stableTrack = track
            // Migra faixas antigas do armazenamento privado para Music/Guitar Legends,
            // preservando o manifesto e as playlists antes de remover a cópia antiga.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriStr.startsWith("/")) {
                val legacyFile = File(uriStr)
                if (legacyFile.exists() && legacyFile.parentFile == storageDir) {
                    val sharedUri = runCatching {
                        persistToSharedMusic(legacyFile, legacyFile.name, track.mimeType)
                    }.getOrNull()
                    if (!sharedUri.isNullOrBlank() && sharedUri.startsWith("content://")) {
                        stableTrack = track.copy(uriString = sharedUri)
                        legacyFilesToDelete.add(legacyFile)
                        manifestChanged = true
                    }
                }
            }

            val digest = sha256Track(stableTrack.uriString) ?: continue
            val canonicalId = canonicalIdByHash[digest]
            if (canonicalId == null) {
                canonicalIdByHash[digest] = stableTrack.id
                verifiedImported.add(stableTrack)
                if (stableTrack != track) manifestChanged = true
            } else {
                duplicateIds[track.id] = canonicalId
                stableTrack.uriString?.let(duplicateUris::add)
                manifestChanged = true
            }
        }

        val discoveredTracks = discoverSharedTracks(canonicalIdByHash.keys.toMutableSet())
        if (discoveredTracks.isNotEmpty()) {
            verifiedImported.addAll(discoveredTracks)
            manifestChanged = true
        }

        val manifestSaved = if (manifestChanged) saveManifest(verifiedImported) else Result.success(Unit)
        if (manifestSaved.isSuccess) {
            if (duplicateIds.isNotEmpty()) {
                val remappedPlaylists = loadPlaylistsInternal().map { playlist ->
                    playlist.copy(
                        trackIds = playlist.trackIds.map { duplicateIds[it] ?: it }.distinct(),
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                if (savePlaylistsInternal(remappedPlaylists).isSuccess) {
                    duplicateUris.forEach(::deleteStoredUri)
                }
            }
            legacyFilesToDelete.forEach { file -> runCatching { file.delete() } }
        }

        AudioAnalyzer.BUILT_IN_TRACKS + verifiedImported
    }

    suspend fun importAudio(uri: Uri): Result<Track> = withContext(Dispatchers.IO) {
        var copiedFile: File? = null
        var persistedUriString: String? = null
        try {
            val originalName = getDisplayName(uri) ?: return@withContext Result.failure(
                IllegalArgumentException("Não foi possível identificar o nome do arquivo selecionado.")
            )
            val extension = getExtensionFromName(originalName).lowercase()
            if (extension !in allowedExtensions) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Formato '.$extension' não suportado. Utilize arquivos MP3, WAV, OGG, M4A, AAC ou FLAC."
                    )
                )
            }

            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (mimeType.isNotBlank() && !isAllowedMimeType(mimeType)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Tipo MIME '$mimeType' incompatível com arquivo de áudio musical.")
                )
            }

            // Impede importar novamente o mesmo arquivo binário, mesmo com outro nome.
            val incomingDigest = context.contentResolver.openInputStream(uri)?.use(::sha256)
                ?: return@withContext Result.failure(Exception("Não foi possível ler o áudio para verificar duplicidade."))
            val duplicate = loadImportedTracksFromManifest().firstOrNull { existing ->
                sha256Track(existing.uriString) == incomingDigest
            }
            if (duplicate != null) return@withContext Result.success(duplicate)

            val uniqueId = "track_user_${UUID.randomUUID().toString().take(8)}"
            val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(storageDir, "${uniqueId}_$sanitizedName")
            copiedFile = targetFile

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(
                    Exception("Não foi possível abrir o arquivo selecionado no aparelho.")
                )

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!targetFile.exists() || targetFile.length() <= 0L) {
                targetFile.delete()
                return@withContext Result.failure(Exception("O arquivo importado está vazio ou corrompido."))
            }

            val rawBaseTitle = originalName.substringBeforeLast(".").ifBlank { originalName }
            val nonMusicTerms = listOf(
                "sfx", "sound_effect", "efeito_sonoro", "ringtone",
                "dialogo", "dialogue", "notification", "vinheta"
            )
            if (nonMusicTerms.any { rawBaseTitle.lowercase().contains(it) }) {
                targetFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "O arquivo '$rawBaseTitle' parece ser um efeito sonoro ou diálogo e não uma música."
                    )
                )
            }

            // Análise PCM e verificação real de decodificabilidade
            val analysis = PcmAudioAnalyzer.analyzeFile(targetFile, extension)
            if (!analysis.isValidAudio || !analysis.isDecodable) {
                targetFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException(
                        analysis.errorMessage ?: "Arquivo de áudio corrompido ou ilegível."
                    )
                )
            }

            if (analysis.durationMs in 1L..999L) {
                targetFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("O áudio tem menos de 1 segundo e não pode gerar uma partida jogável.")
                )
            }

            val meta = extractTags(targetFile)
            val finalTitle = meta.first?.takeIf { it.isNotBlank() }?.trim() ?: rawBaseTitle
            val finalArtist = meta.second?.takeIf { it.isNotBlank() }?.trim() ?: "Artista Local"

            val isShort = analysis.durationMs in 1_000L..14_999L
            val classification = when {
                isShort -> AudioClassification.SHORT_CLIP
                analysis.bpmConfidence == BpmConfidence.LOW_UNRELIABLE -> AudioClassification.UNVERIFIED_STREAM
                else -> AudioClassification.MUSIC_FULL
            }

            persistedUriString = persistToSharedMusic(targetFile, sanitizedName, mimeType)

            val track = Track(
                id = uniqueId,
                title = finalTitle,
                artist = finalArtist,
                durationMs = analysis.durationMs,
                bpm = analysis.bpm,
                genre = "Arquivo Pessoal (${extension.uppercase()})",
                isBuiltIn = false,
                uriString = persistedUriString,
                bpmConfidence = analysis.bpmConfidence,
                bpmIsEstimated = analysis.bpmIsEstimated,
                onsetTimesMs = analysis.onsetTimesMs,
                isShortAudio = isShort,
                audioClassification = classification,
                mimeType = mimeType
            )

            val currentTracks = loadImportedTracksFromManifest().toMutableList()
            currentTracks.add(0, track)
            val saveResult = saveManifest(currentTracks)
            if (saveResult.isFailure) {
                targetFile.delete()
                return@withContext Result.failure(
                    saveResult.exceptionOrNull() ?: Exception("Erro ao gravar manifesto da biblioteca.")
                )
            }

            if (persistedUriString != targetFile.absolutePath) {
                targetFile.delete()
                copiedFile = null
            }
            Result.success(track)
        } catch (e: Exception) {
            persistedUriString?.takeIf { it.startsWith("content://") }?.let(::deleteStoredUri)
            copiedFile?.delete()
            Result.failure(Exception("Falha ao importar áudio: ${e.message}"))
        }
    }

    suspend fun createTemporaryTrack(
        audioFile: File,
        title: String,
        artist: String,
        mimeType: String
    ): Result<Track> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || !audioFile.isFile || audioFile.length() <= 0L) {
                throw IllegalArgumentException("O áudio temporário não está disponível.")
            }
            val extension = getExtensionFromName(audioFile.name).lowercase()
            if (extension !in allowedExtensions) throw IllegalArgumentException("Formato de áudio recebido não suportado.")
            val analysis = PcmAudioAnalyzer.analyzeFile(audioFile, extension)
            if (!analysis.isValidAudio || !analysis.isDecodable || analysis.durationMs < 1_000L) {
                throw IllegalArgumentException(analysis.errorMessage ?: "O áudio recebido não pôde ser validado.")
            }
            val isShort = analysis.durationMs in 1_000L..14_999L
            val classification = when {
                isShort -> AudioClassification.SHORT_CLIP
                analysis.bpmConfidence == BpmConfidence.LOW_UNRELIABLE -> AudioClassification.UNVERIFIED_STREAM
                else -> AudioClassification.MUSIC_FULL
            }
            Result.success(
                Track(
                    id = "track_temp_${UUID.randomUUID().toString().take(12)}",
                    title = title.ifBlank { audioFile.name.substringBeforeLast(".") }.take(160),
                    artist = artist.ifBlank { "Artista Local" }.take(120),
                    durationMs = analysis.durationMs,
                    bpm = analysis.bpm,
                    genre = "Temporária da partida (${extension.uppercase()})",
                    isBuiltIn = false,
                    uriString = audioFile.absolutePath,
                    bpmConfidence = analysis.bpmConfidence,
                    bpmIsEstimated = analysis.bpmIsEstimated,
                    onsetTimesMs = analysis.onsetTimesMs,
                    isShortAudio = isShort,
                    audioClassification = classification,
                    mimeType = mimeType
                )
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateTrackCalibration(
        trackId: String,
        manualBpm: Double?,
        syncOffsetMs: Long
    ): Result<Track> = withContext(Dispatchers.IO) {
        val currentTracks = loadImportedTracksFromManifest().toMutableList()
        val index = currentTracks.indexOfFirst { it.id == trackId }
        if (index < 0) {
            return@withContext Result.failure(IllegalArgumentException("Faixa importada não encontrada para calibração."))
        }
        val old = currentTracks[index]
        val updated = old.copy(
            manualBpmOverride = manualBpm?.coerceIn(40.0, 260.0),
            bpmConfidence = if (manualBpm != null) BpmConfidence.MANUAL else old.bpmConfidence,
            syncOffsetMs = syncOffsetMs.coerceIn(-1000L, 1000L)
        )
        currentTracks[index] = updated
        val saveRes = saveManifest(currentTracks)
        if (saveRes.isFailure) {
            return@withContext Result.failure(saveRes.exceptionOrNull() ?: Exception("Falha ao salvar calibração."))
        }
        Result.success(updated)
    }

    suspend fun deleteTrack(track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        if (track.isBuiltIn) {
            return@withContext Result.failure(IllegalStateException("Músicas originais do projeto não podem ser excluídas."))
        }

        return@withContext try {
            val currentTracks = loadImportedTracksFromManifest().filter { it.id != track.id }
            val saveResult = saveManifest(currentTracks)
            if (saveResult.isFailure) {
                return@withContext Result.failure(
                    saveResult.exceptionOrNull() ?: Exception("Falha ao atualizar o índice da biblioteca.")
                )
            }

            track.uriString?.let(::deleteStoredUri)

            // Limpa referências órfãs da faixa removida em todas as playlists locais
            val currentPlaylists = loadPlaylistsInternal()
            val cleanedPlaylists = currentPlaylists.map { pl ->
                pl.copy(trackIds = pl.trackIds.filterNot { it == track.id })
            }
            if (cleanedPlaylists != currentPlaylists) {
                savePlaylistsInternal(cleanedPlaylists)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Erro ao remover música da biblioteca: ${e.message}"))
        }
    }

    suspend fun loadPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        loadPlaylistsInternal()
    }

    suspend fun savePlaylists(playlists: List<Playlist>): Result<Unit> = withContext(Dispatchers.IO) {
        savePlaylistsInternal(playlists)
    }

    private fun loadPlaylistsInternal(): List<Playlist> {
        if (!playlistsFile.exists()) return emptyList()
        return try {
            val array = JSONArray(playlistsFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val name = obj.optString("name", "").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    val trackArr = obj.optJSONArray("trackIds")
                    val ids = buildList {
                        if (trackArr != null) {
                            for (j in 0 until trackArr.length()) {
                                val tid = trackArr.optString(j, "")
                                if (tid.isNotBlank()) add(tid)
                            }
                        }
                    }.distinct()
                    add(
                        Playlist(
                            id = id,
                            name = name,
                            trackIds = ids,
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePlaylistsInternal(playlists: List<Playlist>): Result<Unit> {
        val tempFile = File(context.filesDir, "playlists.json.tmp")
        return try {
            val array = JSONArray()
            for (pl in playlists) {
                array.put(
                    JSONObject().apply {
                        put("id", pl.id)
                        put("name", pl.name)
                        put("trackIds", JSONArray(pl.trackIds.distinct()))
                        put("updatedAtMs", pl.updatedAtMs)
                    }
                )
            }
            val payload = array.toString()
            tempFile.writeText(payload)
            if (playlistsFile.exists() && !playlistsFile.delete()) {
                playlistsFile.writeText(payload)
                tempFile.delete()
            } else if (!tempFile.renameTo(playlistsFile)) {
                playlistsFile.writeText(payload)
                tempFile.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            Result.failure(Exception("Falha ao salvar playlists: ${e.message}"))
        }
    }

    private fun persistToSharedMusic(source: File, displayName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return source.absolutePath

        val extension = getExtensionFromName(displayName).lowercase()
        val resolvedMime = when {
            extension == "wav" -> "audio/x-wav"
            mimeType.startsWith("audio/") -> mimeType
            extension == "mp3" -> "audio/mpeg"
            extension == "ogg" -> "audio/ogg"
            extension == "m4a" -> "audio/mp4"
            extension == "aac" -> "audio/aac"
            extension == "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/Guitar Legends/"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("Não foi possível criar o arquivo em Music/Guitar Legends.")

        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IllegalStateException("Não foi possível gravar a música no armazenamento compartilhado.")
            output.use { stream ->
                source.inputStream().use { input -> input.copyTo(stream) }
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(destination, ready, null, null)
            return destination.toString()
        } catch (error: Exception) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw error
        }
    }

    private fun trackExists(uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return if (uriString.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.read() >= 0 } == true
            }.getOrDefault(false)
        } else {
            val file = File(uriString)
            file.exists() && file.isFile && file.length() > 0L
        }
    }

    private fun sha256Track(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            if (uriString.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use(::sha256)
            } else {
                val file = File(uriString)
                if (file.exists() && file.isFile && file.length() > 0L) sha256(file) else null
            }
        }.getOrNull()
    }

    private fun deleteStoredUri(uriString: String) {
        runCatching {
            if (uriString.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(uriString), null, null)
            } else {
                val file = File(uriString)
                if (file.parentFile == storageDir) file.delete()
            }
        }
    }

    private fun discoverSharedTracks(knownHashes: MutableSet<String>): List<Track> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val restored = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val relativePath = "${Environment.DIRECTORY_MUSIC}/Guitar Legends/"
        return try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val mediaUri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val digest = sha256Track(mediaUri.toString()) ?: continue
                    if (!knownHashes.add(digest)) continue

                    val displayName = cursor.getString(nameColumn).orEmpty().ifBlank { "Música importada.mp3" }
                    val extension = getExtensionFromName(displayName).lowercase()
                    if (extension !in allowedExtensions) continue
                    val temp = File(context.cacheDir, "restore_${UUID.randomUUID()}.$extension")
                    try {
                        val input = context.contentResolver.openInputStream(mediaUri) ?: continue
                        input.use { source ->
                            FileOutputStream(temp).use { output -> source.copyTo(output) }
                        }
                        val analysis = PcmAudioAnalyzer.analyzeFile(temp, extension)
                        if (!analysis.isValidAudio || !analysis.isDecodable || analysis.durationMs < 1_000L) continue

                        val rawTitle = cursor.getString(titleColumn).orEmpty()
                            .ifBlank { displayName.substringBeforeLast(".") }
                        val artist = cursor.getString(artistColumn).orEmpty().ifBlank { "Artista Local" }
                        val duration = analysis.durationMs.takeIf { it > 0L }
                            ?: cursor.getLong(durationColumn).coerceAtLeast(0L)
                        val short = duration in 1_000L..14_999L
                        restored.add(
                            Track(
                                id = "track_user_${digest.take(16)}",
                                title = rawTitle,
                                artist = artist,
                                durationMs = duration,
                                bpm = analysis.bpm,
                                genre = "Arquivo Pessoal (${extension.uppercase()})",
                                isBuiltIn = false,
                                uriString = mediaUri.toString(),
                                bpmConfidence = analysis.bpmConfidence,
                                bpmIsEstimated = analysis.bpmIsEstimated,
                                onsetTimesMs = analysis.onsetTimesMs,
                                isShortAudio = short,
                                audioClassification = when {
                                    short -> AudioClassification.SHORT_CLIP
                                    analysis.bpmConfidence == BpmConfidence.LOW_UNRELIABLE -> AudioClassification.UNVERIFIED_STREAM
                                    else -> AudioClassification.MUSIC_FULL
                                },
                                mimeType = cursor.getString(mimeColumn).orEmpty()
                            )
                        )
                    } finally {
                        runCatching { temp.delete() }
                    }
                }
            }
            restored
        } catch (_: Exception) {
            restored
        }
    }

    private fun sha256(file: File): String =
        file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isAllowedMimeType(mime: String): Boolean {
        val lower = mime.lowercase()
        return lower.startsWith("audio/") ||
            lower == "application/ogg" ||
            lower == "application/octet-stream"
    }

    private fun extractTags(file: File): Pair<String?, String?> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            title to artist
        } catch (_: Throwable) {
            null to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadImportedTracksFromManifest(): List<Track> {
        if (!manifestFile.exists()) return emptyList()
        return try {
            val jsonText = manifestFile.readText()
            val array = JSONArray(jsonText)
            val list = mutableListOf<Track>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val onsetsArray = obj.optJSONArray("onsetTimesMs")
                val onsets = buildList {
                    if (onsetsArray != null) {
                        for (j in 0 until onsetsArray.length()) {
                            add(onsetsArray.getLong(j))
                        }
                    }
                }
                val manualBpm = if (obj.has("manualBpmOverride") && !obj.isNull("manualBpmOverride")) {
                    obj.getDouble("manualBpmOverride")
                } else null

                val track = Track(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    durationMs = obj.getLong("durationMs"),
                    bpm = obj.getDouble("bpm"),
                    genre = obj.optString("genre", "Arquivo Pessoal"),
                    isBuiltIn = false,
                    uriString = obj.getString("uriString"),
                    bpmConfidence = runCatching {
                        BpmConfidence.valueOf(obj.optString("bpmConfidence", BpmConfidence.MEDIUM.name))
                    }.getOrDefault(BpmConfidence.MEDIUM),
                    bpmIsEstimated = obj.optBoolean("bpmIsEstimated", false),
                    manualBpmOverride = manualBpm,
                    onsetTimesMs = onsets,
                    isShortAudio = obj.optBoolean("isShortAudio", false),
                    audioClassification = runCatching {
                        AudioClassification.valueOf(obj.optString("audioClassification", AudioClassification.MUSIC_FULL.name))
                    }.getOrDefault(AudioClassification.MUSIC_FULL),
                    mimeType = obj.optString("mimeType", ""),
                    syncOffsetMs = obj.optLong("syncOffsetMs", 0L)
                )
                list.add(track)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveManifest(tracks: List<Track>): Result<Unit> {
        val tempFile = File(context.filesDir, "library_manifest.json.tmp")
        return try {
            val array = JSONArray()
            for (t in tracks) {
                if (t.isBuiltIn) continue
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("durationMs", t.durationMs)
                    put("bpm", t.bpm)
                    put("genre", t.genre)
                    put("uriString", t.uriString ?: "")
                    put("bpmConfidence", t.bpmConfidence.name)
                    put("bpmIsEstimated", t.bpmIsEstimated)
                    if (t.manualBpmOverride != null) {
                        put("manualBpmOverride", t.manualBpmOverride)
                    }
                    put("onsetTimesMs", JSONArray(t.onsetTimesMs))
                    put("isShortAudio", t.isShortAudio)
                    put("audioClassification", t.audioClassification.name)
                    put("mimeType", t.mimeType)
                    put("syncOffsetMs", t.syncOffsetMs)
                }
                array.put(obj)
            }

            tempFile.writeText(array.toString())
            if (manifestFile.exists() && !manifestFile.delete()) {
                // Sobrescreve diretamente se delete falhar em algum sistema de arquivos
                manifestFile.writeText(array.toString())
                tempFile.delete()
            } else if (!tempFile.renameTo(manifestFile)) {
                manifestFile.writeText(array.toString())
                tempFile.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            Result.failure(Exception("Falha ao persistir biblioteca musical: ${e.message}"))
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment
        }
        return name
    }

    private fun getExtensionFromName(name: String): String {
        return if (name.contains(".")) name.substringAfterLast(".") else ""
    }
}
