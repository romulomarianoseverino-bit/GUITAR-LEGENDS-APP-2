package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.guitarlegends.analysis.AudioAnalyzer
import com.example.guitarlegends.data.AccountCloudRepository
import com.example.guitarlegends.data.FriendsCloudRepository
import com.example.guitarlegends.data.MultiplayerCloudRepository
import com.example.guitarlegends.model.Difficulty
import com.example.guitarlegends.model.Instrument
import com.example.guitarlegends.model.MultiplayerMode
import com.example.guitarlegends.model.PlayerStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FriendsCloudRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun tag(): String = UUID.randomUUID().toString().replace("-", "").take(5).lowercase()

    @Test
    fun friendRequest_acceptance_presence_directRoomInvite_andBlock_areEnforced() = runBlocking {
        val suffix = tag()
        val aliceUid = signInTestUser("alice_$suffix@test.com")
        val aliceAccount = AccountCloudRepository(firestore, auth)
        assertTrue(aliceAccount.claimNicknameAndSaveAccount("Alice_$suffix", PlayerStats()).isSuccess)
        val aliceFriends = FriendsCloudRepository(firestore, auth)

        val bobUid = signInTestUser("bob_$suffix@test.com")
        val bobAccount = AccountCloudRepository(firestore, auth)
        assertTrue(bobAccount.claimNicknameAndSaveAccount("Bob_$suffix", PlayerStats()).isSuccess)
        val bobFriends = FriendsCloudRepository(firestore, auth)

        signInTestUser("alice_$suffix@test.com")
        assertTrue(
            "A valid nickname must receive a friend request",
            withTimeout(DEFAULT_TIMEOUT_MS) { aliceFriends.sendFriendRequest("Bob_$suffix") }.isSuccess
        )

        signInTestUser("bob_$suffix@test.com")
        val incoming = withTimeout(FLOW_TIMEOUT_MS) {
            bobFriends.observeRequests().first { list -> list.any { it.senderUid == aliceUid && it.status == "PENDING" } }
        }.first { it.senderUid == aliceUid && it.status == "PENDING" }
        assertTrue(bobFriends.respondToRequest(incoming.id, accept = true).isSuccess)

        signInTestUser("alice_$suffix@test.com")
        val accepted = withTimeout(FLOW_TIMEOUT_MS) {
            aliceFriends.observeRequests().first { list -> list.any { it.status == "ACCEPTED" } }
        }
        assertTrue(accepted.any { it.status == "ACCEPTED" })

        assertTrue(aliceFriends.setPresence(online = true).isSuccess)
        signInTestUser("bob_$suffix@test.com")
        assertTrue(bobFriends.setPresence(online = true).isSuccess)
        val onlineState = withTimeout(FLOW_TIMEOUT_MS) {
            bobFriends.observePresence(listOf(aliceUid)).first { it[aliceUid] == true }
        }
        assertTrue(onlineState[aliceUid]?.isOnline() == true)

        // A preferência de privacidade do destinatário precisa bloquear convites diretos.
        assertTrue(bobFriends.setPresence(online = true, allowRoomInvites = false).isSuccess)
        signInTestUser("alice_$suffix@test.com")
        val multiplayer = MultiplayerCloudRepository(firestore, auth)
        val roomCode = "FRIEND-$suffix".uppercase()
        val room = withTimeout(DEFAULT_TIMEOUT_MS) {
            multiplayer.createRoom(
                mode = MultiplayerMode.BATTLE_1V1,
                track = AudioAnalyzer.BUILT_IN_TRACKS[0],
                nickname = "Alice_$suffix",
                instrument = Instrument.GUITAR,
                difficulty = Difficulty.MEDIUM,
                customRoomCode = roomCode
            ).getOrThrow()
        }
        assertEquals(aliceUid, room.hostUid)
        val privacyBlockedInvite = aliceFriends.sendRoomInvite(bobUid, roomCode, "Alice_$suffix")
        assertTrue("A friend who disabled room invitations must not receive an invite", privacyBlockedInvite.isFailure)

        signInTestUser("bob_$suffix@test.com")
        assertTrue(bobFriends.setPresence(online = true, allowRoomInvites = true).isSuccess)
        signInTestUser("alice_$suffix@test.com")
        val inviteId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceFriends.sendRoomInvite(bobUid, roomCode, "Alice_$suffix").getOrThrow()
        }

        signInTestUser("bob_$suffix@test.com")
        val invite = withTimeout(FLOW_TIMEOUT_MS) {
            bobFriends.observeRoomInvites().first { list -> list.any { it.id == inviteId } }
        }.first { it.id == inviteId }
        val acceptedRoomCode = bobFriends.respondToRoomInvite(invite.id, accept = true).getOrThrow()
        assertEquals(roomCode, acceptedRoomCode)
        val bobMultiplayer = MultiplayerCloudRepository(firestore, auth)
        val joined = bobMultiplayer.joinRoom(
            acceptedRoomCode!!,
            "Bob_$suffix",
            Instrument.BASS,
            Difficulty.HARD
        )
        assertTrue("A friend accepting a direct room invite must be able to join", joined.isSuccess)

        assertTrue(bobFriends.blockUser(aliceUid).isSuccess)
        signInTestUser("alice_$suffix@test.com")
        val blockedRequest = aliceFriends.sendFriendRequest("Bob_$suffix")
        assertTrue("A blocked player must not receive a new request", blockedRequest.isFailure)

        signInTestUser("alice_$suffix@test.com")
        assertTrue(aliceFriends.setPresence(online = false).isSuccess)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
