package com.example.guitarlegends.data

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Repositório central de gerenciamento e persistência da Biblioteca Musical.
 * - Valida extensão permitida, MIME type e decodificabilidade real antes de aceitar a música.
 * - Permite clipes curtos legítimos (< 15s) com classificação explícita em vez de rejeição automática.
 * - Analisa áudio real via PcmAudioAnalyzer (RMS + transientes + BPM).
 * - Grava o manifesto com substituição atômica por arquivo temporário e propagação real de falhas.
 */
class MusicLibraryRepository(private val context: Context) {

    // Serializa importações para impedir que dois arquivos iguais sejam gravados simultaneamente.
    private val importMutex = Mutex()

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
        val seenContentHashes = mutableSetOf<String>()

        for (track in importedTracks) {
            val uriStr = track.uriString
            if (!uriStr.isNullOrBlank()) {
                val file = File(uriStr)
                if (file.exists() && file.length() > 0L) {
                    val fingerprinted = if (track.contentHash.isBlank()) {
                        track.copy(contentHash = sha256(file))
                    } else track
                    val fingerprint = fingerprinted.contentHash.lowercase()
                    // Corrige também duplicatas que já estavam gravadas no manifesto.
                    if (fingerprint.isBlank() || seenContentHashes.add(fingerprint)) {
                        verifiedImported.add(fingerprinted)
                    }
                }
            }
        }

        if (verifiedImported != importedTracks) {
            saveManifest(verifiedImported)
        }

        AudioAnalyzer.BUILT_IN_TRACKS + verifiedImported
    }

    suspend fun importAudio(uri: Uri): Result<Track> = importMutex.withLock {
        withContext(Dispatchers.IO) {
        var copiedFile: File? = null
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

            val contentHash = sha256(targetFile)
            val manifestTracks = loadImportedTracksFromManifest()
            val existingTracks = manifestTracks.mapNotNull { existing ->
                val existingFile = existing.uriString?.let(::File)
                if (existingFile == null || !existingFile.exists() || existingFile.length() <= 0L) {
                    null
                } else if (existing.contentHash.isBlank()) {
                    existing.copy(contentHash = sha256(existingFile))
                } else existing
            }
            if (existingTracks != manifestTracks) {
                val migration = saveManifest(existingTracks)
                if (migration.isFailure) {
                    targetFile.delete()
                    return@withContext Result.failure(
                        migration.exceptionOrNull() ?: Exception("Falha ao atualizar fingerprints da biblioteca.")
                    )
                }
            }
            val duplicate = existingTracks.firstOrNull {
                it.contentHash.isNotBlank() && it.contentHash.equals(contentHash, ignoreCase = true)
            }
            if (duplicate != null) {
                targetFile.delete()
                return@withContext Result.success(duplicate)
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

            val track = Track(
                id = uniqueId,
                title = finalTitle,
                artist = finalArtist,
                durationMs = analysis.durationMs,
                bpm = analysis.bpm,
                genre = "Arquivo Pessoal (${extension.uppercase()})",
                isBuiltIn = false,
                uriString = targetFile.absolutePath,
                bpmConfidence = analysis.bpmConfidence,
                bpmIsEstimated = analysis.bpmIsEstimated,
                onsetTimesMs = analysis.onsetTimesMs,
                isShortAudio = isShort,
                audioClassification = classification,
                mimeType = mimeType,
                contentHash = contentHash
            )

            val currentTracks = existingTracks.toMutableList()
            currentTracks.add(0, track)
            val saveResult = saveManifest(currentTracks)
            if (saveResult.isFailure) {
                targetFile.delete()
                return@withContext Result.failure(
                    saveResult.exceptionOrNull() ?: Exception("Erro ao gravar manifesto da biblioteca.")
                )
            }

            Result.success(track)
        } catch (e: Exception) {
            copiedFile?.delete()
            Result.failure(Exception("Falha ao importar áudio: ${e.message}"))
        }
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

            track.uriString?.let { path ->
                val file = File(path)
                if (file.exists() && file.parentFile == storageDir) {
                    file.delete()
                }
            }

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
        val tracks = loadAllTracks()
        val stored = loadPlaylistsInternal()
        val normalized = normalizePlaylistTracks(stored, tracks)
        if (normalized != stored) savePlaylistsInternal(normalized)
        normalized
    }

    suspend fun savePlaylists(playlists: List<Playlist>): Result<Unit> = withContext(Dispatchers.IO) {
        val tracks = loadAllTracks()
        savePlaylistsInternal(normalizePlaylistTracks(playlists, tracks))
    }

    /**
     * Remove referências duplicadas por ID e por conteúdo de áudio.
     * Mantém a primeira ocorrência e não compara títulos, evitando juntar músicas distintas.
     */
    private fun normalizePlaylistTracks(
        playlists: List<Playlist>,
        tracks: List<Track>
    ): List<Playlist> {
        val tracksById = tracks.associateBy { it.id }
        return playlists.map { playlist ->
            val seenIds = mutableSetOf<String>()
            val seenHashes = mutableSetOf<String>()
            val normalizedIds = playlist.trackIds.filter { trackId ->
                if (!seenIds.add(trackId)) {
                    false
                } else {
                    val hash = tracksById[trackId]?.contentHash
                        ?.takeIf { it.isNotBlank() }
                        ?.lowercase()
                    hash == null || seenHashes.add(hash)
                }
            }
            if (normalizedIds == playlist.trackIds) playlist
            else playlist.copy(trackIds = normalizedIds, updatedAtMs = System.currentTimeMillis())
        }
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
                    syncOffsetMs = obj.optLong("syncOffsetMs", 0L),
                    contentHash = obj.optString("contentHash", "")
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
                    put("contentHash", t.contentHash)
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
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
