package com.example.guitarlegends.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.example.guitarlegends.model.Track
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class MultiplayerAudioTransferCloud(
    @DocumentId val id: String = "",
    val roomCode: String = "",
    val ownerUid: String = "",
    val sourceTrackId: String = "",
    val title: String = "",
    val artist: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val chunkCount: Int = 0,
    val status: String = "UPLOADING",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

/**
 * Transferência de áudio por blocos no Firestore já utilizado pelo jogo.
 * Limite conservador de 8 MiB por faixa para manter cada documento abaixo de 1 MiB
 * e evitar adicionar Firebase Storage ou infraestrutura paga.
 */
class MultiplayerAudioTransferRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    companion object {
        const val MAX_TRACK_BYTES = 8L * 1024L * 1024L
        const val CHUNK_BYTES = 512 * 1024
    }

    private fun roomRef(roomCode: String) =
        firestore.collection("multiplayer_rooms").document(roomCode.trim().uppercase())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extensionFor(track: Track): String {
        val uriString = track.uriString.orEmpty()
        val name = if (uriString.startsWith("content://")) {
            runCatching {
                context.contentResolver.query(
                    Uri.parse(uriString),
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
                }.orEmpty()
            }.getOrDefault("")
        } else File(uriString).name
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")) return fromName
        return when {
            track.genre.contains("MP3", ignoreCase = true) || track.mimeType == "audio/mpeg" -> "mp3"
            track.genre.contains("WAV", ignoreCase = true) || track.mimeType == "audio/wav" -> "wav"
            track.genre.contains("OGG", ignoreCase = true) || track.mimeType == "audio/ogg" -> "ogg"
            track.genre.contains("FLAC", ignoreCase = true) || track.mimeType == "audio/flac" -> "flac"
            track.mimeType == "audio/aac" -> "aac"
            else -> "m4a"
        }
    }

    private fun copyTrackToTemp(track: Track): File {
        val uriString = track.uriString ?: throw IllegalArgumentException("A faixa não possui arquivo de áudio local.")
        val temp = File(context.cacheDir, "transfer_source_${UUID.randomUUID()}.audio")
        val input = if (uriString.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        } else {
            File(uriString).takeIf { it.exists() && it.isFile }?.inputStream()
        } ?: throw IllegalStateException("Não foi possível abrir o áudio original.")
        input.use { source ->
            FileOutputStream(temp).use { output -> source.copyTo(output) }
        }
        if (!temp.exists() || temp.length() <= 0L) {
            temp.delete()
            throw IllegalStateException("O áudio selecionado está vazio.")
        }
        return temp
    }

    suspend fun fingerprint(track: Track): Result<String> {
        var sourceFile: File? = null
        return try {
            sourceFile = copyTrackToTemp(track)
            Result.success(sha256(sourceFile))
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            sourceFile?.delete()
        }
    }

    suspend fun uploadTrack(roomCode: String, track: Track): Result<MultiplayerAudioTransferCloud> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        if (track.isBuiltIn) {
            return Result.failure(IllegalArgumentException("Faixas originais já existem em todas as instalações e não precisam ser transferidas."))
        }
        var sourceFile: File? = null
        var createdTransferId: String? = null
        return try {
            val room = roomRef(roomCode).get().await()
            if (!room.exists() || room.getString("hostUid") != uid || room.getString("status") != "WAITING") {
                throw IllegalStateException("Somente o anfitrião pode preparar o áudio antes do início da partida.")
            }
            sourceFile = copyTrackToTemp(track)
            if (sourceFile.length() > MAX_TRACK_BYTES) {
                throw IllegalArgumentException("Esta faixa excede o limite gratuito de 8 MiB para transferência multiplayer.")
            }

            val transferId = UUID.randomUUID().toString().replace("-", "")
            createdTransferId = transferId
            val transferRef = roomRef(roomCode).collection("audio_transfers").document(transferId)
            val now = System.currentTimeMillis()
            val chunkCount = ((sourceFile.length() + CHUNK_BYTES - 1L) / CHUNK_BYTES).toInt()
            val fileName = track.title.trim().ifBlank { "musica" }
                .replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80) + ".${extensionFor(track)}"
            val metadata = MultiplayerAudioTransferCloud(
                id = transferId,
                roomCode = roomCode.trim().uppercase(),
                ownerUid = uid,
                sourceTrackId = track.id.take(128),
                title = track.title.take(160),
                artist = track.artist.take(120),
                fileName = fileName,
                mimeType = track.mimeType.take(80),
                sizeBytes = sourceFile.length(),
                sha256 = sha256(sourceFile),
                chunkCount = chunkCount,
                status = "UPLOADING",
                createdAtMs = now,
                updatedAtMs = now
            )
            transferRef.set(
                mapOf(
                    "roomCode" to metadata.roomCode,
                    "ownerUid" to metadata.ownerUid,
                    "sourceTrackId" to metadata.sourceTrackId,
                    "title" to metadata.title,
                    "artist" to metadata.artist,
                    "fileName" to metadata.fileName,
                    "mimeType" to metadata.mimeType,
                    "sizeBytes" to metadata.sizeBytes,
                    "sha256" to metadata.sha256,
                    "chunkCount" to metadata.chunkCount,
                    "status" to metadata.status,
                    "createdAtMs" to metadata.createdAtMs,
                    "updatedAtMs" to metadata.updatedAtMs
                )
            ).await()

            sourceFile.inputStream().use { input ->
                var index = 0
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count == 0) break
                    val encoded = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                    transferRef.collection("chunks").document(index.toString().padStart(4, '0'))
                        .set(mapOf("index" to index, "data" to encoded, "byteCount" to count))
                        .await()
                    index++
                }
            }
            transferRef.update(
                mapOf("status" to "READY", "updatedAtMs" to System.currentTimeMillis())
            ).await()
            Result.success(metadata.copy(status = "READY", updatedAtMs = System.currentTimeMillis()))
        } catch (error: Exception) {
            createdTransferId?.let { id ->
                runCatching {
                    roomRef(roomCode).collection("audio_transfers").document(id)
                        .update(mapOf("status" to "ERROR", "updatedAtMs" to System.currentTimeMillis())).await()
                }
            }
            Result.failure(error)
        } finally {
            sourceFile?.delete()
        }
    }

    fun observeTransfers(roomCode: String): Flow<List<MultiplayerAudioTransferCloud>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = roomRef(roomCode).collection("audio_transfers")
            .whereEqualTo("status", "READY")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val transfers = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject(MultiplayerAudioTransferCloud::class.java)?.copy(id = doc.id)
                }.orEmpty().sortedByDescending { it.createdAtMs }
                trySend(transfers)
            }
        awaitClose { listener.remove() }
    }

    suspend fun downloadToCache(roomCode: String, transferId: String): Result<Pair<MultiplayerAudioTransferCloud, File>> {
        if (auth.currentUser == null) return Result.failure(IllegalStateException("Usuário não autenticado."))
        var destination: File? = null
        return try {
            val transferRef = roomRef(roomCode).collection("audio_transfers").document(transferId)
            val snap = transferRef.get().await()
            val metadata = snap.toObject(MultiplayerAudioTransferCloud::class.java)?.copy(id = snap.id)
                ?: throw IllegalStateException("A transferência de áudio não foi encontrada.")
            if (metadata.status != "READY" || metadata.sizeBytes !in 1L..MAX_TRACK_BYTES ||
                metadata.chunkCount !in 1..16 || metadata.sha256.length != 64
            ) {
                throw IllegalStateException("A transferência de áudio está incompleta ou inválida.")
            }
            val chunks = transferRef.collection("chunks").orderBy("index").get().await().documents
            if (chunks.size != metadata.chunkCount) {
                throw IllegalStateException("A transferência recebeu apenas parte dos blocos de áudio.")
            }
            val extension = metadata.fileName.substringAfterLast('.', "m4a").lowercase()
            destination = File(context.cacheDir, "match_audio_${metadata.sha256.take(16)}.$extension")
            FileOutputStream(destination).use { output ->
                var totalBytes = 0L
                chunks.forEachIndexed { expectedIndex, chunk ->
                    val index = chunk.getLong("index")?.toInt()
                        ?: throw IllegalStateException("Bloco de áudio sem índice.")
                    if (index != expectedIndex) throw IllegalStateException("A sequência dos blocos de áudio está inválida.")
                    val encoded = chunk.getString("data")
                        ?: throw IllegalStateException("Bloco de áudio vazio.")
                    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                    totalBytes += bytes.size
                    if (totalBytes > MAX_TRACK_BYTES) throw IllegalStateException("O áudio excede o limite de transferência.")
                    output.write(bytes)
                }
                output.flush()
            }
            if (destination.length() != metadata.sizeBytes || sha256(destination) != metadata.sha256) {
                throw IllegalStateException("A verificação SHA-256 do áudio recebido falhou.")
            }
            Result.success(metadata to destination)
        } catch (error: Exception) {
            destination?.delete()
            Result.failure(error)
        }
    }
}
