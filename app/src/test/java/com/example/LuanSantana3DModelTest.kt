package com.example

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.guitarlegends.engine.InteractiveCharacter3DViewer
import com.example.guitarlegends.engine.computeLuanAnimationPose
import com.example.guitarlegends.engine.drawRockerCharacter3D
import com.example.guitarlegends.model.CharacterArtStyle
import com.example.guitarlegends.model.Instrument
import com.example.guitarlegends.model.PlayerStats
import com.example.guitarlegends.model.StagePoseExpression
import com.example.guitarlegends.ui.screens.CustomizationScreen
import com.example.guitarlegends.ui.screens.LiveStageGameplayScreen
import com.example.guitarlegends.viewmodel.GameViewModel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h412dp-land")
class LuanSantana3DModelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "local_player_progress.json").delete()
        File(app.filesDir, "local_player_progress.json.tmp").delete()
    }

    @Test
    fun `01 - Luan Santana 3D renders cleanly across 360 degrees, all art styles, and all instruments`() {
        val bitmap = android.graphics.Bitmap.createBitmap(420, 620, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bitmap))
        val drawScope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
        val density = androidx.compose.ui.unit.Density(1f)

        val angles = listOf(16f, 90f, 180f, 270f)
        val instruments = listOf(
            Instrument.VOCALS,
            Instrument.GUITAR,
            Instrument.ACOUSTIC_GUITAR,
            Instrument.BASS,
            Instrument.DRUMS
        )
        var renderedPasses = 0

        for (artStyle in CharacterArtStyle.values()) {
            val stats = PlayerStats(selectedCharacterId = "luan_santana").let { base ->
                base.copy(customRocker = base.customRocker.copy(artStyle = artStyle))
            }
            for (yaw in angles) {
                for (inst in instruments) {
                    drawScope.draw(
                        density,
                        androidx.compose.ui.unit.LayoutDirection.Ltr,
                        canvas,
                        androidx.compose.ui.geometry.Size(420f, 620f)
                    ) {
                        drawRockerCharacter3D(
                            drawScope = this,
                            stats = stats,
                            instrument = inst,
                            centerX = 210f,
                            baseY = 558f,
                            scale = 240f,
                            yawDeg = yaw,
                            pitchDeg = 5f,
                            animTimeSec = 1.25f,
                            isStarPowerActive = inst == Instrument.VOCALS,
                            combo = 22
                        )
                        renderedPasses++
                    }
                }
            }
        }
        assertEquals(60, renderedPasses)

        composeTestRule.setContent {
            MyApplicationTheme {
                InteractiveCharacter3DViewer(
                    stats = PlayerStats(selectedCharacterId = "luan_santana"),
                    instrument = Instrument.VOCALS,
                    modifier = Modifier.size(300.dp, 360.dp)
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("character_3d_viewer").assertIsDisplayed()
    }

    @Test
    fun `02 - facial and body animation rig drives vocal lip sync, blinking, expression, and star power`() {
        val restPose = computeLuanAnimationPose(
            animTimeSec = 0f,
            isStarPowerActive = false,
            combo = 0,
            instrument = Instrument.VOCALS,
            stageExpression = StagePoseExpression.SMIRK_LEGEND
        )
        assertEquals(0f, restPose.grooveBounce, 0.0001f)
        assertEquals(1f, restPose.eyeOpenScale, 0.0001f)

        val singingT1 = computeLuanAnimationPose(
            animTimeSec = 0.25f,
            isStarPowerActive = false,
            combo = 12,
            instrument = Instrument.VOCALS,
            stageExpression = StagePoseExpression.SMIRK_LEGEND
        )
        val singingT2 = computeLuanAnimationPose(
            animTimeSec = 0.65f,
            isStarPowerActive = true,
            combo = 30,
            instrument = Instrument.VOCALS,
            stageExpression = StagePoseExpression.SHOUTING_ROCK
        )
        assertNotEquals(singingT1.mouthOpenAmount, singingT2.mouthOpenAmount)
        assertNotEquals(singingT1.grooveBounce, singingT2.grooveBounce)
        assertTrue(singingT2.mouthOpenAmount > singingT1.mouthOpenAmount * 0.7f)

        // Verifica piscar natural no início do ciclo (~3.8s)
        val blinkMid = computeLuanAnimationPose(
            animTimeSec = 3.87f,
            isStarPowerActive = false,
            combo = 5,
            instrument = Instrument.VOCALS,
            stageExpression = StagePoseExpression.SMIRK_LEGEND
        )
        assertTrue("Expected eyeOpenScale to dip during blink", blinkMid.eyeOpenScale < 0.5f)
    }

    @Test
    fun `03 - InteractiveCharacter3DViewer and CustomizationScreen support Luan Santana with 360 controls`() {
        val vm = GameViewModel(app)
        vm.selectCharacter("luan_santana")
        val stats = vm.playerStats.value
        assertEquals("luan_santana", stats.selectedCharacterId)

        composeTestRule.setContent {
            MyApplicationTheme {
                InteractiveCharacter3DViewer(
                    stats = stats,
                    instrument = Instrument.VOCALS
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("character_3d_viewer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Lado").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("360° • 90°").assertIsDisplayed()
        composeTestRule.onNodeWithText("Costas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("360° • 180°").assertIsDisplayed()
    }

    @Test
    fun `04 - Luan Santana integrates with VOCALS category in LiveStageGameplayScreen`() {
        val vm = GameViewModel(app)
        vm.selectCharacter("luan_santana")
        vm.selectInstrument(Instrument.VOCALS)

        composeTestRule.setContent {
            MyApplicationTheme {
                LiveStageGameplayScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("luan_santana", vm.playerStats.value.selectedCharacterId)
        assertEquals(Instrument.VOCALS, vm.selectedInstrument)
        composeTestRule.onNodeWithTag("vocal_pitch_bar").assertIsDisplayed()
    }

    @Test
    fun `05 - Luan Santana is selectable and visible in CustomizationScreen Studio`() {
        val vm = GameViewModel(app)
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
    }
}
