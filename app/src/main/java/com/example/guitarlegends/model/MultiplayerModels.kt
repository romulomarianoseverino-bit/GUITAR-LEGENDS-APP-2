package com.example.guitarlegends.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class MultiplayerMode(val displayName: String, val maxPlayers: Int) {
    BATTLE_1V1("Batalha 1v1", 2),
    BAND_COOP("Banda Cooperativa", 4)
}

enum class RoomStatus(val label: String) {
    WAITING("Aguardando Jogadores"),
    PLAYING("Show em Andamento"),
    PAUSED("Aguardando Reconexão"),
    FINISHED("Show Finalizado"),
    CLOSED("Sala Encerrada")
}

enum class ParticipantConnectionState(val label: String) {
    ONLINE("Conectado"),
    RECONNECTING("Reconectando..."),
    DISCONNECTED("Desconectado")
}

@Keep
@IgnoreExtraProperties
data class MultiplayerRoomCloud(
    @DocumentId
    val id: String = "",
    val roomCode: String = "",
    val hostUid: String = "",
    val hostNickname: String = "",
    val mode: String = MultiplayerMode.BATTLE_1V1.name,
    val status: String = RoomStatus.WAITING.name,
    val trackId: String = "track_thunderstrike",
    val trackTitle: String = "Thunderstrike Anthem",
    val maxPlayers: Int = 2,
    val memberUids: List<String> = emptyList(),
    val winnerUid: String = "",
    val inviteMessage: String = "",
    val strikeEnabled: Boolean = true,
    val audioTransferId: String = "",
    val trackFingerprint: String = "",
    val pauseStartedAt: Timestamp? = null,
    val pausedByUid: String = "",
    val pausedByNickname: String = "",
    @ServerTimestamp
    val createdAt: Timestamp? = null,
    @ServerTimestamp
    val updatedAt: Timestamp? = null
) {
    val modeEnum: MultiplayerMode
        get() = runCatching { MultiplayerMode.valueOf(mode) }.getOrDefault(MultiplayerMode.BATTLE_1V1)

    val statusEnum: RoomStatus
        get() = runCatching { RoomStatus.valueOf(status) }.getOrDefault(RoomStatus.WAITING)

    fun toCreateMap(): Map<String, Any?> = mapOf(
        "roomCode" to roomCode.take(24),
        "hostUid" to hostUid.take(128),
        "hostNickname" to hostNickname.take(24),
        "mode" to modeEnum.name,
        "status" to statusEnum.name,
        "trackId" to trackId.take(128),
        "trackTitle" to trackTitle.take(160),
        "maxPlayers" to maxPlayers.coerceIn(2, 4),
        "memberUids" to memberUids.distinct().take(4),
        "winnerUid" to winnerUid.take(128),
        "inviteMessage" to inviteMessage.take(160),
        "strikeEnabled" to strikeEnabled,
        "audioTransferId" to audioTransferId.take(64),
        "trackFingerprint" to trackFingerprint.take(64),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class MultiplayerParticipantCloud(
    @DocumentId
    val id: String = "",
    val userId: String = "",
    val roomCode: String = "",
    val nickname: String = "",
    val instrument: String = Instrument.GUITAR.name,
    val difficulty: String = Difficulty.MEDIUM.name,
    val ready: Boolean = false,
    val connectionState: String = ParticipantConnectionState.ONLINE.name,
    val score: Int = 0,
    val combo: Int = 0,
    val rockMeter: Double = 0.6,
    val starPowerActive: Boolean = false,
    val strikeConsent: Boolean = false,
    val lastHeartbeatMs: Long = 0L,
    @ServerTimestamp
    val lastHeartbeatAt: Timestamp? = null,
    @ServerTimestamp
    val joinedAt: Timestamp? = null,
    @ServerTimestamp
    val updatedAt: Timestamp? = null
) {
    val instrumentEnum: Instrument
        get() = runCatching { Instrument.valueOf(instrument) }.getOrDefault(Instrument.GUITAR)

    val difficultyEnum: Difficulty
        get() = runCatching { Difficulty.valueOf(difficulty) }.getOrDefault(Difficulty.MEDIUM)

    val connectionStateEnum: ParticipantConnectionState
        get() = runCatching { ParticipantConnectionState.valueOf(connectionState) }
            .getOrDefault(ParticipantConnectionState.ONLINE)

    // Antes de PRONTO, a sala tolera até 2 minutos de ausência.
    // Depois de PRONTO, a tolerância é curta; uma saída explícita é imediata.
    fun isStale(nowMs: Long, thresholdMs: Long? = null): Boolean {
        if (connectionStateEnum == ParticipantConnectionState.DISCONNECTED) return true
        val allowedMs = thresholdMs ?: if (ready) 20_000L else 120_000L
        val heartbeatTime = lastHeartbeatAt?.toDate()?.time ?: lastHeartbeatMs
        return heartbeatTime > 0L && (nowMs - heartbeatTime) > allowedMs
    }

    fun toCreateMap(): Map<String, Any?> = mapOf(
        "userId" to userId.take(128),
        "roomCode" to roomCode.take(24),
        "nickname" to nickname.take(24),
        "instrument" to instrumentEnum.name,
        "difficulty" to difficultyEnum.name,
        "ready" to ready,
        "connectionState" to connectionStateEnum.name,
        "score" to score.coerceIn(0, 50_000_000),
        "combo" to combo.coerceIn(0, 100_000),
        "rockMeter" to rockMeter.coerceIn(0.0, 1.0),
        "starPowerActive" to starPowerActive,
        "strikeConsent" to strikeConsent,
        "lastHeartbeatMs" to lastHeartbeatMs.coerceAtLeast(0L),
        "lastHeartbeatAt" to FieldValue.serverTimestamp(),
        "joinedAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}


@Keep
@IgnoreExtraProperties
data class StrikeEventCloud(
    @DocumentId
    val id: String = "",
    val roomCode: String = "",
    val attackerUid: String = "",
    val targetUid: String = "",
    val power: String = "LANE_INVERSION",
    val createdAtMs: Long = 0L,
    val status: String = "PENDING",
    val resolvedAtMs: Long = 0L
)
