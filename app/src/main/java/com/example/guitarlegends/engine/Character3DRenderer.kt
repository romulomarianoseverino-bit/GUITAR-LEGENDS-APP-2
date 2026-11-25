package com.example.guitarlegends.engine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guitarlegends.model.*
import com.example.guitarlegends.ui.components.RockAccentCyan
import com.example.guitarlegends.ui.components.RockAccentGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Especificação visual unificada da base 3D reutilizável de personagens.
 * Suporta tanto avatares customizados quanto integrantes oficiais (ex.: Luan Santana)
 * em todos os estilos (Realista Estilizado, Cartoon e Anime).
 */
internal data class CharacterVisualSpec(
    val characterId: String,
    val isLuanSantana: Boolean,
    val artStyle: CharacterArtStyle,
    val bodyModel: String,
    val skinColor: Color,
    val topColor: Color,
    val pantsColor: Color,
    val shoesColor: Color,
    val accentColor: Color,
    val hairColor: Color,
    val hairHighlightColor: Color,
    val hairStyle: String,
    val topClothing: String,
    val pantsClothing: String,
    val accessories: String,
    val bodyTattoos: String,
    val shoulderFactor: Float,
    val hasStubbleBeard: Boolean = false,
    val hasSlungAcousticGuitar: Boolean = false,
    val hasNecklace: Boolean = false,
    val hasWatchAndBracelets: Boolean = false,
    val hasCrownRoseEmbroidery: Boolean = false,
    val hasLeftInEarMonitor: Boolean = false
)

private fun resolveCharacterVisualSpec(stats: PlayerStats): CharacterVisualSpec {
    val studio = stats.studioProfile
    val rocker = stats.customRocker
    val artStyle = rocker.artStyle

    if (stats.selectedCharacterId == "luan_santana") {
        return CharacterVisualSpec(
            characterId = "luan_santana",
            isLuanSantana = true,
            artStyle = artStyle,
            bodyModel = "Atlético Vocalista",
            skinColor = Color(0xFFD9A685),
            topColor = Color(0xFF151519),
            pantsColor = Color(0xFF121216),
            shoesColor = Color(0xFF111115),
            accentColor = Color(0xFFD2D8E2),
            hairColor = Color(0xFF281A14),
            hairHighlightColor = Color(0xFF7D4E31),
            hairStyle = "Topete Caramel Luan",
            topClothing = "Camisa Preta Gola V",
            pantsClothing = "Calça Preta Bordado Rosa",
            accessories = "Relógio Prata, Pulseiras, Colar & Ponto Eletrônico",
            bodyTattoos = "Script Antebraço Direito",
            shoulderFactor = 1.03f,
            hasStubbleBeard = true,
            hasSlungAcousticGuitar = true,
            hasNecklace = true,
            hasWatchAndBracelets = true,
            hasCrownRoseEmbroidery = true,
            hasLeftInEarMonitor = true
        )
    }

    // Presets oficiais quando não estiver no avatar customizado e sem skin completa sobrescrevendo
    val presetPalette = when {
        studio.fullOutfitSkinId == "outfit_skin_inferno" -> listOf(
            Color(0xFF7B1113), Color(0xFF1A1014), Color(0xFF2B120C), Color(0xFFFF6D00), Color(rocker.hairColorHex)
        )
        studio.fullOutfitSkinId == "outfit_skin_cyber" -> listOf(
            Color(0xFF0E294B), Color(0xFF161336), Color(0xFF00E5FF), Color(0xFF00E5FF), Color(0xFF00E5FF)
        )
        studio.fullOutfitSkinId == "outfit_skin_ps2_legend" -> listOf(
            Color(0xFF1F1C26), Color(0xFF233246), Color(0xFF121016), Color(0xFFFFD700), Color(rocker.hairColorHex)
        )
        stats.selectedCharacterId == "roxie_riot" -> listOf(
            Color(0xFF21172A), Color(0xFF1A1D28), Color(0xFF16121A), Color(0xFFFF4081), Color(0xFFFF2E88)
        )
        stats.selectedCharacterId == "kai_cyber" -> listOf(
            Color(0xFF112340), Color(0xFF12142B), Color(0xFF00E5FF), Color(0xFF00E5FF), Color(0xFF00E5FF)
        )
        else -> listOf(
            Color(studio.topColorHex),
            Color(studio.pantsColorHex),
            Color(studio.shoesColorHex),
            Color(studio.accessoryColorHex),
            Color(rocker.hairColorHex)
        )
    }

    val shoulderFactor = when {
        studio.rockerBodyModel.contains("Heavy") -> 1.14f
        studio.rockerBodyModel.contains("Punk") || stats.selectedCharacterId == "roxie_riot" -> 0.94f
        studio.rockerBodyModel.contains("Cyber") || stats.selectedCharacterId == "kai_cyber" -> 1.04f
        else -> 1.0f
    }

    val resolvedHairStyle = when (stats.selectedCharacterId) {
        "roxie_riot" -> "Glam Rock Desfiado"
        "kai_cyber" -> "Undercut Cyber"
        else -> rocker.hairStyle
    }

    return CharacterVisualSpec(
        characterId = stats.selectedCharacterId,
        isLuanSantana = false,
        artStyle = artStyle,
        bodyModel = studio.rockerBodyModel,
        skinColor = Color(studio.skinToneHex),
        topColor = presetPalette[0],
        pantsColor = presetPalette[1],
        shoesColor = presetPalette[2],
        accentColor = presetPalette[3],
        hairColor = presetPalette[4],
        hairHighlightColor = presetPalette[4].copy(alpha = 0.72f),
        hairStyle = resolvedHairStyle,
        topClothing = rocker.topClothing,
        pantsClothing = rocker.pantsClothing,
        accessories = rocker.accessories,
        bodyTattoos = rocker.bodyTattoos,
        shoulderFactor = shoulderFactor
    )
}

/**
 * Renderizador 3D articulado do integrante da banda no estilo PlayStation 2 modernizado.
 * Suporta rotação completa 360° (frente, perfil e costas), base reutilizável entre todos os
 * integrantes/avatares e os 3 estilos visuais (Realista Estilizado, Cartoon e Anime),
 * além do modelo fiel do primeiro vocalista oficial 3D Luan Santana.
 */
