package com.example

import com.example.guitarlegends.model.MultiplayerParticipantCloud
import com.example.guitarlegends.model.ParticipantConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplayerPresenceGracePeriodTest {
    @Test
    fun switchingAppsDoesNotMarkPlayerDisconnectedDuringGracePeriod() {
        val participant = MultiplayerParticipantCloud(
            userId = "player-1",
            connectionState = ParticipantConnectionState.ONLINE.name,
            lastHeartbeatMs = 1_000L
        )

        assertFalse(participant.isStale(nowMs = 31_000L))
        assertFalse(participant.isStale(nowMs = 119_000L))
        assertTrue(participant.isStale(nowMs = 122_000L))
    }

    @Test
    fun explicitDisconnectIsStillDetectedImmediately() {
        val participant = MultiplayerParticipantCloud(
            userId = "player-1",
            connectionState = ParticipantConnectionState.DISCONNECTED.name,
            lastHeartbeatMs = 1_000L
        )

        assertTrue(participant.isStale(nowMs = 1_001L))
    }
}
