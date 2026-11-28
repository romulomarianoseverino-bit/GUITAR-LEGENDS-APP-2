package com.example.guitarlegends.data

import com.example.guitarlegends.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

class MultiplayerCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val roomCodeRegex = Regex("^[A-Z0-9_-]{4,24}$")

    fun currentUidOrNull(): String? = auth.currentUser?.uid

    fun normalizeRoomCode(raw: String): String {
        return raw.trim()
            .uppercase()
            .replace(Regex("\\s+"), "-")
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
            .take(24)
    }

    fun generateRoomCode(): String {
        val number = Random.nextInt(1000, 9999)
        val tag = listOf("ROCK", "AXEL", "AMP", "RIFF", "SOLO").random()
        return "$tag-$number"
    }

    private fun safeNickname(raw: String, uid: String): String {
        val clean = raw.trim().take(24)
        return if (clean.length >= 2) clean else "Rocker_${uid.take(4)}"
    }

    suspend fun createRoom(
        mode: MultiplayerMode,
        track: Track,
        nickname: String,
        instrument: Instrument,
        difficulty: Difficulty,
        customRoomCode: String? = null,
        strikeEnabled: Boolean = true
    ): Result<MultiplayerRoomCloud> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("Entre com sua conta Google antes de criar uma sala online."))
        val uid = user.uid
        val hostNick = safeNickname(nickname, uid)
        val roomCode = customRoomCode?.let(::normalizeRoomCode)?.takeIf { roomCodeRegex.matches(it) }
            ?: generateRoomCode()

        val roomPath = "multiplayer_rooms/$roomCode"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(roomCode)
            val createdRoom = firestore.runTransaction { tx ->
                val snap = tx.get(roomRef)
                if (snap.exists()) {
                    throw IllegalStateException("Código de sala '$roomCode' já está em uso. Tente gerar outro.")
                }
                val room = MultiplayerRoomCloud(
                    id = roomCode,
                    roomCode = roomCode,
                    hostUid = uid,
                    hostNickname = hostNick,
                    mode = mode.name,
                    status = RoomStatus.WAITING.name,
                    trackId = track.id.ifBlank { "track_thunderstrike" }.take(128),
                    trackTitle = track.title.ifBlank { "Música de Palco" }.take(160),
                    maxPlayers = mode.maxPlayers,
                    memberUids = listOf(uid),
                    winnerUid = "",
                    inviteMessage = "Entre na sala $roomCode no Guitar Legends!",
                    strikeEnabled = strikeEnabled
                )
                tx.set(roomRef, room.toCreateMap())
                room
            }.await()

            val participant = MultiplayerParticipantCloud(
                id = uid,
                userId = uid,
                roomCode = roomCode,
                nickname = hostNick,
                instrument = instrument.name,
                difficulty = difficulty.name,
                ready = false,
                connectionState = ParticipantConnectionState.ONLINE.name,
                score = 0,
                combo = 0,
                rockMeter = 0.6,
                starPowerActive = false,
                strikeConsent = strikeEnabled,
                lastHeartbeatMs = System.currentTimeMillis()
            )
            roomRef.collection("participants").document(uid)
                .set(participant.toCreateMap())
                .await()

            Result.success(createdRoom)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.WRITE, roomPath, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun joinRoom(
        rawCode: String,
        nickname: String,
        instrument: Instrument,
        difficulty: Difficulty
    ): Result<MultiplayerRoomCloud> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("Entre com sua conta Google para entrar em uma sala online."))
        val uid = user.uid
        val roomCode = normalizeRoomCode(rawCode)
        if (!roomCodeRegex.matches(roomCode)) {
            return Result.failure(IllegalArgumentException("Código de sala inválido. Use de 4 a 24 caracteres (ex: ROCK-4829)."))
        }

        val nick = safeNickname(nickname, uid)
        val roomPath = "multiplayer_rooms/$roomCode"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(roomCode)
            val joinedRoom = firestore.runTransaction { tx ->
                val snap = tx.get(roomRef)
                if (!snap.exists()) {
                    throw IllegalStateException("Sala '$roomCode' não encontrada. Confira o código com o anfitrião.")
                }
                val room = snap.toObject(
                    MultiplayerRoomCloud::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("Dados da sala '$roomCode' inválidos.")

                if (room.statusEnum == RoomStatus.CLOSED) {
                    throw IllegalStateException("Esta sala foi encerrada pelo anfitrião.")
                }

                val alreadyMember = room.memberUids.contains(uid)
                if (!alreadyMember) {
                    if (room.statusEnum != RoomStatus.WAITING) {
                        throw IllegalStateException("A partida na sala '$roomCode' já está em andamento.")
                    }
                    if (room.memberUids.size >= room.maxPlayers) {
                        throw IllegalStateException("A sala '$roomCode' já atingiu o limite máximo (${room.maxPlayers} jogadores).")
                    }
                    val updatedMembers = room.memberUids + uid
                    tx.update(
                        roomRef,
                        mapOf(
                            "memberUids" to updatedMembers,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    room.copy(memberUids = updatedMembers)
                } else {
                    room
                }
            }.await()

            val participantRef = roomRef.collection("participants").document(uid)
            val existingPart = participantRef.get().await()
            if (existingPart.exists()) {
                participantRef.update(
                    mapOf(
                        "nickname" to nick,
                        "instrument" to instrument.name,
                        "difficulty" to difficulty.name,
                        "connectionState" to ParticipantConnectionState.ONLINE.name,
                        "lastHeartbeatMs" to System.currentTimeMillis(),
                        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                val participant = MultiplayerParticipantCloud(
                    id = uid,
                    userId = uid,
                    roomCode = roomCode,
                    nickname = nick,
                    instrument = instrument.name,
                    difficulty = difficulty.name,
                    ready = false,
                    connectionState = ParticipantConnectionState.ONLINE.name,
                    score = 0,
                    combo = 0,
                    rockMeter = 0.6,
                    starPowerActive = false,
                    lastHeartbeatMs = System.currentTimeMillis()
                )
                participantRef.set(participant.toCreateMap()).await()
            }

            Result.success(joinedRoom)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.WRITE, roomPath, auth) }.exceptionOrNull() ?: e)
        }
    }

    fun observeRoom(roomCode: String): Flow<MultiplayerRoomCloud?> = callbackFlow {
        val normalized = normalizeRoomCode(roomCode)
        if (!roomCodeRegex.matches(normalized) || auth.currentUser == null) {
            close(IllegalStateException("Sala ou autenticação inválida."))
            return@callbackFlow
        }
        val listener = firestore.collection("multiplayer_rooms")
            .document(normalized)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                // Não propague ao jogo um estado que ainda existe somente no cache local.
                // O outro participante só pode iniciar quando a alteração estiver confirmada.
                if (snap?.metadata?.hasPendingWrites() == true) {
                    return@addSnapshotListener
                }
                val room = if (snap != null && snap.exists()) {
                    snap.toObject(
                        MultiplayerRoomCloud::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
                trySend(room)
            }
        awaitClose { listener.remove() }
    }

    fun observeParticipants(roomCode: String): Flow<List<MultiplayerParticipantCloud>> = callbackFlow {
        val normalized = normalizeRoomCode(roomCode)
        if (!roomCodeRegex.matches(normalized) || auth.currentUser == null) {
            close(IllegalStateException("Sala ou autenticação inválida."))
            return@callbackFlow
        }
        val listener = firestore.collection("multiplayer_rooms")
            .document(normalized)
            .collection("participants")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(
                        MultiplayerParticipantCloud::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }?.sortedBy { it.joinedAt?.toDate()?.time ?: 0L } ?: emptyList()
                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun updateParticipantStrikeConsent(roomCode: String, consent: Boolean): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized/participants/$uid"
        return try {
            firestore.collection("multiplayer_rooms").document(normalized)
                .collection("participants").document(uid)
                .update(
                    mapOf(
                        "strikeConsent" to consent,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun updateParticipantLoadout(
        roomCode: String,
        nickname: String,
        instrument: Instrument,
        difficulty: Difficulty,
        ready: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized/participants/$uid"
        return try {
            firestore.collection("multiplayer_rooms")
                .document(normalized)
                .collection("participants")
                .document(uid)
                .update(
                    mapOf(
                        "nickname" to safeNickname(nickname, uid),
                        "instrument" to instrument.name,
                        "difficulty" to difficulty.name,
                        "ready" to ready,
                        "connectionState" to ParticipantConnectionState.ONLINE.name,
                        "lastHeartbeatMs" to System.currentTimeMillis(),
                        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun updateParticipantProgress(
        roomCode: String,
        score: Int,
        combo: Int,
        rockMeter: Float,
        starPowerActive: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized/participants/$uid"
        return try {
            firestore.collection("multiplayer_rooms")
                .document(normalized)
                .collection("participants")
                .document(uid)
                .update(
                    mapOf(
                        "score" to score.coerceIn(0, 50_000_000),
                        "combo" to combo.coerceIn(0, 100_000),
                        "rockMeter" to rockMeter.toDouble().coerceIn(0.0, 1.0),
                        "starPowerActive" to starPowerActive
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    fun observeStrikes(roomCode: String): Flow<List<StrikeEventCloud>> = callbackFlow {
        val normalized = normalizeRoomCode(roomCode)
        if (!roomCodeRegex.matches(normalized) || auth.currentUser == null) {
            close(IllegalStateException("Sala ou autenticação inválida."))
            return@callbackFlow
        }
        val listener = firestore.collection("multiplayer_rooms")
            .document(normalized)
            .collection("strikes")
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val events = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(StrikeEventCloud::class.java)?.copy(id = doc.id)
                }?.sortedBy { it.createdAtMs } ?: emptyList()
                trySend(events)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendStrike(roomCode: String, targetUid: String, power: String): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        if (targetUid.isBlank() || targetUid == uid || power !in setOf("LANE_INVERSION", "FOG")) {
            return Result.failure(IllegalArgumentException("Ataque ou alvo inválido."))
        }
        val path = "multiplayer_rooms/$normalized/strikes"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(normalized)
            val strikeId = firestore.runTransaction { tx ->
                val roomSnap = tx.get(roomRef)
                val room = roomSnap.toObject(MultiplayerRoomCloud::class.java)
                    ?: throw IllegalStateException("Sala não encontrada.")

                if (room.statusEnum != RoomStatus.PLAYING) {
                    throw IllegalStateException("A partida ainda não está em andamento.")
                }
                if (!room.strikeEnabled) {
                    throw IllegalStateException("O anfitrião não habilitou o Strike nesta sala.")
                }
                if (uid !in room.memberUids) {
                    throw IllegalStateException("Seu jogador não consta mais na lista de participantes da sala.")
                }
                if (targetUid !in room.memberUids) {
                    throw IllegalStateException("O jogador escolhido não consta mais na lista de participantes.")
                }

                // A decisão é conferida no servidor para todos os integrantes da sala.
                // Assim, um cliente desatualizado não consegue enviar Strike sem consentimento.
                val participantSnapshots = room.memberUids.distinct().map { memberUid ->
                    tx.get(roomRef.collection("participants").document(memberUid))
                }
                if (participantSnapshots.size < 2 || participantSnapshots.any { !it.exists() }) {
                    throw IllegalStateException("Não foi possível confirmar todos os participantes da sala.")
                }
                if (participantSnapshots.any { it.getBoolean("strikeConsent") != true }) {
                    throw IllegalStateException("O Strike só pode ser usado quando todos os jogadores concordarem.")
                }

                val strikeRef = roomRef.collection("strikes").document()
                tx.set(
                    strikeRef,
                    mapOf(
                        "roomCode" to normalized,
                        "attackerUid" to uid,
                        "targetUid" to targetUid,
                        "power" to power,
                        "createdAtMs" to System.currentTimeMillis(),
                        "status" to "PENDING",
                        "resolvedAtMs" to 0L
                    )
                )
                strikeRef.id
            }.await()
            Result.success(strikeId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.WRITE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun markStrikeApplied(roomCode: String, strikeId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        if (strikeId.isBlank() || strikeId.length > 128) {
            return Result.failure(IllegalArgumentException("Ataque inválido."))
        }
        val path = "multiplayer_rooms/$normalized/strikes/$strikeId"
        return try {
            firestore.collection("multiplayer_rooms").document(normalized)
                .collection("strikes").document(strikeId)
                .update(
                    mapOf(
                        "status" to "APPLIED",
                        "resolvedAtMs" to System.currentTimeMillis()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun defendStrike(roomCode: String, strikeId: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        if (strikeId.isBlank() || strikeId.length > 128) {
            return Result.failure(IllegalArgumentException("Ataque inválido."))
        }
        val path = "multiplayer_rooms/$normalized/strikes/$strikeId"
        return try {
            firestore.collection("multiplayer_rooms").document(normalized)
                .collection("strikes").document(strikeId)
                .update(
                    mapOf(
                        "status" to "DEFENDED",
                        "resolvedAtMs" to System.currentTimeMillis()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun sendHeartbeat(
        roomCode: String,
        connectionState: ParticipantConnectionState = ParticipantConnectionState.ONLINE
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized/participants/$uid"
        return try {
            firestore.collection("multiplayer_rooms")
                .document(normalized)
                .collection("participants")
                .document(uid)
                .update(
                    mapOf(
                        "connectionState" to connectionState.name,
                        "lastHeartbeatMs" to System.currentTimeMillis(),
                        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    /**
     * Marca a saída do participante e, se a partida estiver em andamento,
     * inicia uma única pausa coletiva usando o horário do servidor Firestore.
     * Repetições durante a mesma pausa não reiniciam o prazo.
     */
    suspend fun markDisconnectedAndPauseRoom(roomCode: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(normalized)
            val participantRef = roomRef.collection("participants").document(uid)
            firestore.runTransaction { tx ->
                val roomSnap = tx.get(roomRef)
                val participantSnap = tx.get(participantRef)
                if (!roomSnap.exists() || !participantSnap.exists()) {
                    throw IllegalStateException("A participação na sala não foi encontrada.")
                }
                val room = roomSnap.toObject(
                    MultiplayerRoomCloud::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("Dados da sala inválidos.")
                if (room.statusEnum == RoomStatus.CLOSED || room.statusEnum == RoomStatus.FINISHED) {
                    return@runTransaction
                }

                tx.update(
                    participantRef,
                    mapOf(
                        "connectionState" to ParticipantConnectionState.DISCONNECTED.name,
                        "lastHeartbeatMs" to System.currentTimeMillis(),
                        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (room.statusEnum == RoomStatus.PLAYING) {
                    val nickname = safeNickname(participantSnap.getString("nickname").orEmpty(), uid)
                    tx.update(
                        roomRef,
                        mapOf(
                            "status" to RoomStatus.PAUSED.name,
                            "pauseStartedAt" to FieldValue.serverTimestamp(),
                            "pausedByUid" to uid,
                            "pausedByNickname" to nickname,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    /**
     * Revalida a identidade e restaura a presença. Se a sala estiver PAUSED,
     * a transição de volta a PLAYING ocorre na mesma transação que ONLINE;
     * as regras do Firestore rejeitam a retomada após os 60 segundos.
     */
    suspend fun reconnectParticipant(
        roomCode: String,
        nickname: String,
        instrument: Instrument,
        difficulty: Difficulty
    ): Result<MultiplayerRoomCloud> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(normalized)
            val participantRef = roomRef.collection("participants").document(uid)
            val refreshed = firestore.runTransaction { tx ->
                val roomSnap = tx.get(roomRef)
                val participantSnap = tx.get(participantRef)
                if (!roomSnap.exists() || !participantSnap.exists()) {
                    throw IllegalStateException("A sala ou a participação não existe mais.")
                }
                val room = roomSnap.toObject(
                    MultiplayerRoomCloud::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: throw IllegalStateException("Dados da sala inválidos.")
                if (room.statusEnum == RoomStatus.CLOSED || room.statusEnum == RoomStatus.FINISHED) {
                    throw IllegalStateException("Esta sala já foi encerrada.")
                }
                if (!room.memberUids.contains(uid)) {
                    throw IllegalStateException("Você não faz mais parte desta sala.")
                }
                val memberSnapshots = room.memberUids.map { memberUid ->
                    tx.get(roomRef.collection("participants").document(memberUid))
                }
                val allOtherMembersOnline = memberSnapshots
                    .filter { it.id != uid }
                    .all { it.exists() && it.getString("connectionState") == ParticipantConnectionState.ONLINE.name }

                tx.update(
                    participantRef,
                    mapOf(
                        "nickname" to safeNickname(nickname, uid),
                        "instrument" to instrument.name,
                        "difficulty" to difficulty.name,
                        "connectionState" to ParticipantConnectionState.ONLINE.name,
                        "lastHeartbeatMs" to System.currentTimeMillis(),
                        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                if (room.statusEnum == RoomStatus.PAUSED && allOtherMembersOnline) {
                    tx.update(
                        roomRef,
                        mapOf(
                            "status" to RoomStatus.PLAYING.name,
                            "pauseStartedAt" to FieldValue.delete(),
                            "pausedByUid" to FieldValue.delete(),
                            "pausedByNickname" to FieldValue.delete(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    room.copy(
                        status = RoomStatus.PLAYING.name,
                        pauseStartedAt = null,
                        pausedByUid = "",
                        pausedByNickname = ""
                    )
                } else {
                    room
                }
            }.await()
            Result.success(refreshed)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    /**
     * Solicita o encerramento de uma pausa expirada. O horário limite é
     * validado pelas regras com request.time do Firestore, não pelo relógio local.
     */
    suspend fun closeExpiredPausedRoom(roomCode: String): Result<Boolean> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized"
        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(normalized)
            val closed = firestore.runTransaction { tx ->
                val roomSnap = tx.get(roomRef)
                if (!roomSnap.exists()) return@runTransaction false
                val room = roomSnap.toObject(
                    MultiplayerRoomCloud::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: return@runTransaction false
                if (room.statusEnum == RoomStatus.CLOSED) return@runTransaction true
                if (room.statusEnum != RoomStatus.PAUSED || !room.memberUids.contains(uid)) {
                    return@runTransaction false
                }
                tx.update(
                    roomRef,
                    mapOf(
                        "status" to RoomStatus.CLOSED.name,
                        "pauseStartedAt" to FieldValue.delete(),
                        "pausedByUid" to FieldValue.delete(),
                        "pausedByNickname" to FieldValue.delete(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                true
            }.await()
            Result.success(closed)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun updateRoomHostConfig(
        roomCode: String,
        mode: MultiplayerMode,
        track: Track,
        status: RoomStatus? = null,
        winnerUid: String? = null,
        strikeEnabled: Boolean? = null,
        audioTransferId: String? = null,
        trackFingerprint: String? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        val path = "multiplayer_rooms/$normalized"
        return try {
            val updates = mutableMapOf<String, Any>(
                "mode" to mode.name,
                "maxPlayers" to mode.maxPlayers,
                "trackId" to track.id.ifBlank { "track_thunderstrike" }.take(128),
                "trackTitle" to track.title.ifBlank { "Música de Palco" }.take(160),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (status != null) {
                updates["status"] = status.name
            }
            if (winnerUid != null) {
                updates["winnerUid"] = winnerUid.take(128)
            }
            if (strikeEnabled != null) {
                updates["strikeEnabled"] = strikeEnabled
            }
            if (audioTransferId != null) {
                updates["audioTransferId"] = audioTransferId.take(64)
            }
            if (trackFingerprint != null) {
                updates["trackFingerprint"] = trackFingerprint.take(64)
            }
            firestore.collection("multiplayer_rooms")
                .document(normalized)
                .update(updates)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.UPDATE, path, auth) }.exceptionOrNull() ?: e)
        }
    }

    suspend fun leaveOrCloseRoom(roomCode: String): Result<Unit> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val normalized = normalizeRoomCode(roomCode)
        if (!roomCodeRegex.matches(normalized)) return Result.success(Unit)
        val path = "multiplayer_rooms/$normalized"

        return try {
            val roomRef = firestore.collection("multiplayer_rooms").document(normalized)
            val partRef = roomRef.collection("participants").document(uid)
            val snap = roomRef.get().await()
            if (!snap.exists()) {
                return Result.success(Unit)
            }

            firestore.runTransaction { tx ->
                val curSnap = tx.get(roomRef)
                if (!curSnap.exists()) return@runTransaction
                val curRoom = curSnap.toObject(
                    MultiplayerRoomCloud::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: return@runTransaction

                if (curRoom.statusEnum == RoomStatus.CLOSED) return@runTransaction

                val remaining = curRoom.memberUids.filter { it != uid }
                if (curRoom.hostUid == uid) {
                    if (remaining.isEmpty()) {
                        tx.update(
                            roomRef,
                            mapOf(
                                "status" to RoomStatus.CLOSED.name,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    } else {
                        val nextHostUid = remaining.first()
                        val nextHostPartSnap = tx.get(roomRef.collection("participants").document(nextHostUid))
                        val nextNick = safeNickname(
                            nextHostPartSnap.getString("nickname").orEmpty(),
                            nextHostUid
                        )
                        tx.update(
                            roomRef,
                            mapOf(
                                "hostUid" to nextHostUid,
                                "hostNickname" to nextNick,
                                "memberUids" to remaining,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                } else if (curRoom.memberUids.contains(uid)) {
                    tx.update(
                        roomRef,
                        mapOf(
                            "memberUids" to remaining,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }.await()

            runCatching { partRef.delete().await() }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(runCatching { handleFirestoreError(e, OperationType.WRITE, path, auth) }.exceptionOrNull() ?: e)
        }
    }
}
