package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.guitarlegends.analysis.AudioAnalyzer
import com.example.guitarlegends.data.MultiplayerCloudRepository
import com.example.guitarlegends.model.Difficulty
import com.example.guitarlegends.model.Instrument
import com.example.guitarlegends.model.MultiplayerMode
import com.example.guitarlegends.model.ParticipantConnectionState
import com.example.guitarlegends.model.RoomStatus
import com.example.guitarlegends.ui.screens.MainMenuScreen
import com.example.guitarlegends.viewmodel.GameScreen
import com.example.guitarlegends.viewmodel.GameViewModel
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@LooperMode(LooperMode.Mode.PAUSED)
class MultiplayerRecoveryTest : FirestoreEmulatorTestBase() {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun cleanLocalState() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "local_player_progress.json").delete()
        File(app.filesDir, "local_player_progress.json.tmp").delete()
    }

    private fun getOrInitSecondaryBobRepo(context: Context): Pair<FirebaseAuth, MultiplayerCloudRepository> {
        val bobAppName = "bob-mp-test-app"
        val bobApp = FirebaseApp.getApps(context).firstOrNull { it.name == bobAppName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.applet.kpfcic")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key-for-emulator")
                    .build(),
                bobAppName
            ).also { initialized ->
                val bobDb = FirebaseFirestore.getInstance(initialized, databaseId)
                bobDb.useEmulator("127.0.0.1", 8085)
                bobDb.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
                val bobAuth = FirebaseAuth.getInstance(initialized)
                bobAuth.useEmulator("127.0.0.1", 9099)
            }
        val bobAuth = FirebaseAuth.getInstance(bobApp)
        val bobDb = FirebaseFirestore.getInstance(bobApp, databaseId)
        return bobAuth to MultiplayerCloudRepository(bobDb, bobAuth)
    }

    private suspend fun signInSecondary(bobAuth: FirebaseAuth, email: String): String {
        val result = try {
            bobAuth.createUserWithEmailAndPassword(email, "password123").await()
        } catch (_: FirebaseAuthUserCollisionException) {
            bobAuth.signInWithEmailAndPassword(email, "password123").await()
        }
        val signedInUser = checkNotNull(result.user)
        signedInUser.getIdToken(true).await()
        return checkNotNull(signedInUser.uid)
    }

    @Test
    fun `01 - MULTIPLAYER button stays visible without scrolling on 800x360 landscape and opens screen`() {
        val vm = GameViewModel(app)
        vm.enterGame()

        composeTestRule.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.size(width = 800.dp, height = 360.dp)) {
                    MainMenuScreen(viewModel = vm)
                }
            }
        }
        composeTestRule.waitForIdle()

        // Garante visibilidade simultânea sem rolagem dos botões principais e MULTIPLAYER
        composeTestRule.onNodeWithTag("menu_quick_play").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_multiplayer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_career").assertIsDisplayed()
        composeTestRule.onNodeWithTag("menu_settings").assertIsDisplayed()

        // Clica em MULTIPLAYER e valida abertura
        composeTestRule.onNodeWithTag("menu_multiplayer").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.currentScreen.value is GameScreen.CompetitiveMultiplayer)
    }

    @Test
    fun `02 - guest opening MultiplayerScreen sees auth option, local challenge, and returns cleanly to MainMenu`() {
        val vm = GameViewModel(app)
        vm.enterGame()

        composeTestRule.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.size(width = 840.dp, height = 400.dp)) {
                    GuitarLegendsApp(viewModel = vm)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("menu_multiplayer").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.currentScreen.value is GameScreen.CompetitiveMultiplayer)

        composeTestRule.onNodeWithTag("multiplayer_google_signin_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_tab_battle").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_tab_band").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_start_button").assertIsDisplayed()

        // Alterna abas e retorna para a página principal
        composeTestRule.onNodeWithTag("multiplayer_tab_band").performClick()
        composeTestRule.waitForIdle()
        assertEquals(MultiplayerMode.BAND_COOP, vm.multiplayerMode.value)

        vm.navigateBack()
        composeTestRule.waitForIdle()
        assertTrue(vm.currentScreen.value is GameScreen.MainMenu)
        composeTestRule.onNodeWithTag("menu_multiplayer").assertIsDisplayed()
    }

    private fun <T> awaitWithMainLooper(timeoutMs: Long = 6000L, block: suspend () -> T): T {
        val deferred = CoroutineScope(Dispatchers.IO).async { block() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!deferred.isCompleted) {
            if (System.currentTimeMillis() > deadline) {
                deferred.cancel()
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for async Firebase/ViewModel step")
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        return runBlocking { deferred.await() }
    }

    private fun waitUntilWithMainLooper(timeoutMs: Long = 6000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition")
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
    }

    @Test
    fun `03 - full online room creation, second player join, live sync, disconnect detection, reconnect and exit`() {
        val tag = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
        val roomCode = "ROCK-$tag"
        val aliceUid = awaitWithMainLooper { signInTestUser("mp_alice_$tag@test.com") }

        val vm = GameViewModel(app, firebaseApp)
        assertEquals("ViewModel must use the authenticated test account", aliceUid, vm.currentMultiplayerUid())
        vm.updateGuestNickname("AliceLead")
        vm.enterGame()

        composeTestRule.setContent {
            MyApplicationTheme {
                Box(modifier = Modifier.size(width = 900.dp, height = 440.dp)) {
                    GuitarLegendsApp(viewModel = vm)
                }
            }
        }
        composeTestRule.waitForIdle()

        // 1. Abre Multiplayer e cria sala real
        composeTestRule.onNodeWithTag("menu_multiplayer").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.currentScreen.value is GameScreen.CompetitiveMultiplayer)

        vm.createMultiplayerRoom(MultiplayerMode.BATTLE_1V1, customRoomCode = roomCode)
        waitUntilWithMainLooper(timeoutMs = 15000) { vm.multiplayerRoom.value != null || vm.multiplayerError.value != null }
        assertNotNull("Room creation failed: ${vm.multiplayerError.value}", vm.multiplayerRoom.value)
        composeTestRule.waitForIdle()
        assertEquals(roomCode, vm.multiplayerRoom.value?.roomCode)
        composeTestRule.onNodeWithTag("multiplayer_room_code_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_invite_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("participant_card_$aliceUid").performScrollTo().assertIsDisplayed()

        // 2. Segundo jogador (Bob) entra na sala pelo código
        val (bobAuth, bobRepo) = getOrInitSecondaryBobRepo(app)
        val bobUid = awaitWithMainLooper { signInSecondary(bobAuth, "mp_bob_$tag@test.com") }
        val bobJoinRes = awaitWithMainLooper {
            bobRepo.joinRoom(roomCode, "BobBass", Instrument.BASS, Difficulty.HARD)
        }
        assertTrue("Bob must join Alice's room: ${bobJoinRes.exceptionOrNull()?.message}", bobJoinRes.isSuccess)

        // Espera sincronização em tempo real no ViewModel da Alice
        waitUntilWithMainLooper { vm.multiplayerParticipants.value.size >= 2 }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("multiplayer_participants_list")
            .performScrollToNode(hasTestTag("participant_card_$bobUid"))
        composeTestRule.onNodeWithTag("participant_card_$bobUid").assertIsDisplayed()

        // 3. Comunicação/Sincronização: Bob confirma pronto & envia pontuação; Alice troca faixa
        awaitWithMainLooper {
            bobRepo.updateParticipantLoadout(roomCode, "BobBass", Instrument.BASS, Difficulty.HARD, ready = true).getOrThrow()
            bobRepo.updateParticipantProgress(roomCode, score = 18500, combo = 27, rockMeter = 0.88f, starPowerActive = true).getOrThrow()
        }
        vm.updateMultiplayerTrack(AudioAnalyzer.BUILT_IN_TRACKS[1])

        waitUntilWithMainLooper {
            vm.multiplayerParticipants.value.find { it.userId == bobUid }?.ready == true &&
                vm.multiplayerRoom.value?.trackId == AudioAnalyzer.BUILT_IN_TRACKS[1].id
        }
        assertEquals(18500, vm.multiplayerParticipants.value.find { it.userId == bobUid }?.score)

        // 4. Tratamento de Desconexão & Reconexão
        awaitWithMainLooper {
            bobRepo.sendHeartbeat(roomCode, ParticipantConnectionState.DISCONNECTED).getOrThrow()
        }
        waitUntilWithMainLooper { vm.disconnectedPeerNicknames.value.contains("BobBass") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("multiplayer_connection_warning").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_reconnect_button").assertIsDisplayed()

        // 5. Código inexistente gera erro claro sem quebrar fluxo
        vm.joinMultiplayerRoom("NOPE-9999")
        waitUntilWithMainLooper { vm.multiplayerError.value != null }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("multiplayer_error_banner").assertIsDisplayed()

        // 6. Ambos os jogadores precisam registrar explicitamente o aceite de Strike.
        // O anfitrião usa o mesmo caminho do app; Bob confirma pela própria conta.
        vm.setMultiplayerStrikeEnabled(true)
        awaitWithMainLooper {
            bobRepo.updateParticipantStrikeConsent(roomCode, true).getOrThrow()
        }
        waitUntilWithMainLooper {
            vm.multiplayerParticipants.value.size >= 2 &&
                vm.multiplayerParticipants.value.all { it.strikeConsent == true }
        }

        // Inicia a partida sincronizada após Bob voltar online e sai limpo para o Menu Principal.
        awaitWithMainLooper {
            bobRepo.sendHeartbeat(roomCode, ParticipantConnectionState.ONLINE).getOrThrow()
        }
        vm.startMultiplayerMatch()
        waitUntilWithMainLooper {
            vm.multiplayerRoom.value?.statusEnum == RoomStatus.PLAYING &&
                vm.currentScreen.value is GameScreen.Gameplay
        }

        // Aguarda a navegação automática para Gameplay terminar antes do encerramento,
        // evitando uma corrida entre o observador da sala e o retorno ao menu.
        // Encerra partida/sala e retorna à página principal.
        vm.stop()
        vm.leaveMultiplayerRoom()
        vm.navigateTo(GameScreen.MainMenu, pushStack = false)
        waitUntilWithMainLooper { vm.currentScreen.value is GameScreen.MainMenu }
        composeTestRule.waitForIdle()
        assertNull(vm.multiplayerRoom.value)
        assertTrue(vm.currentScreen.value is GameScreen.MainMenu)
        composeTestRule.onNodeWithTag("menu_multiplayer").assertIsDisplayed()

        bobAuth.signOut()
    }
}