fun drawRockerCharacter3D(
    drawScope: DrawScope,
    stats: PlayerStats,
    instrument: Instrument,
    centerX: Float,
    baseY: Float,
    scale: Float,
    yawDeg: Float = 16f,
    pitchDeg: Float = 4f,
    animTimeSec: Float = 0f,
    isStarPowerActive: Boolean = false,
    combo: Int = 0
) {
    val studio = stats.studioProfile
    val spec = resolveCharacterVisualSpec(stats)
    if (spec.isLuanSantana) {
        drawLuanSantana3D(
            drawScope = drawScope,
            spec = spec,
            instrument = instrument,
            centerX = centerX,
            baseY = baseY,
            scale = scale,
            yawDeg = yawDeg,
            pitchDeg = pitchDeg,
            animTimeSec = animTimeSec,
            isStarPowerActive = isStarPowerActive,
            combo = combo,
            studioProfile = studio
        )
        return
    }
    val yawNorm = ((yawDeg % 360f) + 360f) % 360f
    val facingFront = yawNorm <= 95f || yawNorm >= 265f

    val energyMult = if (isStarPowerActive) 1.55f else if (combo >= 15) 1.25f else 1.0f
    val grooveBounce = sin(animTimeSec * if (spec.isLuanSantana) 4.8f else 6.5f) * (if (spec.isLuanSantana) 0.028f else 0.045f) * energyMult
    val strumSwing = sin(animTimeSec * 12.0f) * 0.06f * energyMult
    val headNod = sin(animTimeSec * (if (spec.isLuanSantana) 4.8f else 6.5f) + 0.4f) * (if (spec.isLuanSantana) 0.022f else 0.03f) * energyMult
    val vocalBreath = (sin(animTimeSec * 5.2f) * 0.5f + 0.5f)

    val hipY = -0.82f + grooveBounce * 0.4f
    val shoulderY = -1.42f + grooveBounce
    val headY = -1.73f + grooveBounce + headNod

    fun pt(x: Float, y: Float, z: Float): Offset =
        projectVec3(Vec3(x, y, z), yawDeg, pitchDeg, centerX, baseY, scale).first

    fun zOf(x: Float, y: Float, z: Float): Float =
        projectVec3(Vec3(x, y, z), yawDeg, pitchDeg, centerX, baseY, scale).second

    val styleOutlineWidth = when (spec.artStyle) {
        CharacterArtStyle.CARTOON -> 3.3f
        CharacterArtStyle.ANIME -> 2.3f
        CharacterArtStyle.STYLIZED_REALISTIC -> if (spec.isLuanSantana) 1.4f else 1.8f
    }
    val styleOutlineAlpha = when (spec.artStyle) {
        CharacterArtStyle.CARTOON -> 0.85f
        CharacterArtStyle.ANIME -> 0.65f
        CharacterArtStyle.STYLIZED_REALISTIC -> 0.34f
    }

    // Sombra realista no palco
    val shadowCenter = pt(0f, 0f, 0f)
    drawScope.drawOval(
        color = if (isStarPowerActive) Color(0x5500E5FF) else Color(0x77000000),
        topLeft = Offset(shadowCenter.x - scale * 0.48f, shadowCenter.y - scale * 0.09f),
        size = Size(scale * 0.96f, scale * 0.18f)
    )

    // Violão nas costas (quando o personagem estiver de frente, desenha atrás do tronco primeiro!)
    fun drawBackSlungAcousticGuitar() {
        val bodyCenter = pt(0.24f, hipY - 0.14f, 0.16f)
        val upperBout = pt(0.18f, shoulderY + 0.25f, 0.16f)
        val neckTop = pt(-0.14f, shoulderY - 0.16f, 0.15f)
        val bodyColor = Color(0xFF2B2D32)
        val bodyEdge = Color(0xFF565B64)

        // Braço / headstock saindo atrás do ombro direito (esquerda do espectador)
        drawScope.drawLine(
            color = Color(0xFF231814),
            start = upperBout,
            end = neckTop,
            strokeWidth = (scale * 0.055f).coerceAtLeast(3f)
        )
        drawScope.drawCircle(
            color = Color(0xFF191311),
            radius = scale * 0.042f,
            center = neckTop
        )

        // Corpo dreadnought cinza-grafite texturizado com cutaway
        val lowerR = scale * 0.22f
        val upperR = scale * 0.16f
        drawScope.drawOval(
            color = bodyEdge,
            topLeft = Offset(bodyCenter.x - lowerR * 1.06f, bodyCenter.y - lowerR * 0.98f),
            size = Size(lowerR * 2.12f, lowerR * 1.96f)
        )
        drawScope.drawOval(
            color = bodyColor,
            topLeft = Offset(bodyCenter.x - lowerR, bodyCenter.y - lowerR * 0.92f),
            size = Size(lowerR * 2.0f, lowerR * 1.84f)
        )
        drawScope.drawOval(
            color = bodyColor,
            topLeft = Offset(upperBout.x - upperR, upperBout.y - upperR * 0.9f),
            size = Size(upperR * 2f, upperR * 1.8f)
        )

        // Se visto de costas, mostra tampa frontal do violão (boca/roseta, cavalete e cordas)
        if (!facingFront) {
            val soundHole = pt(0.20f, shoulderY + 0.38f, 0.19f)
            drawScope.drawCircle(color = Color(0xFF121316), radius = scale * 0.072f, center = soundHole)
            drawScope.drawCircle(color = Color(0xFF8B6F52), radius = scale * 0.088f, center = soundHole, style = Stroke(width = 2f))
            val bridgePos = pt(0.23f, hipY - 0.14f, 0.19f)
            drawScope.drawLine(
                color = Color(0xFF171311),
                start = Offset(bridgePos.x - scale * 0.06f, bridgePos.y - scale * 0.02f),
                end = Offset(bridgePos.x + scale * 0.06f, bridgePos.y + scale * 0.02f),
                strokeWidth = (scale * 0.032f).coerceAtLeast(2.5f)
            )
            // Correia de couro marrom nas costas ligada ao violão
            drawScope.drawLine(
                color = Color(0xFF8B4B28),
                start = pt(0.16f, shoulderY + 0.04f, 0.13f),
                end = pt(-0.16f, hipY - 0.04f, 0.13f),
                strokeWidth = (scale * 0.062f).coerceAtLeast(3.5f)
            )
        }
    }

    if (spec.hasSlungAcousticGuitar && facingFront) {
        drawBackSlungAcousticGuitar()
    }

    // 1. PERNAS & BOTAS ARTICULADAS
    val isShorts = spec.pantsClothing.contains("Bermuda")
    val isPlaid = spec.pantsClothing.contains("Xadrez")

    fun drawLeg(side: Float) {
        val hip = pt(side * 0.14f, hipY, 0f)
        val knee = pt(side * (if (spec.isLuanSantana) 0.15f else 0.19f), -0.42f, -0.04f)
        val ankle = pt(side * (if (spec.isLuanSantana) 0.17f else 0.22f), -0.07f, 0.02f)

        drawScope.drawLine(color = spec.pantsColor, start = hip, end = knee, strokeWidth = scale * 0.17f)
        drawScope.drawLine(
            color = if (isShorts) spec.skinColor else spec.pantsColor,
            start = knee,
            end = ankle,
            strokeWidth = scale * 0.15f
        )
        if (isPlaid && !isShorts) {
            drawScope.drawLine(color = Color(0xFFFF1744).copy(alpha = 0.55f), start = hip, end = knee, strokeWidth = 3f)
            drawScope.drawLine(color = Color(0xFFFF1744).copy(alpha = 0.55f), start = knee, end = ankle, strokeWidth = 3f)
        }

        // Bordado característico de coroa & rosa prateada na coxa esquerda do Luan Santana
        if (spec.hasCrownRoseEmbroidery && side > 0f && facingFront) {
            val crownPt = pt(0.16f, hipY + 0.14f, -0.105f)
            val rosePt = pt(0.165f, hipY + 0.23f, -0.105f)
            val stemBot = pt(0.17f, hipY + 0.34f, -0.105f)
            val embSilver = Color(0xFFBDC3CC)
            val embHighlight = Color(0xFFE7EBEF)

            // Coroa pequena acima da rosa
            val crownW = scale * 0.055f
            val crownH = scale * 0.030f
            val crownPath = Path().apply {
                moveTo(crownPt.x - crownW * 0.5f, crownPt.y + crownH * 0.5f)
                lineTo(crownPt.x - crownW * 0.5f, crownPt.y - crownH * 0.3f)
                lineTo(crownPt.x - crownW * 0.18f, crownPt.y + crownH * 0.05f)
                lineTo(crownPt.x, crownPt.y - crownH * 0.5f)
                lineTo(crownPt.x + crownW * 0.18f, crownPt.y + crownH * 0.05f)
                lineTo(crownPt.x + crownW * 0.5f, crownPt.y - crownH * 0.3f)
                lineTo(crownPt.x + crownW * 0.5f, crownPt.y + crownH * 0.5f)
                close()
            }
            drawScope.drawPath(crownPath, color = embSilver, style = Stroke(width = 1.6f))

            // Rosa bordada prateada em pétalas concêntricas + caule e folhas
            drawScope.drawCircle(color = embSilver.copy(alpha = 0.78f), radius = scale * 0.044f, center = rosePt)
            drawScope.drawCircle(color = Color(0xFF3E424A), radius = scale * 0.032f, center = rosePt, style = Stroke(width = 1.5f))
            drawScope.drawCircle(color = embHighlight, radius = scale * 0.017f, center = rosePt, style = Stroke(width = 1.3f))
            drawScope.drawLine(color = embSilver, start = Offset(rosePt.x, rosePt.y + scale * 0.042f), end = stemBot, strokeWidth = 1.6f)
            drawScope.drawOval(
                color = embSilver.copy(alpha = 0.85f),
                topLeft = Offset(stemBot.x - scale * 0.042f, stemBot.y - scale * 0.06f),
                size = Size(scale * 0.036f, scale * 0.018f)
            )
            drawScope.drawOval(
                color = embSilver.copy(alpha = 0.85f),
                topLeft = Offset(stemBot.x + scale * 0.008f, stemBot.y - scale * 0.05f),
                size = Size(scale * 0.036f, scale * 0.018f)
            )
        }

        // Calçado / Bota
        val toe = pt(side * 0.19f, -0.02f, -0.14f)
        drawScope.drawLine(color = spec.shoesColor, start = ankle, end = toe, strokeWidth = scale * 0.14f)
        drawScope.drawCircle(color = spec.shoesColor, radius = scale * 0.075f, center = ankle)
        drawScope.drawCircle(color = Color(0xFF282830), radius = scale * 0.068f, center = toe)
        if (!spec.isLuanSantana) {
            drawScope.drawCircle(color = spec.accentColor.copy(alpha = 0.7f), radius = scale * 0.024f, center = Offset((ankle.x + toe.x) * 0.5f, ankle.y))
        }
    }

    if (zOf(-0.18f, hipY, 0f) > zOf(0.18f, hipY, 0f)) {
        drawLeg(-1f); drawLeg(1f)
    } else {
        drawLeg(1f); drawLeg(-1f)
    }

    // 2. TRONCO / CAMISA OU JAQUETA 3D COM LARGURA DE OMBRO & PROFUNDIDADE
    val torsoFront = listOf(
        pt(-0.24f * spec.shoulderFactor, shoulderY, -0.11f),
        pt(0.24f * spec.shoulderFactor, shoulderY, -0.11f),
        pt(0.18f, hipY, -0.10f),
        pt(-0.18f, hipY, -0.10f)
    )
    val torsoBack = listOf(
        pt(-0.24f * spec.shoulderFactor, shoulderY, 0.11f),
        pt(0.24f * spec.shoulderFactor, shoulderY, 0.11f),
        pt(0.18f, hipY, 0.10f),
        pt(-0.18f, hipY, 0.10f)
    )

    for (edge in listOf(0 to 3, 1 to 2)) {
        val (a, b) = edge
        val sidePath = Path().apply {
            moveTo(torsoFront[a].x, torsoFront[a].y)
            lineTo(torsoBack[a].x, torsoBack[a].y)
            lineTo(torsoBack[b].x, torsoBack[b].y)
            lineTo(torsoFront[b].x, torsoFront[b].y)
            close()
        }
        drawScope.drawPath(sidePath, color = spec.topColor.copy(alpha = 0.85f), style = Fill)
    }

    val chestPts = if (facingFront) torsoFront else torsoBack
    val chestPath = Path().apply {
        chestPts.forEachIndexed { idx, off -> if (idx == 0) moveTo(off.x, off.y) else lineTo(off.x, off.y) }
        close()
    }
    drawScope.drawPath(chestPath, color = spec.topColor, style = Fill)
    drawScope.drawPath(chestPath, color = Color.Black.copy(alpha = styleOutlineAlpha), style = Stroke(width = styleOutlineWidth))

    if (facingFront) {
        if (spec.isLuanSantana) {
            // Decote em V da camisa social preta + colar prata fino com pingente
            val vLeft = pt(-0.075f, shoulderY + 0.01f, -0.115f)
            val vRight = pt(0.075f, shoulderY + 0.01f, -0.115f)
            val vTip = pt(0f, shoulderY + 0.18f, -0.115f)
            val neckSkinV = Path().apply {
                moveTo(vLeft.x, vLeft.y)
                lineTo(vRight.x, vRight.y)
                lineTo(vTip.x, vTip.y)
                close()
            }
            drawScope.drawPath(neckSkinV, color = spec.skinColor, style = Fill)
            // Gola dobrada da camisa preta
            drawScope.drawLine(color = Color(0xFF282932), start = vLeft, end = vTip, strokeWidth = 2.4f)
            drawScope.drawLine(color = Color(0xFF282932), start = vRight, end = vTip, strokeWidth = 2.4f)
            // Carcela e botões escuros centrais
            val placketBot = pt(0f, hipY + 0.02f, -0.115f)
            drawScope.drawLine(color = Color(0xFF23242C), start = vTip, end = placketBot, strokeWidth = 1.6f)
            listOf(0.28f, 0.42f).forEach { yOff ->
                drawScope.drawCircle(color = Color(0xFF333540), radius = scale * 0.013f, center = pt(0f, shoulderY + yOff, -0.118f))
            }
            // Colar prata fino com pingente no peito
            if (spec.hasNecklace) {
                val nL = pt(-0.048f, shoulderY + 0.02f, -0.118f)
                val nR = pt(0.048f, shoulderY + 0.02f, -0.118f)
                val nPend = pt(0f, shoulderY + 0.125f, -0.12f)
                drawScope.drawLine(color = Color(0xFFD6DCE5), start = nL, end = nPend, strokeWidth = 1.5f)
                drawScope.drawLine(color = Color(0xFFD6DCE5), start = nR, end = nPend, strokeWidth = 1.5f)
                drawScope.drawCircle(color = Color(0xFFE7ECF2), radius = scale * 0.016f, center = nPend)
            }
            // Alça de couro marrom diagonal do ombro esquerdo ao quadril direito
            if (spec.hasSlungAcousticGuitar) {
                val strapTop = pt(0.14f, shoulderY + 0.01f, -0.12f)
                val strapBot = pt(-0.15f, hipY - 0.03f, -0.12f)
                drawScope.drawLine(
                    color = Color(0xFF5D3018),
                    start = strapTop,
                    end = strapBot,
                    strokeWidth = (scale * 0.072f).coerceAtLeast(4.5f)
                )
                drawScope.drawLine(
                    color = Color(0xFF8F4F2B),
                    start = strapTop,
                    end = strapBot,
                    strokeWidth = (scale * 0.056f).coerceAtLeast(3.2f)
                )
            }
            // Cinto fino preto com fivela prateada discreta
            drawScope.drawLine(
                color = Color(0xFF0A0A0D),
                start = pt(-0.185f, hipY + 0.015f, -0.115f),
                end = pt(0.185f, hipY + 0.015f, -0.115f),
                strokeWidth = (scale * 0.042f).coerceAtLeast(3f)
            )
            val bucklePt = pt(0f, hipY + 0.015f, -0.12f)
            drawScope.drawRect(
                color = Color(0xFFC8CFD8),
                topLeft = Offset(bucklePt.x - scale * 0.038f, bucklePt.y - scale * 0.024f),
                size = Size(scale * 0.076f, scale * 0.048f),
                style = Stroke(width = 2f)
            )
        } else {
            if (spec.topClothing.contains("Jaqueta")) {
                val zTop = pt(0f, shoulderY + 0.04f, -0.12f)
                val zBot = pt(0f, hipY, -0.11f)
                drawScope.drawLine(color = Color(0xFFD0D5DD), start = zTop, end = zBot, strokeWidth = 3f)
                drawScope.drawCircle(color = spec.accentColor, radius = scale * 0.025f, center = pt(-0.17f, shoulderY + 0.09f, -0.12f))
                drawScope.drawCircle(color = spec.accentColor, radius = scale * 0.025f, center = pt(0.17f, shoulderY + 0.09f, -0.12f))
            } else if (spec.topClothing.contains("Colete")) {
                val emblem = pt(-0.08f, shoulderY + 0.18f, -0.12f)
                drawScope.drawRect(color = spec.accentColor, topLeft = Offset(emblem.x - 6f, emblem.y - 6f), size = Size(12f, 12f))
            } else {
                val emblem = pt(0f, shoulderY + 0.22f, -0.12f)
                drawScope.drawCircle(color = spec.accentColor.copy(alpha = 0.8f), radius = scale * 0.065f, center = emblem)
            }
            drawScope.drawLine(
                color = Color(0xFF0E0C12),
                start = pt(-0.19f, hipY + 0.02f, -0.115f),
                end = pt(0.19f, hipY + 0.02f, -0.115f),
                strokeWidth = scale * 0.055f
            )
            drawScope.drawCircle(color = spec.accentColor, radius = scale * 0.032f, center = pt(0f, hipY + 0.02f, -0.12f))
        }
    } else {
        if (spec.isLuanSantana) {
            if (spec.hasSlungAcousticGuitar) {
                drawBackSlungAcousticGuitar()
            }
        } else {
            val backLogo = pt(0f, shoulderY + 0.24f, 0.12f)
            drawScope.drawCircle(color = spec.accentColor.copy(alpha = 0.75f), radius = scale * 0.10f, center = backLogo, style = Stroke(width = 3f))
            drawScope.drawCircle(color = spec.hairColor.copy(alpha = 0.7f), radius = scale * 0.045f, center = backLogo)
        }
    }

    // Braços articulados com suporte a manga curta dobrada, tatuagens, relógio e pulseiras
    val isShortSleeveOrTank = spec.isLuanSantana || spec.topClothing.contains("Camiseta") || spec.topClothing.contains("Regata")
    val hasTattoos = !spec.bodyTattoos.contains("Sem Tatuagens")

    fun drawArm(side: Float, handTarget: Vec3) {
        val shoulder = pt(side * 0.25f * spec.shoulderFactor, shoulderY + 0.05f, 0f)
        val elbow = if (spec.isLuanSantana) {
            pt(side * 0.35f * spec.shoulderFactor, shoulderY + 0.28f, -0.10f)
        } else {
            pt(side * 0.34f * spec.shoulderFactor, shoulderY + 0.30f, -0.06f)
        }
        val hand = pt(handTarget.x, handTarget.y, handTarget.z)

        val upperColor = if (spec.topClothing.contains("Regata")) spec.skinColor else spec.topColor
        val lowerColor = if (isShortSleeveOrTank) spec.skinColor else spec.topColor

        if (spec.isLuanSantana) {
            // Manga curta preta com dobra/punho na metade do bíceps
            val cuff = Offset(shoulder.x * 0.42f + elbow.x * 0.58f, shoulder.y * 0.42f + elbow.y * 0.58f)
            drawScope.drawLine(color = spec.topColor, start = shoulder, end = cuff, strokeWidth = scale * 0.135f)
            drawScope.drawCircle(color = Color(0xFF282A34), radius = scale * 0.068f, center = cuff, style = Stroke(width = 2f))
            drawScope.drawLine(color = spec.skinColor, start = cuff, end = elbow, strokeWidth = scale * 0.112f)
            drawScope.drawLine(color = spec.skinColor, start = elbow, end = hand, strokeWidth = scale * 0.102f)

            // Tatuagem de escrita escura no antebraço direito (side < 0)
            if (side < 0f) {
                for (t in 1..4) {
                    val f = t * 0.14f + 0.12f
                    val tx = elbow.x * (1f - f) + hand.x * f
                    val ty = elbow.y * (1f - f) + hand.y * f
                    drawScope.drawCircle(color = Color(0xFF262D38).copy(alpha = 0.72f), radius = scale * 0.015f, center = Offset(tx, ty))
                }
            }

            // Relógio prata com mostrador branco + pulseira escura + corrente prata no pulso direito
            if (spec.hasWatchAndBracelets && side < 0f) {
                val wristWatch = Offset(elbow.x * 0.25f + hand.x * 0.75f, elbow.y * 0.25f + hand.y * 0.75f)
                val wristBracelet = Offset(elbow.x * 0.36f + hand.x * 0.64f, elbow.y * 0.36f + hand.y * 0.64f)
                drawScope.drawCircle(color = Color(0xFF1F2126), radius = scale * 0.052f, center = wristBracelet)
                drawScope.drawCircle(color = Color(0xFFC8D0DA), radius = scale * 0.050f, center = wristBracelet, style = Stroke(width = 1.6f))
                drawScope.drawCircle(color = Color(0xFFD5DCE4), radius = scale * 0.056f, center = wristWatch)
                drawScope.drawCircle(color = Color(0xFFF6F7FA), radius = scale * 0.040f, center = wristWatch)
            }
        } else {
            drawScope.drawLine(color = upperColor, start = shoulder, end = elbow, strokeWidth = scale * 0.12f)
            drawScope.drawLine(color = lowerColor, start = elbow, end = hand, strokeWidth = scale * 0.10f)

            if (hasTattoos && isShortSleeveOrTank) {
                val midForearm = Offset((elbow.x + hand.x) * 0.5f, (elbow.y + hand.y) * 0.5f)
                val tatColor = if (spec.bodyTattoos.contains("Raio") || spec.bodyTattoos.contains("Chamas")) {
                    Color(0xFFFF3D00)
                } else {
                    Color(0xFF1F2B38)
                }
                drawScope.drawCircle(color = tatColor, radius = scale * 0.036f, center = midForearm)
            }

            if (spec.accessories.contains("Munhequeiras") || spec.accessories.contains("Bracelete")) {
                val wrist = Offset(elbow.x * 0.22f + hand.x * 0.78f, elbow.y * 0.22f + hand.y * 0.78f)
                drawScope.drawCircle(color = Color.Black, radius = scale * 0.052f, center = wrist)
                drawScope.drawCircle(color = spec.accentColor, radius = scale * 0.052f, center = wrist, style = Stroke(width = 2f))
            }
        }
        drawScope.drawCircle(color = spec.skinColor, radius = scale * 0.054f, center = hand)
    }

    if (spec.pantsClothing.contains("Rasgada") && facingFront) {
        listOf(-0.18f, 0.18f).forEach { sx ->
            val kneeRip = pt(sx, -0.44f, -0.09f)
            drawScope.drawLine(
                color = spec.skinColor.copy(alpha = 0.85f),
                start = Offset(kneeRip.x - scale * 0.045f, kneeRip.y),
                end = Offset(kneeRip.x + scale * 0.045f, kneeRip.y),
                strokeWidth = (scale * 0.032f).coerceAtLeast(2.5f)
            )
        }
    }

    // 3. CABEÇA 3D + PESCOÇO + EXPRESSÃO FACIAL + CABELO VOLUMÉTRICO + ORELHA / IEM
    val neckBase = pt(0f, shoulderY + 0.03f, 0f)
    val headCenter = pt(0f, headY, 0f)
    drawScope.drawLine(color = spec.skinColor, start = neckBase, end = headCenter, strokeWidth = scale * 0.11f)

    fun drawHair(backOnly: Boolean = false) {
        when {
            spec.isLuanSantana -> {
                // Cabelo castanho-escuro nas laterais/nuca + topete volumoso castanho-caramelo texturizado
                val sideLeft = pt(-0.14f, headY - 0.05f, 0.01f)
                val sideRight = pt(0.14f, headY - 0.05f, 0.01f)
                val backCrown = pt(0f, headY - 0.06f, 0.08f)
                if (!facingFront || backOnly) {
                    drawScope.drawOval(
                        color = spec.hairColor,
                        topLeft = Offset(backCrown.x - scale * 0.175f, backCrown.y - scale * 0.15f),
                        size = Size(scale * 0.35f, scale * 0.30f)
                    )
                }
                // Laterais curtas e costeletas
                drawScope.drawOval(
                    color = spec.hairColor,
                    topLeft = Offset(sideLeft.x - scale * 0.04f, sideLeft.y - scale * 0.09f),
                    size = Size(scale * 0.08f, scale * 0.17f)
                )
                drawScope.drawOval(
                    color = spec.hairColor,
                    topLeft = Offset(sideRight.x - scale * 0.04f, sideRight.y - scale * 0.09f),
                    size = Size(scale * 0.08f, scale * 0.17f)
                )
                // Topete volumoso penteado para cima e levemente para o lado
                val quiffCenter = pt(-0.02f, headY - 0.18f, -0.02f)
                drawScope.drawOval(
                    color = spec.hairColor,
                    topLeft = Offset(quiffCenter.x - scale * 0.165f, quiffCenter.y - scale * 0.105f),
                    size = Size(scale * 0.33f, scale * 0.21f)
                )
                // Mechas castanho-caramelo em camadas fiéis à fotografia
                for (i in -2..2) {
                    val p0 = pt(i * 0.04f - 0.02f, headY - 0.12f, -0.09f)
                    val p1 = pt(i * 0.045f + 0.03f, headY - 0.27f, -0.01f)
                    drawScope.drawLine(
                        color = if (i % 2 == 0) spec.hairHighlightColor else spec.hairColor,
                        start = p0,
                        end = p1,
                        strokeWidth = (scale * 0.038f).coerceAtLeast(2.6f)
                    )
                }
            }
            spec.hairStyle.contains("Moicano") -> {
                for (i in 0..4) {
                    val f = i / 4f
                    val zSpike = -0.09f + f * 0.22f
                    val tip = pt(0f, headY - 0.30f + f * 0.05f, zSpike)
                    val b1 = pt(-0.04f, headY - 0.11f, zSpike - 0.03f)
                    val b2 = pt(0.04f, headY - 0.11f, zSpike + 0.03f)
                    val spike = Path().apply {
                        moveTo(b1.x, b1.y); lineTo(tip.x, tip.y); lineTo(b2.x, b2.y); close()
                    }
                    drawScope.drawPath(spike, color = spec.hairColor, style = Fill)
                }
            }
            spec.hairStyle.contains("Metalhead") || spec.hairStyle.contains("Dreads") -> {
                val strands = if (spec.hairStyle.contains("Dreads")) 7 else 5
                for (s in -strands / 2..strands / 2) {
                    val xOff = s * 0.055f
                    val topH = pt(xOff, headY - 0.16f, 0.03f)
                    val botH = pt(xOff * 1.35f, headY + 0.24f + headNod, 0.10f)
                    drawScope.drawLine(
                        color = spec.hairColor,
                        start = topH,
                        end = botH,
                        strokeWidth = scale * if (spec.hairStyle.contains("Dreads")) 0.055f else 0.075f
                    )
                }
            }
            else -> {
                val crown = pt(0f, headY - 0.14f, -0.02f)
                drawScope.drawOval(
                    color = spec.hairColor,
                    topLeft = Offset(crown.x - scale * 0.18f, crown.y - scale * 0.12f),
                    size = Size(scale * 0.36f, scale * 0.22f)
                )
            }
        }
    }

    if (facingFront && !spec.isLuanSantana) drawHair()

    val headR = scale * when (spec.artStyle) {
        CharacterArtStyle.ANIME -> if (spec.isLuanSantana) 0.175f else 0.185f
        CharacterArtStyle.CARTOON -> 0.19f
        CharacterArtStyle.STYLIZED_REALISTIC -> if (spec.isLuanSantana) 0.162f else 0.17f
    }
    val isGothicFace = !spec.isLuanSantana && spec.bodyTattoos.contains("Gótica")
    val faceFill = if (isGothicFace) Color(0xFFECEAE6) else spec.skinColor

    if (spec.isLuanSantana) {
        // Orelhas laterais + ponto eletrônico preto e brinco prata na orelha esquerda
        val earLeft = pt(0.155f, headY + 0.01f, -0.02f)
        val earRight = pt(-0.155f, headY + 0.01f, -0.02f)
        drawScope.drawOval(
            color = spec.skinColor.copy(alpha = 0.94f),
            topLeft = Offset(earRight.x - headR * 0.16f, earRight.y - headR * 0.26f),
            size = Size(headR * 0.28f, headR * 0.48f)
        )
        drawScope.drawOval(
            color = spec.skinColor.copy(alpha = 0.94f),
            topLeft = Offset(earLeft.x - headR * 0.12f, earLeft.y - headR * 0.26f),
            size = Size(headR * 0.28f, headR * 0.48f)
        )
        if (spec.hasLeftInEarMonitor) {
            drawScope.drawCircle(color = Color(0xFF141418), radius = headR * 0.13f, center = earLeft)
            drawScope.drawCircle(color = Color(0xFFDDE3EB), radius = headR * 0.045f, center = Offset(earLeft.x + headR * 0.02f, earLeft.y + headR * 0.22f))
        }

        // Rosto oval alongado e mandíbula definida fiel à fotografia
        drawScope.drawOval(
            color = faceFill,
            topLeft = Offset(headCenter.x - headR * 0.92f, headCenter.y - headR * 1.04f),
            size = Size(headR * 1.84f, headR * 2.12f)
        )
        drawScope.drawOval(
            color = Color.Black.copy(alpha = styleOutlineAlpha),
            topLeft = Offset(headCenter.x - headR * 0.92f, headCenter.y - headR * 1.04f),
            size = Size(headR * 1.84f, headR * 2.12f),
            style = Stroke(width = styleOutlineWidth)
        )
    } else {
        drawScope.drawCircle(color = faceFill, radius = headR, center = headCenter)
        if (spec.artStyle == CharacterArtStyle.CARTOON) {
            drawScope.drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = headR * 0.72f,
                center = Offset(headCenter.x - headR * 0.14f, headCenter.y - headR * 0.14f)
            )
        }
        drawScope.drawCircle(
            color = if (spec.artStyle == CharacterArtStyle.ANIME && isStarPowerActive) Color(0xFF00E5FF) else Color.Black.copy(alpha = styleOutlineAlpha),
            radius = headR,
            center = headCenter,
            style = Stroke(width = styleOutlineWidth)
        )
    }

    if (facingFront) {
        if (spec.isLuanSantana) {
            // Cabelo por cima da testa mantendo fidelidade ao topete da foto
            drawHair()

            val eyeL = pt(-0.052f, headY - 0.028f, -0.145f)
            val eyeR = pt(0.052f, headY - 0.028f, -0.145f)
            val browL1 = pt(-0.090f, headY - 0.068f, -0.145f)
            val browL2 = pt(-0.020f, headY - 0.075f, -0.145f)
            val browR1 = pt(0.020f, headY - 0.075f, -0.145f)
            val browR2 = pt(0.090f, headY - 0.068f, -0.145f)

            // Sobrancelhas expressivas escuras
            drawScope.drawLine(color = Color(0xFF241610), start = browL1, end = browL2, strokeWidth = (headR * 0.11f).coerceAtLeast(2.2f))
            drawScope.drawLine(color = Color(0xFF241610), start = browR1, end = browR2, strokeWidth = (headR * 0.11f).coerceAtLeast(2.2f))

            // Olhos castanhos adaptáveis ao estilo visual (Realista / Cartoon / Anime)
            val irisR = when (spec.artStyle) {
                CharacterArtStyle.ANIME -> headR * 0.15f
                CharacterArtStyle.CARTOON -> headR * 0.135f
                CharacterArtStyle.STYLIZED_REALISTIC -> headR * 0.105f
            }
            drawScope.drawOval(
                color = Color(0xFFFAF7F4),
                topLeft = Offset(eyeL.x - irisR * 1.35f, eyeL.y - irisR * 0.85f),
                size = Size(irisR * 2.7f, irisR * 1.7f)
            )
            drawScope.drawOval(
                color = Color(0xFFFAF7F4),
                topLeft = Offset(eyeR.x - irisR * 1.35f, eyeR.y - irisR * 0.85f),
                size = Size(irisR * 2.7f, irisR * 1.7f)
            )
            drawScope.drawCircle(color = Color(0xFF3A2115), radius = irisR * 0.92f, center = eyeL)
            drawScope.drawCircle(color = Color(0xFF3A2115), radius = irisR * 0.92f, center = eyeR)
            drawScope.drawCircle(color = Color.White, radius = irisR * 0.28f, center = Offset(eyeL.x - irisR * 0.26f, eyeL.y - irisR * 0.26f))
            drawScope.drawCircle(color = Color.White, radius = irisR * 0.28f, center = Offset(eyeR.x - irisR * 0.26f, eyeR.y - irisR * 0.26f))

            // Nariz sutilmente sombreado
            val noseTop = pt(0f, headY - 0.015f, -0.155f)
            val noseTip = pt(0f, headY + 0.032f, -0.165f)
            drawScope.drawLine(color = Color(0xFFB57C5B), start = noseTop, end = noseTip, strokeWidth = (headR * 0.075f).coerceAtLeast(1.8f))

            // Barba rala (stubble) na mandíbula, queixo e bigode
            if (spec.hasStubbleBeard) {
                val jawColor = Color(0xFF33231B).copy(alpha = 0.42f)
                val jawLeft = pt(-0.115f, headY + 0.045f, -0.11f)
                val jawMid = pt(0f, headY + 0.145f, -0.125f)
                val jawRight = pt(0.115f, headY + 0.045f, -0.11f)
                drawScope.drawLine(color = jawColor, start = jawLeft, end = jawMid, strokeWidth = (headR * 0.14f).coerceAtLeast(2.2f))
                drawScope.drawLine(color = jawColor, start = jawMid, end = jawRight, strokeWidth = (headR * 0.14f).coerceAtLeast(2.2f))
                // Bigode sutil acima do sorriso
                drawScope.drawLine(
                    color = Color(0xFF2B1C16).copy(alpha = 0.56f),
                    start = pt(-0.068f, headY + 0.056f, -0.155f),
                    end = pt(0.068f, headY + 0.056f, -0.155f),
                    strokeWidth = (headR * 0.08f).coerceAtLeast(2.0f)
                )
            }

            // Sorriso aberto com dentes brancos + leve abertura vocal animada
            val mouthPos = pt(0f, headY + 0.082f, -0.155f)
            val singOpen = if (animTimeSec > 0f) vocalBreath * 0.05f else 0f
            val smileW = headR * 0.62f
            val smileH = headR * (0.28f + singOpen)
            drawScope.drawOval(
                color = Color(0xFFA7584D),
                topLeft = Offset(mouthPos.x - smileW * 0.54f, mouthPos.y - smileH * 0.54f),
                size = Size(smileW * 1.08f, smileH * 1.08f)
            )
            drawScope.drawOval(
                color = Color(0xFFFBFBFB),
                topLeft = Offset(mouthPos.x - smileW * 0.46f, mouthPos.y - smileH * 0.38f),
                size = Size(smileW * 0.92f, smileH * 0.66f)
            )
        } else {
            val eyeL = pt(-0.055f, headY - 0.02f, -0.15f)
            val eyeR = pt(0.055f, headY - 0.02f, -0.15f)
            val hasSunglasses = spec.accessories.contains("Óculos")

            if (isGothicFace) {
                drawScope.drawOval(
                    color = Color(0xFF141118),
                    topLeft = Offset(eyeL.x - headR * 0.26f, eyeL.y - headR * 0.22f),
                    size = Size(headR * 0.44f, headR * 0.44f)
                )
                drawScope.drawOval(
                    color = Color(0xFF141118),
                    topLeft = Offset(eyeR.x - headR * 0.18f, eyeR.y - headR * 0.22f),
                    size = Size(headR * 0.44f, headR * 0.44f)
                )
            }

            if (hasSunglasses) {
                val lensColor = if (spec.accessories.contains("Neon")) Color(0xFF00E5FF) else Color(0xFF101018)
                drawScope.drawRoundRect(
                    color = lensColor,
                    topLeft = Offset(eyeL.x - headR * 0.34f, eyeL.y - headR * 0.20f),
                    size = Size(headR * 0.56f, headR * 0.36f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawScope.drawRoundRect(
                    color = lensColor,
                    topLeft = Offset(eyeR.x - headR * 0.22f, eyeR.y - headR * 0.20f),
                    size = Size(headR * 0.56f, headR * 0.36f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                drawScope.drawLine(color = spec.accentColor, start = eyeL, end = eyeR, strokeWidth = 2.5f)
            } else {
                val irisRadius = when (spec.artStyle) {
                    CharacterArtStyle.ANIME -> headR * 0.17f
                    CharacterArtStyle.CARTOON -> headR * 0.15f
                    CharacterArtStyle.STYLIZED_REALISTIC -> headR * 0.12f
                }
                val irisColor = if (isGothicFace) Color.White else if (spec.artStyle == CharacterArtStyle.ANIME) spec.accentColor else Color(0xFF18141E)
                if (spec.artStyle == CharacterArtStyle.ANIME) {
                    drawScope.drawCircle(color = Color.White, radius = irisRadius * 1.25f, center = eyeL)
                    drawScope.drawCircle(color = Color.White, radius = irisRadius * 1.25f, center = eyeR)
                }
                drawScope.drawCircle(color = irisColor, radius = irisRadius, center = eyeL)
                drawScope.drawCircle(color = irisColor, radius = irisRadius, center = eyeR)
                if (spec.artStyle != CharacterArtStyle.STYLIZED_REALISTIC) {
                    drawScope.drawCircle(color = Color.White, radius = irisRadius * 0.38f, center = Offset(eyeL.x - irisRadius * 0.3f, eyeL.y - irisRadius * 0.3f))
                    drawScope.drawCircle(color = Color.White, radius = irisRadius * 0.38f, center = Offset(eyeR.x - irisRadius * 0.3f, eyeR.y - irisRadius * 0.3f))
                }
            }

            val mouthPos = pt(0f, headY + 0.075f, -0.15f)
            val effExpr = if (isStarPowerActive || combo >= 25) StagePoseExpression.SHOUTING_ROCK else studio.stageExpression
            when (effExpr) {
                StagePoseExpression.SHOUTING_ROCK -> {
                    drawScope.drawOval(
                        color = Color(0xFF3E0A16),
                        topLeft = Offset(mouthPos.x - headR * 0.26f, mouthPos.y - headR * 0.16f),
                        size = Size(headR * 0.52f, headR * 0.34f)
                    )
                }
                StagePoseExpression.SMIRK_LEGEND -> {
                    drawScope.drawLine(
                        color = Color(0xFF3A1F1D),
                        start = Offset(mouthPos.x - headR * 0.22f, mouthPos.y),
                        end = Offset(mouthPos.x + headR * 0.26f, mouthPos.y - headR * 0.07f),
                        strokeWidth = 2.5f
                    )
                }
                else -> {
                    drawScope.drawLine(
                        color = Color(0xFF3A1F1D),
                        start = Offset(mouthPos.x - headR * 0.20f, mouthPos.y),
                        end = Offset(mouthPos.x + headR * 0.20f, mouthPos.y),
                        strokeWidth = 2.5f
                    )
                }
            }
        }
    } else {
        drawHair(backOnly = true)
    }

    // 4. BRAÇOS E INSTRUMENTO / MICROFONE NO PEDESTAL EM 3D
    val activeInstConfig = when (instrument) {
        Instrument.GUITAR -> studio.guitarConfig
        Instrument.ACOUSTIC_GUITAR -> studio.acousticConfig
        Instrument.BASS -> studio.bassConfig
        Instrument.DRUMS -> studio.drumsConfig
        Instrument.VOCALS -> studio.vocalConfig
    }

    when (instrument) {
        Instrument.GUITAR, Instrument.ACOUSTIC_GUITAR, Instrument.BASS -> {
            val frettingHand = Vec3(-0.34f, shoulderY + 0.18f, -0.26f)
            val strummingHand = Vec3(0.14f, hipY - 0.12f + strumSwing, -0.26f)
            drawArm(-1f, frettingHand)
            val instCenter = pt(0.02f, hipY - 0.22f, -0.22f)
            drawInstrument3D(
                drawScope = drawScope,
                instrument = instrument,
                config = activeInstConfig,
                centerX = instCenter.x,
                centerY = instCenter.y,
                scale = scale * 0.52f,
                yawDeg = yawDeg + 18f,
                pitchDeg = pitchDeg - 12f,
                pulsePhase = animTimeSec
            )
            drawArm(1f, strummingHand)
        }
        Instrument.DRUMS -> {
            drawArm(-1f, Vec3(-0.26f, hipY - 0.12f + strumSwing, -0.24f))
            drawArm(1f, Vec3(0.26f, hipY - 0.12f - strumSwing, -0.24f))
            val drumPos = pt(0f, hipY + 0.10f, -0.35f)
            drawInstrument3D(
                drawScope = drawScope,
                instrument = Instrument.DRUMS,
                config = activeInstConfig,
                centerX = drumPos.x,
                centerY = drumPos.y,
                scale = scale * 0.48f,
                yawDeg = yawDeg,
                pitchDeg = pitchDeg,
                pulsePhase = animTimeSec
            )
        }
        Instrument.VOCALS -> {
            if (spec.isLuanSantana) {
                // Pedestal reto preto à direita do artista (lado esquerdo da fotografia) + microfone preto inclinado
                val standBase = pt(-0.32f, -0.02f, -0.24f)
                val standGrip = pt(-0.30f, headY + 0.24f, -0.24f)
                val micHead = pt(-0.44f, headY + 0.20f, -0.27f)
                val micTail = pt(-0.24f, headY + 0.25f, -0.23f)

                // Pedestal reto em haste dupla com base
                drawScope.drawOval(
                    color = Color(0xFF101115),
                    topLeft = Offset(standBase.x - scale * 0.12f, standBase.y - scale * 0.03f),
                    size = Size(scale * 0.24f, scale * 0.06f)
                )
                drawScope.drawLine(
                    color = Color(0xFF15161B),
                    start = standBase,
                    end = standGrip,
                    strokeWidth = (scale * 0.034f).coerceAtLeast(2.8f)
                )
                drawScope.drawLine(
                    color = Color(0xFF30333C),
                    start = Offset(standBase.x, (standBase.y + standGrip.y) * 0.5f),
                    end = standGrip,
                    strokeWidth = (scale * 0.022f).coerceAtLeast(2f)
                )
                // Clipe e corpo do microfone preto inclinado
                drawScope.drawLine(
                    color = Color(0xFF14151A),
                    start = micTail,
                    end = micHead,
                    strokeWidth = (scale * 0.048f).coerceAtLeast(3.5f)
                )
                drawScope.drawCircle(
                    color = Color(0xFF1D1F26),
                    radius = (scale * 0.042f).coerceAtLeast(3.5f),
                    center = micHead
                )

                // Ambas as mãos segurando o microfone no pedestal como na foto oficial
                val rightHandMic = Vec3(-0.26f, headY + 0.23f + grooveBounce * 0.2f, -0.24f)
                val leftHandMic = Vec3(-0.19f, headY + 0.27f + grooveBounce * 0.2f, -0.23f)
                drawArm(-1f, rightHandMic)
                drawArm(1f, leftHandMic)
            } else {
                drawArm(-1f, Vec3(-0.28f, hipY + 0.04f, -0.06f))
                drawArm(1f, Vec3(0.08f, headY + 0.18f + strumSwing * 0.5f, -0.24f))
                val micPos = pt(0.12f, hipY - 0.05f, -0.26f)
                drawInstrument3D(
                    drawScope = drawScope,
                    instrument = Instrument.VOCALS,
                    config = activeInstConfig,
                    centerX = micPos.x,
                    centerY = micPos.y,
                    scale = scale * 0.55f,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    pulsePhase = animTimeSec
                )
            }
        }
    }
}

/**
 * Desenha integrantes de apoio da banda no palco (baterista no praticável elevado + baixista/guitarrista base).
 */
fun drawStageBandCompanions(
    drawScope: DrawScope,
    width: Float,
    height: Float,
    timeSec: Float,
    isStarPowerActive: Boolean,
    primaryInstrument: Instrument,
    stats: PlayerStats
) {
    // 1. Baterista elevado ao fundo à direita da esteira
    if (primaryInstrument != Instrument.DRUMS) {
        val riserX = width * 0.70f
        val riserY = height * 0.58f
        val riserW = width * 0.16f
        val riserH = height * 0.07f

        drawScope.drawRect(
            color = Color(0xFF1A1424),
            topLeft = Offset(riserX - riserW * 0.5f, riserY),
            size = Size(riserW, riserH)
        )
        drawScope.drawRect(
            color = if (isStarPowerActive) Color(0xFF00E5FF) else Color(0xFFFF9100).copy(alpha = 0.6f),
            topLeft = Offset(riserX - riserW * 0.5f, riserY),
            size = Size(riserW, riserH),
            style = Stroke(width = 2f)
        )

        val drumScale = (height * 0.19f).coerceIn(55f, 125f)
        val drummerStats = stats.copy(
            selectedCharacterId = "axel_rocker",
            customRocker = CustomRockerAppearance(
                hairStyle = "Moicano Punk Clássico",
                hairColorHex = 0xFFFFD700L,
                topClothing = "Regata Rasgada"
            ),
            studioProfile = stats.studioProfile.copy(
                fullOutfitSkinId = "outfit_skin_none",
                topColorHex = 0xFF282032L,
                rockerBodyModel = "Punk Esguio"
            )
        )
        drawRockerCharacter3D(
            drawScope = drawScope,
            stats = drummerStats,
            instrument = Instrument.DRUMS,
            centerX = riserX,
            baseY = riserY + 4f,
            scale = drumScale,
            yawDeg = -18f + sin(timeSec * 2.1f) * 6f,
            pitchDeg = 4f,
            animTimeSec = timeSec,
            isStarPowerActive = isStarPowerActive,
            combo = 15
        )
    }

    // 2. Baixista / Guitarrista de apoio do lado direito do palco
    val sideStats = stats.copy(
        selectedCharacterId = "axel_rocker",
        customRocker = CustomRockerAppearance(
            hairStyle = "Glam Rock Desfiado",
            hairColorHex = 0xFFFF2A55L,
            topClothing = "Jaqueta de Couro c/ Rebites",
            accessories = "Óculos Escuros Aviador"
        ),
        studioProfile = stats.studioProfile.copy(
            fullOutfitSkinId = "outfit_skin_none",
            topColorHex = 0xFF1A1822L,
            pantsColorHex = 0xFF1C2738L
        )
    )
    val companionInst = if (primaryInstrument == Instrument.BASS) Instrument.GUITAR else Instrument.BASS
    val companionScale = (height * 0.25f).coerceIn(72f, 165f)
    drawRockerCharacter3D(
        drawScope = drawScope,
        stats = sideStats,
        instrument = companionInst,
        centerX = width * 0.80f,
        baseY = height * 0.78f,
        scale = companionScale,
        yawDeg = -24f + sin(timeSec * 1.6f) * 8f,
        pitchDeg = 4f,
        animTimeSec = timeSec + 0.7f,
        isStarPowerActive = isStarPowerActive,
        combo = 12
    )
}

private fun drawBackstageMirrorStudioBackdrop(
    drawScope: DrawScope,
    width: Float,
    height: Float,
    accent: Color
) {
    drawScope.drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF1D1128), Color(0xFF2B173B), Color(0xFF120B1A))
        ),
        size = Size(width, height)
    )
    val rowH = (height / 8f).coerceAtLeast(18f)
    for (r in 0..8) {
        drawScope.drawLine(Color.Black.copy(alpha = 0.26f), Offset(0f, r * rowH), Offset(width, r * rowH), strokeWidth = 1.4f)
    }
    drawScope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.26f), Color.Transparent),
            center = Offset(width * 0.5f, height * 0.48f),
            radius = minOf(width, height) * 0.45f
        ),
        radius = minOf(width, height) * 0.45f,
        center = Offset(width * 0.5f, height * 0.48f)
    )
}

