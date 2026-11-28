package com.example

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.guitarlegends.analysis.AudioAnalyzer
import com.example.guitarlegends.data.LocalPlayerProgressRepository
import com.example.guitarlegends.model.*
import com.example.guitarlegends.ui.screens.*
import com.example.guitarlegends.viewmodel.GameScreen
import com.example.guitarlegends.viewmodel.GameViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h412dp-land")
class App2UpdatesAndFixesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "local_player_progress.json").delete()
        File(app.filesDir, "local_player_progress.json.tmp").delete()
        File(app.filesDir, "playlists.json").delete()
        File(app.filesDir, "playlists.json.tmp").delete()
        File(app.filesDir, "music_library_metadata.json").delete()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setVmStateFlow(vm: GameViewModel, fieldName: String, value: T) {
        val field = GameViewModel::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        val flow = field.get(vm) as MutableStateFlow<T>
        flow.value = value
    }

    @Test
    fun `01 - dynamic lanes by difficulty match canvas and touch controls without PISTA labels`() {
        assertEquals(3, Difficulty.EASY.lanesUsed)
        assertEquals(4, Difficulty.MEDIUM.lanesUsed)
        assertEquals(5, Difficulty.HARD.lanesUsed)
        assertEquals(5, Difficulty.EXPERT.lanesUsed)

        val vm = GameViewModel(app)
        vm.selectDifficulty(Difficulty.EASY)

        composeTestRule.setContent {
            MyApplicationTheme {
                LiveStageGameplayScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        // EASY: 3 pistas ativas (0, 1, 2), pistas 3 e 4 ausentes
        for (lane in 0..2) {
            composeTestRule.onNodeWithTag("fret_touch_button_$lane").assertExists()
        }
        composeTestRule.onNodeWithTag("fret_touch_button_3").assertDoesNotExist()
        composeTestRule.onNodeWithTag("fret_touch_button_4").assertDoesNotExist()
        assertTrue(
            composeTestRule.onAllNodesWithText("PISTA", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `02 - sustained notes grant tick and completion points, break on early release, and trigger pick FX`() {
        val vm = GameViewModel(app)
        vm.selectDifficulty(Difficulty.HARD)
        vm.setControlMode(ControlMode.DIRECT_TOUCH)

        val sustainNote = Note(
            id = 101L,
            timeMs = 1000L,
            lane = 0,
            durationMs = 600L,
            isStarPower = true
        )
        val brokenNote = Note(
            id = 102L,
            timeMs = 2000L,
            lane = 1,
            durationMs = 600L
        )
        setVmStateFlow(vm, "_activeNotes", listOf(sustainNote, brokenNote))
        setVmStateFlow(vm, "_currentSongTimeMs", 1005L)

        // Acerta nota sustentada -> inicia sustentação e gera Pick/Strum FX
        vm.onFretDown(0)
        assertTrue(sustainNote.isHit)
        assertTrue(sustainNote.isSustaining)
        assertFalse(sustainNote.sustainBroken)
        assertTrue(vm.activePickFlashes.value.containsKey(0))

        val scoreAfterInitialHit = vm.score.value
        assertTrue(scoreAfterInitialHit > 0)

        // Mantém segurado -> gera pontuação contínua por ticks
        setVmStateFlow(vm, "_currentSongTimeMs", 1120L)
        vm.processSustainingNotes(1120L)
        val scoreMidHold = vm.score.value
        assertTrue("Sustentar a nota deve somar pontos contínuos", scoreMidHold > scoreAfterInitialHit)

        // Completa o sustain até o final -> bônus final
        setVmStateFlow(vm, "_currentSongTimeMs", 1620L)
        vm.processSustainingNotes(1620L)
        assertTrue(sustainNote.sustainCompleted)
        assertFalse(sustainNote.isSustaining)
        assertTrue(vm.score.value > scoreMidHold)
        vm.onFretUp(0)

        // Soltar antes do fim interrompe a sustentação de forma limpa
        setVmStateFlow(vm, "_currentSongTimeMs", 2005L)
        vm.onFretDown(1)
        assertTrue(brokenNote.isSustaining)
        setVmStateFlow(vm, "_currentSongTimeMs", 2150L)
        vm.onFretUp(1)
        assertTrue("Soltar antes do fim deve marcar sustainBroken", brokenNote.sustainBroken)
        assertFalse(brokenNote.isSustaining)
        assertFalse(brokenNote.sustainCompleted)

        // Modo Palheta também dispara Pick Flash
        vm.setControlMode(ControlMode.FRET_AND_STRUM)
        val tapNote = Note(id = 103L, timeMs = 3000L, lane = 2, durationMs = 0L)
        setVmStateFlow(vm, "_activeNotes", listOf(tapNote))
        setVmStateFlow(vm, "_currentSongTimeMs", 3002L)
        vm.onFretDown(2)
        vm.onStrumBar()
        assertTrue(tapNote.isHit)
        assertTrue(vm.activePickFlashes.value.containsKey(2))
    }

    @Test
    fun `03 - pause menu exact 5-item order, live dual volume controls in Pause & Settings, and next track`() {
        val vm = GameViewModel(app)
        vm.selectTrack(AudioAnalyzer.BUILT_IN_TRACKS[0])
        vm.navigateTo(GameScreen.Gameplay)
        vm.togglePause()

        composeTestRule.setContent {
            MyApplicationTheme {
                LiveStageGameplayScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pause_dialog").assertIsDisplayed()
        val resumeNode = composeTestRule.onNodeWithTag("pause_resume_button").assertIsDisplayed()
        val restartNode = composeTestRule.onNodeWithTag("pause_restart_button").assertIsDisplayed()
        val volumeNode = composeTestRule.onNodeWithTag("pause_volume_button").assertIsDisplayed()
        val nextNode = composeTestRule.onNodeWithTag("pause_next_track_button").assertIsDisplayed()
        val exitNode = composeTestRule.onNodeWithTag("pause_exit_button").assertIsDisplayed()

        // Ordem vertical exata: 1. Continuar -> 2. Reiniciar -> 3. Volume -> 4. Próxima música -> 5. Sair
        val y1 = resumeNode.fetchSemanticsNode().boundsInRoot.top
        val y2 = restartNode.fetchSemanticsNode().boundsInRoot.top
        val y3 = volumeNode.fetchSemanticsNode().boundsInRoot.top
        val y4 = nextNode.fetchSemanticsNode().boundsInRoot.top
        val y5 = exitNode.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Ordem do menu de pausa deve ser estritamente sequencial", y1 < y2 && y2 < y3 && y3 < y4 && y4 < y5)

        composeTestRule.onNodeWithText("1. CONTINUAR").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. REINICIAR MÚSICA").assertIsDisplayed()
        composeTestRule.onNodeWithText("3. CONTROLE DE VOLUME").assertIsDisplayed()
        composeTestRule.onNodeWithText("4. PRÓXIMA MÚSICA").assertIsDisplayed()
        composeTestRule.onNodeWithText("5. SAIR").assertIsDisplayed()

        // Abre controle de volume dentro da Pausa e verifica os 5 canais exatos + edição numérica ao vivo
        volumeNode.performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pause_volume_dialog").assertIsDisplayed()

        val exactChannelLabels = listOf(
            "Volume geral",
            "Música",
            "Efeitos sonoros",
            "Interface",
            "Ambiente, palco e plateia"
        )
        exactChannelLabels.forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }

        composeTestRule.onNodeWithTag("pause_audio_input_music")
            .performScrollTo()
            .performTextReplacement("42")
        composeTestRule.waitForIdle()
        assertEquals(0.42f, vm.playerStats.value.studioProfile.audioSettings.musicVolume, 0.01f)
        assertEquals(0.42f, vm.audioPlayer.getCurrentMusicGain(), 0.02f)

        // Próxima música a partir da pausa avança faixa
        val firstTrackId = vm.selectedTrack.id
        composeTestRule.onNodeWithTag("pause_volume_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pause_next_track_button").performClick()
        composeTestRule.waitForIdle()
        assertNotEquals(firstTrackId, vm.selectedTrack.id)
    }

    @Test
    fun `04 - settings screen shows synchronized dual volume controls for all 5 channels`() {
        val vm = GameViewModel(app)
        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        val exactLabels = listOf(
            "Volume geral",
            "Música",
            "Efeitos sonoros",
            "Interface",
            "Ambiente, palco e plateia"
        )
        exactLabels.forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }

        composeTestRule.onNodeWithTag("audio_input_master").performTextReplacement("65")
        composeTestRule.waitForIdle()
        assertEquals(0.65f, vm.playerStats.value.studioProfile.audioSettings.masterVolume, 0.01f)
    }

    @Test
    fun `05 - Star Power remains independent when Strike is disabled`() {
        val vm = GameViewModel(app)
        vm.setLocalMultiplayerMatch(true)
        vm.setMultiplayerStrikeEnabled(false)
        setVmStateFlow(vm, "_starPowerEnergy", 0.9f)

        // Star Power continua funcionando mesmo sem Strike.
        vm.activateStarPower()
        assertTrue(vm.isStarPowerActive.value)
        assertFalse(vm.isStrikeEnabledForCurrentMatch())

        composeTestRule.setContent {
            MyApplicationTheme {
                LiveStageGameplayScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("multiplayer_dual_highways_canvas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("left_player_hud").assertIsDisplayed()
        composeTestRule.onNodeWithTag("right_player_hud").assertIsDisplayed()
        composeTestRule.onNodeWithTag("strike_disabled_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("star_power_button").assertIsDisplayed()
    }

    @Test
    fun `05b - Strike controls require unanimous consent and expose attack and defense actions`() {
        val vm = GameViewModel(app)
        val room = MultiplayerRoomCloud(
            id = "ROCK-TEST",
            roomCode = "ROCK-TEST",
            hostUid = "host",
            hostNickname = "Host",
            status = RoomStatus.PLAYING.name,
            memberUids = listOf("host", "guest"),
            strikeEnabled = true
        )
        setVmStateFlow(vm, "_multiplayerRoom", room)
        setVmStateFlow(
            vm,
            "_multiplayerParticipants",
            listOf(
                MultiplayerParticipantCloud(userId = "host", nickname = "Host", strikeConsent = true),
                MultiplayerParticipantCloud(userId = "guest", nickname = "Guest", strikeConsent = false)
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                LiveStageGameplayScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("star_power_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("strike_attack_inversion_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("strike_attack_fog_button").assertDoesNotExist()

        setVmStateFlow(
            vm,
            "_multiplayerParticipants",
            listOf(
                MultiplayerParticipantCloud(userId = "host", nickname = "Host", strikeConsent = true),
                MultiplayerParticipantCloud(userId = "guest", nickname = "Guest", strikeConsent = true)
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("strike_attack_inversion_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("strike_attack_fog_button").assertIsDisplayed()

        setVmStateFlow(
            vm,
            "_incomingStrike",
            StrikeEventCloud(
                id = "strike-test",
                attackerUid = "host",
                targetUid = "guest",
                power = "FOG",
                createdAtMs = System.currentTimeMillis()
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("strike_incoming_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("strike_defend_button").assertIsDisplayed()
    }

    @Test
    fun `06 - multiplayer lobby exposes Strike System pre-match toggle`() {
        val vm = GameViewModel(app)
        composeTestRule.setContent {
            MyApplicationTheme {
                MultiplayerScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("multiplayer_strike_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("multiplayer_strike_toggle_sem").performClick()
        composeTestRule.waitForIdle()
        assertFalse(vm.isStrikeEnabledForCurrentMatch())

        composeTestRule.onNodeWithTag("multiplayer_strike_toggle_com").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.isStrikeEnabledForCurrentMatch())
    }

    @Test
    fun `07 - daily rewards respect 100 LC weekly UTC Monday reset cap while matches stay uncapped`() {
        val repo = LocalPlayerProgressRepository(app)

        // Semana 1: Segunda e Terça UTC (ex.: 2026-11-23 e 2026-11-24)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val monMs = cal.timeInMillis
        val monWeek = LocalPlayerProgressRepository.utcWeekStartKey(monMs)
        val monKey = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sunBeforeKey = sdf.format(cal.time)

        val day1 = repo.claimGuestDailyReward(todayKey = monKey, yesterdayKey = sunBeforeKey, currentWeekKey = monWeek).getOrThrow()
        assertEquals(100, day1.first.coinsGranted)
        assertEquals(100, day1.second.stats.dailyRewardWeeklyCoinsClaimed)

        // Terça-feira na mesma semana (dia 2): atinge o teto semanal de 100 LC (0 moedas extras, mas cosmético do dia 2 ainda desbloqueia)
        cal.timeInMillis = monMs
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tueKey = sdf.format(cal.time)
        val day2 = repo.claimGuestDailyReward(todayKey = tueKey, yesterdayKey = monKey, currentWeekKey = monWeek).getOrThrow()
        assertEquals(0, day2.first.coinsGranted)
        assertEquals(100, day2.second.stats.dailyRewardWeeklyCoinsClaimed)
        assertTrue(day2.second.stats.unlockedCosmetics.contains("outfit_chamas_palco"))

        // Quarta-feira (dia 3): ainda na mesma semana, moedas = 0 mas conquista de 3 dias é desbloqueada
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val wedKey = sdf.format(cal.time)
        val day3 = repo.claimGuestDailyReward(todayKey = wedKey, yesterdayKey = tueKey, currentWeekKey = monWeek).getOrThrow()
        assertEquals(0, day3.first.coinsGranted)
        assertTrue(day3.second.stats.achievementsUnlocked.contains("ach_streak_3"))

        // Próxima segunda-feira UTC (2026-11-30): reinicia o limite semanal e volta a conceder 100 LC
        cal.set(2026, Calendar.NOVEMBER, 30, 12, 0, 0)
        val nextMonKey = sdf.format(cal.time)
        val nextWeek = LocalPlayerProgressRepository.utcWeekStartKey(cal.timeInMillis)
        assertNotEquals(monWeek, nextWeek)
        val nextWeekClaim = repo.claimGuestDailyReward(todayKey = nextMonKey, yesterdayKey = "2026-11-29", currentWeekKey = nextWeek).getOrThrow()
        assertEquals(100, nextWeekClaim.first.coinsGranted)
        assertEquals(100, nextWeekClaim.second.stats.dailyRewardWeeklyCoinsClaimed)

        // Partidas musicais não possuem limite semanal (podem acumular > 100 LC livremente)
        val vm = GameViewModel(app)
        vm.selectDifficulty(Difficulty.EXPERT)
        vm.selectTrack(AudioAnalyzer.BUILT_IN_TRACKS[0])
        val coinsBeforeMatches = vm.playerStats.value.coins
        repeat(6) { idx ->
            val note = Note(id = 900L + idx, timeMs = 1000L, lane = 0, durationMs = 0L)
            setVmStateFlow(vm, "_activeNotes", listOf(note))
            setVmStateFlow(vm, "_currentSongTimeMs", 1000L)
            vm.onFretDown(0)
            vm.onFretUp(0)
            vm.finishMatch(failed = false)
        }
        val coinsEarnedFromMatches = vm.playerStats.value.coins - coinsBeforeMatches
        assertTrue("Recompensas por partida não têm limite semanal (ganho: $coinsEarnedFromMatches)", coinsEarnedFromMatches > 100)
    }

    @Test
    fun `08 - playlists open as isolated folders without duplicates while preserving order`() {
        val vm = GameViewModel(app)
        val t1 = AudioAnalyzer.BUILT_IN_TRACKS[0]
        val t2 = AudioAnalyzer.BUILT_IN_TRACKS[1]
        val t3 = AudioAnalyzer.BUILT_IN_TRACKS[2]

        val pl1 = vm.createPlaylist("Favoritas Arena")
        val pl2 = vm.createPlaylist("Acústico Palco")
        vm.addTrackToPlaylist(pl1.id, t1.id)
        vm.addTrackToPlaylist(pl1.id, t2.id)
        vm.addTrackToPlaylist(pl1.id, t1.id) // duplicada na mesma pasta ignorada
        vm.addTrackToPlaylist(pl2.id, t3.id)

        val updatedPl1 = vm.playlists.value.first { it.id == pl1.id }
        assertEquals(listOf(t1.id, t2.id), updatedPl1.trackIds)

        composeTestRule.setContent {
            MyApplicationTheme {
                MusicLibraryScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("library_tab_playlists").performClick()
        composeTestRule.waitForIdle()

        // Raiz mostra pastas, não lista expandida das faixas
        composeTestRule.onNodeWithTag("playlists_folder_list")
            .performScrollToNode(hasTestTag("playlist_card_${pl2.id}"))
        composeTestRule.onNodeWithTag("playlist_card_${pl2.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlists_folder_list")
            .performScrollToNode(hasTestTag("playlist_card_${pl1.id}"))
        composeTestRule.onNodeWithTag("playlist_card_${pl1.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_track_${pl1.id}_${t1.id}").assertDoesNotExist()

        // Ao abrir a pasta 1, exibe somente suas músicas sem misturar com a pasta 2
        composeTestRule.onNodeWithTag("open_playlist_${pl1.id}").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("opened_playlist_header_${pl1.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_track_${pl1.id}_${t1.id}").assertExists()
        composeTestRule.onNodeWithTag("folder_track_${pl1.id}_${t2.id}").assertExists()
        composeTestRule.onNodeWithTag("folder_track_${pl1.id}_${t3.id}").assertDoesNotExist()

        // Volta às pastas
        composeTestRule.onNodeWithTag("close_playlist_folder_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("playlist_card_${pl2.id}").assertIsDisplayed()
    }

    @Test
    fun `09 - Luan Santana 3D vocalist is unlocked, selectable in Studio, and works across all 3 art styles`() {
        val vm = GameViewModel(app)
        assertTrue(vm.characters.any { it.id == "luan_santana" && it.isUnlocked })
        assertTrue(DEFAULT_UNLOCKED_COSMETICS.contains("luan_santana"))

        composeTestRule.setContent {
            MyApplicationTheme {
                CustomizationScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("character_3d_viewer").assertIsDisplayed()
        composeTestRule.onNodeWithTag("select_character_luan_santana").performClick()
        composeTestRule.waitForIdle()
        assertEquals("luan_santana", vm.playerStats.value.selectedCharacterId)

        composeTestRule.onNodeWithTag("top_art_style_cartoon").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CharacterArtStyle.CARTOON, vm.playerStats.value.customRocker.artStyle)

        composeTestRule.onNodeWithTag("top_art_style_anime").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CharacterArtStyle.ANIME, vm.playerStats.value.customRocker.artStyle)

        composeTestRule.onNodeWithTag("top_art_style_stylized_realistic").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CharacterArtStyle.STYLIZED_REALISTIC, vm.playerStats.value.customRocker.artStyle)
    }

    @Test
    fun `10 - guest nickname sanitization trims spaces and guards minimum length`() {
        val vm = GameViewModel(app)
        val initialNick = vm.playerStats.value.nickname

        vm.updateGuestNickname(" ")
        assertEquals(initialNick, vm.playerStats.value.nickname)

        vm.updateGuestNickname("  Luan_Legend  ")
        assertEquals("Luan_Legend", vm.playerStats.value.nickname)
    }
}
