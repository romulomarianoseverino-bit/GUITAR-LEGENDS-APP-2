package com.example.data.repository

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.base.FirestoreEmulatorTestBase
import com.example.guitarlegends.analysis.AudioAnalyzer
import com.example.guitarlegends.data.MultiplayerAudioTransferRepository
import com.example.guitarlegends.data.MultiplayerCloudRepository
import com.example.guitarlegends.model.Difficulty
import com.example.guitarlegends.model.Instrument
import com.example.guitarlegends.model.MultiplayerMode
import com.example.guitarlegends.model.Track
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MultiplayerAudioTransferRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun hostUploadsChunkedAudio_andRoomMemberDownloadsVerifiedBytes() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(5).uppercase()
        val hostUid = signInTestUser("audio_host_$suffix@test.com")
        val hostRooms = MultiplayerCloudRepository(firestore, auth)
        val roomCode = "AUDIO-$suffix"
        val room = withTimeout(6000L) {
            hostRooms.createRoom(
                mode = MultiplayerMode.COOPERATIVE,
                track = AudioAnalyzer.BUILT_IN_TRACKS[0],
                nickname = "HostAudio",
                instrument = Instrument.GUITAR,
                difficulty = Difficulty.MEDIUM,
                customRoomCode = roomCode
            ).getOrThrow()
        }
        assertEquals(hostUid, room.hostUid)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "audio_transfer_$suffix.mp3")
        val originalBytes = ByteArray(1_300_000) { index -> ((index * 31) % 251).toByte() }
        source.writeBytes(originalBytes)
        val importedTrack = Track(
            id = "imported_$suffix",
            title = "Faixa de teste",
            artist = "Artista de teste",
            durationMs = 45_000L,
            bpm = 120.0,
            genre = "Arquivo Pessoal (MP3)",
            isBuiltIn = false,
            uriString = source.absolutePath,
            mimeType = "audio/mpeg"
        )

        val hostTransfer = MultiplayerAudioTransferRepository(context, firestore, auth)
        val transfer = hostTransfer.uploadTrack(roomCode, importedTrack).getOrThrow()
        assertEquals("READY", transfer.status)
        assertTrue(transfer.chunkCount >= 2)
        assertEquals(originalBytes.size.toLong(), transfer.sizeBytes)

        val guestUid = signInTestUser("audio_guest_$suffix@test.com")
        val guestRooms = MultiplayerCloudRepository(firestore, auth)
        assertTrue(
            guestRooms.joinRoom(
                roomCode,
                "GuestAudio",
                Instrument.BASS,
                Difficulty.HARD
            ).isSuccess
        )

        val guestTransfer = MultiplayerAudioTransferRepository(context, firestore, auth)
        val received = guestTransfer.downloadToCache(roomCode, transfer.id).getOrThrow()
        assertEquals(transfer.sha256, received.first.sha256)
        assertArrayEquals(originalBytes, received.second.readBytes())

        received.second.delete()
        source.delete()
        assertNotEquals(hostUid, guestUid)
    }
}