@Composable
fun InteractiveCharacter3DViewer(
    stats: PlayerStats,
    instrument: Instrument,
    modifier: Modifier = Modifier
) {
    var yawDeg by remember { mutableFloatStateOf(16f) }
    var pitchDeg by remember { mutableFloatStateOf(5f) }
    var zoom by remember { mutableFloatStateOf(1.0f) }

    val effectiveInstrument = if (stats.selectedCharacterId == "luan_santana") {
        Instrument.VOCALS
    } else {
        instrument
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF120B1A), RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        yawDeg = (yawDeg + dragAmount.x * 0.6f) % 360f
                        pitchDeg = (pitchDeg - dragAmount.y * 0.22f).coerceIn(-18f, 24f)
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(0.75f, 1.55f)
                    }
                }
                .testTag("character_3d_viewer")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawBackstageMirrorStudioBackdrop(
                    drawScope = this,
                    width = size.width,
                    height = size.height,
                    accent = Color(stats.studioProfile.accessoryColorHex)
                )
                val charScale = minOf(size.width * 0.72f, size.height * 0.44f) * zoom
                drawRockerCharacter3D(
                    drawScope = this,
                    stats = stats,
                    instrument = effectiveInstrument,
                    centerX = size.width * 0.5f,
                    baseY = size.height * 0.90f,
                    scale = charScale,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    animTimeSec = 0f
                )
            }

            Surface(
                color = Color(0x99000000),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            ) {
                Text(
                    text = "360° • ${(((yawDeg % 360f) + 360f) % 360f).toInt()}°",
                    color = RockAccentCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("Frente" to 16f, "Lado" to 90f, "Costas" to 180f).forEach { (label, angle) ->
                TextButton(
                    onClick = { yawDeg = angle; pitchDeg = 5f },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(label, color = RockAccentGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Slider(
                value = zoom,
                onValueChange = { zoom = it },
                valueRange = 0.75f..1.55f,
                modifier = Modifier.weight(1f).height(24.dp).testTag("character_3d_zoom_slider"),
                colors = SliderDefaults.colors(thumbColor = RockAccentCyan, activeTrackColor = RockAccentCyan)
            )
        }
    }
}
