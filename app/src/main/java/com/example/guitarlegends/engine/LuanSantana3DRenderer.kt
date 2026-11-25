package com.example.guitarlegends.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import com.example.guitarlegends.model.CharacterArtStyle
import com.example.guitarlegends.model.Instrument
import com.example.guitarlegends.model.InstrumentCustomConfig
import com.example.guitarlegends.model.StagePoseExpression
import com.example.guitarlegends.model.StudioProfileConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Estrutura de animação corporal e facial do modelo 3D do Luan Santana,
 * garantindo coerência de palco com respiração, balanço rítmico, piscar de olhos,
 * articulação labial no vocal e reação ao combo / Star Power.
 */
internal data class LuanAnimationPose(
    val grooveBounce: Float,
    val torsoSwayX: Float,
    val headNod: Float,
    val headTiltX: Float,
    val vocalBreath: Float,
    val mouthOpenAmount: Float,
    val browLift: Float,
    val eyeOpenScale: Float,
    val strumSwing: Float,
    val micHandBob: Float
)

internal fun computeLuanAnimationPose(
    animTimeSec: Float,
    isStarPowerActive: Boolean,
    combo: Int,
    instrument: Instrument,
    stageExpression: StagePoseExpression
): LuanAnimationPose {
    val active = animTimeSec > 0f
    val energyMult = if (isStarPowerActive) 1.38f else if (combo >= 15) 1.18f else 1.0f

    val grooveBounce = if (active) sin(animTimeSec * 4.4f) * 0.017f * energyMult else 0f
    val torsoSwayX = if (active) sin(animTimeSec * 2.2f) * 0.014f * energyMult else 0f
    val headNod = if (active) {
        val baseNod = sin(animTimeSec * 4.4f + 0.35f) * 0.013f * energyMult
        if (stageExpression == StagePoseExpression.HEADBANG_FURY) baseNod * 1.45f else baseNod
    } else 0f
    val headTiltX = 0.008f + if (active) sin(animTimeSec * 2.2f + 0.5f) * 0.009f else 0f

    val vocalBreath = if (active) (sin(animTimeSec * 5.2f) * 0.5f + 0.5f) else 0.35f
    val singingWave = if (active && instrument == Instrument.VOCALS) {
        (sin(animTimeSec * 7.8f) * 0.5f + 0.5f) * 0.65f + (sin(animTimeSec * 3.1f) * 0.5f + 0.5f) * 0.35f
    } else 0.45f

    val mouthBase = when (stageExpression) {
        StagePoseExpression.CONCENTRATED -> 0.32f
        StagePoseExpression.SMIRK_LEGEND -> 0.68f
        StagePoseExpression.SHOUTING_ROCK -> 0.92f
        StagePoseExpression.HEADBANG_FURY -> 0.80f
    }
    val mouthOpenAmount = if (active && instrument == Instrument.VOCALS) {
        (mouthBase * 0.55f + singingWave * 0.55f + if (isStarPowerActive) 0.16f else 0f).coerceIn(0.25f, 1.15f)
    } else {
        mouthBase * 0.85f
    }

    val browLift = if (active && instrument == Instrument.VOCALS) {
        (singingWave * 0.0045f + if (isStarPowerActive) 0.003f else 0f)
    } else 0f

    // Piscar natural a cada ~3.8s durante apresentações animadas
    val eyeOpenScale = if (active) {
        val blinkPhase = animTimeSec % 3.8f
        when {
            blinkPhase < 0.07f -> (1f - blinkPhase / 0.07f).coerceIn(0.12f, 1f)
            blinkPhase < 0.14f -> ((blinkPhase - 0.07f) / 0.07f).coerceIn(0.12f, 1f)
            else -> 1f
        }
    } else 1f

    val strumSwing = if (active) sin(animTimeSec * 11.2f) * 0.046f * energyMult else 0f
    val micHandBob = if (active) grooveBounce * 0.25f else 0f

    return LuanAnimationPose(
        grooveBounce = grooveBounce,
        torsoSwayX = torsoSwayX,
        headNod = headNod,
        headTiltX = headTiltX,
        vocalBreath = vocalBreath,
        mouthOpenAmount = mouthOpenAmount,
        browLift = browLift,
        eyeOpenScale = eyeOpenScale,
        strumSwing = strumSwing,
        micHandBob = micHandBob
    )
}

/**
 * Renderizador 3D ultra-realista do vocalista oficial Luan Santana,
 * modelado com fidelidade máxima à fotografia oficial de referência:
 * - Anatomia humana proporcional (1:7.4), volume 3D multi-camada e sombreamento facial realista
 * - Cabelo castanho-escuro com laterais aparadas e topete volumoso em mechas caramelo texturizadas
 * - Rosto expressivo com cavidade orbital, olhos castanhos com duplo brilho e piscar natural,
 *   nariz esculpido, barba rala/bigode pontilhados, sorriso aberto com arcada dentária e ponto eletrônico + brinco
 * - Camisa social preta de mangas curtas dobradas com gola em V, botões, dobras de tecido e colar prata com pingente
 * - Alça de couro marrom conhaque com costura dupla + violão dreadnought grafite marmorizado nas costas
 * - Tatuagem cursiva dupla no antebraço direito, relógio prata com mostrador branco + pulseira ônix + corrente prata
 * - Calça preta de alfaiataria com cinto preto, fivela prata e bordado metálico de coroa e rosa na coxa esquerda
 * - Pedestal reto preto com trava telescópica central + microfone preto segurado pelas duas mãos articuladas
 */
internal fun drawLuanSantana3D(
    drawScope: DrawScope,
    spec: CharacterVisualSpec,
    instrument: Instrument,
    centerX: Float,
    baseY: Float,
    scale: Float,
    yawDeg: Float,
    pitchDeg: Float,
    animTimeSec: Float,
    isStarPowerActive: Boolean,
    combo: Int,
    studioProfile: StudioProfileConfig = StudioProfileConfig()
) {
    val yawNorm = ((yawDeg % 360f) + 360f) % 360f
    val facingFront = yawNorm <= 95f || yawNorm >= 265f
    val yawRad = Math.toRadians(yawNorm.toDouble()).toFloat()

    val pose = computeLuanAnimationPose(
        animTimeSec = animTimeSec,
        isStarPowerActive = isStarPowerActive,
        combo = combo,
        instrument = instrument,
        stageExpression = studioProfile.stageExpression
    )

    // Proporções anatômicas realistas
    val hipY = -0.88f + pose.grooveBounce * 0.4f
    val waistY = -1.03f + pose.grooveBounce * 0.6f
    val chestY = -1.24f + pose.grooveBounce * 0.85f
    val shoulderY = -1.42f + pose.grooveBounce
    val neckTopY = -1.56f + pose.grooveBounce + pose.headNod * 0.5f
    val headY = -1.695f + pose.grooveBounce + pose.headNod
    val swayX = pose.torsoSwayX

    fun pt(x: Float, y: Float, z: Float): Offset =
        projectVec3(Vec3(x, y, z), yawDeg, pitchDeg, centerX, baseY, scale).first

    fun zOf(x: Float, y: Float, z: Float): Float =
        projectVec3(Vec3(x, y, z), yawDeg, pitchDeg, centerX, baseY, scale).second

    // Paleta ultra-realista extraída diretamente da fotografia oficial
    val skinSpec = Color(0xFFF3CCB2)
    val skinLight = Color(0xFFEBBFA2)
    val skinBase = Color(0xFFD9A383)
    val skinWarm = Color(0xFFC68768)
    val skinShadow = Color(0xFF9B6046)
    val skinDeepAO = Color(0xFF7B4834)

    val shirtHighlight = Color(0xFF2B2E38)
    val shirtLight = Color(0xFF21232B)
    val shirtBase = Color(0xFF14151A)
    val shirtShadow = Color(0xFF0A0A0D)

    val pantsHighlight = Color(0xFF262831)
    val pantsLight = Color(0xFF1C1E25)
    val pantsBase = Color(0xFF111217)
    val pantsShadow = Color(0xFF08080B)

    val hairDeep = Color(0xFF160E0B)
    val hairDark = Color(0xFF231610)
    val hairMid = Color(0xFF4B2E1F)
    val hairCaramel = Color(0xFF8F5C3A)
    val hairHighlight = Color(0xFFB77F55)

    val styleOutlineAlpha = when (spec.artStyle) {
        CharacterArtStyle.CARTOON -> 0.80f
        CharacterArtStyle.ANIME -> 0.48f
        CharacterArtStyle.STYLIZED_REALISTIC -> 0.16f
    }
    val styleOutlineW = when (spec.artStyle) {
        CharacterArtStyle.CARTOON -> 2.3f
        CharacterArtStyle.ANIME -> 1.5f
        CharacterArtStyle.STYLIZED_REALISTIC -> 0.9f
    }

    fun drawTaperedSegment(
        p0: Offset,
        p1: Offset,
        r0: Float,
        r1: Float,
        light: Color,
        base: Color,
        shadow: Color,
        capStart: Boolean = true,
        capEnd: Boolean = true
    ) {
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len
        val ny = dx / len

        val a0 = Offset(p0.x + nx * r0, p0.y + ny * r0)
        val b0 = Offset(p0.x - nx * r0, p0.y - ny * r0)
        val a1 = Offset(p1.x + nx * r1, p1.y + ny * r1)
        val b1 = Offset(p1.x - nx * r1, p1.y - ny * r1)

        val mid0 = Offset((a0.x + b0.x) * 0.5f, (a0.y + b0.y) * 0.5f)
        val segPath = Path().apply {
            moveTo(a0.x, a0.y)
            lineTo(a1.x, a1.y)
            if (capEnd) {
                quadraticTo(
                    p1.x + (dx / len) * r1 * 0.85f,
                    p1.y + (dy / len) * r1 * 0.85f,
                    b1.x,
                    b1.y
                )
            } else {
                lineTo(b1.x, b1.y)
            }
            lineTo(b0.x, b0.y)
            if (capStart) {
                quadraticTo(
                    p0.x - (dx / len) * r0 * 0.85f,
                    p0.y - (dy / len) * r0 * 0.85f,
                    a0.x,
                    a0.y
                )
            }
            close()
        }

        drawScope.drawPath(
            path = segPath,
            brush = Brush.linearGradient(
                colors = listOf(light, base, shadow),
                start = Offset(mid0.x + nx * r0, mid0.y + ny * r0),
                end = Offset(mid0.x - nx * r0, mid0.y - ny * r0)
            ),
            style = Fill
        )
        if (styleOutlineAlpha > 0.28f) {
            drawScope.drawPath(
                path = segPath,
                color = Color.Black.copy(alpha = styleOutlineAlpha * 0.6f),
                style = Stroke(width = styleOutlineW, join = StrokeJoin.Round)
            )
        }
    }

    // Sombra realista de contato no chão do palco
    val shadowCenter = pt(0f, 0.01f, 0f)
    drawScope.drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                if (isStarPowerActive) Color(0x6E00E5FF) else Color(0x6E000000),
                Color.Transparent
            ),
            center = shadowCenter,
            radius = scale * 0.46f
        ),
        topLeft = Offset(shadowCenter.x - scale * 0.46f, shadowCenter.y - scale * 0.088f),
        size = Size(scale * 0.92f, scale * 0.176f)
    )

    // 0. VIOLÃO DREADNOUGHT GRAFITE MARMORIZADO NAS COSTAS (FIEL À FOTO)
    fun drawAcousticGuitarOnBack() {
        val bodyBase = pt(0.245f + swayX * 0.4f, hipY - 0.05f, 0.172f)
        val waistPt = pt(0.215f + swayX * 0.5f, waistY - 0.01f, 0.172f)
        val upperBout = pt(0.185f + swayX * 0.6f, chestY + 0.05f, 0.172f)
        val neckJoin = pt(0.08f + swayX * 0.7f, chestY - 0.06f, 0.160f)
        val neckTip = pt(-0.18f + swayX * 0.6f, shoulderY + 0.05f, 0.155f)

        // Braço e headstock atrás das costas (visíveis nas rotações 360° sem invadir o rosto de frente)
        drawTaperedSegment(
            p0 = neckJoin,
            p1 = neckTip,
            r0 = scale * 0.025f,
            r1 = scale * 0.019f,
            light = Color(0xFF47362D),
            base = Color(0xFF281D18),
            shadow = Color(0xFF16100D),
            capStart = false,
            capEnd = true
        )
        val headAngle = atan2((neckTip.y - neckJoin.y).toDouble(), (neckTip.x - neckJoin.x).toDouble()).toFloat()
        val hx = cos(headAngle)
        val hy = sin(headAngle)
        val hnx = -hy
        val hyN = hx
        val headP1 = Offset(neckTip.x + hnx * scale * 0.024f, neckTip.y + hyN * scale * 0.024f)
        val headP2 = Offset(neckTip.x + hx * scale * 0.085f + hnx * scale * 0.028f, neckTip.y + hy * scale * 0.085f + hyN * scale * 0.028f)
        val headP3 = Offset(neckTip.x + hx * scale * 0.085f - hnx * scale * 0.028f, neckTip.y + hy * scale * 0.085f - hyN * scale * 0.028f)
        val headP4 = Offset(neckTip.x - hnx * scale * 0.024f, neckTip.y - hyN * scale * 0.024f)
        val headPath = Path().apply {
            moveTo(headP1.x, headP1.y); lineTo(headP2.x, headP2.y); lineTo(headP3.x, headP3.y); lineTo(headP4.x, headP4.y); close()
        }
        drawScope.drawPath(headPath, color = Color(0xFF1E1714))
        // Tarraxas cromadas discretas no headstock
        if (!facingFront) {
            for (t in 0..2) {
                val tf = 0.25f + t * 0.25f
                drawScope.drawCircle(
                    color = Color(0xFFC8CFD8),
                    radius = (scale * 0.0055f).coerceAtLeast(1.0f),
                    center = Offset(
                        neckTip.x + hx * scale * 0.085f * tf + hnx * scale * 0.032f,
                        neckTip.y + hy * scale * 0.085f * tf + hyN * scale * 0.032f
                    )
                )
                drawScope.drawCircle(
                    color = Color(0xFFC8CFD8),
                    radius = (scale * 0.0055f).coerceAtLeast(1.0f),
                    center = Offset(
                        neckTip.x + hx * scale * 0.085f * tf - hnx * scale * 0.032f,
                        neckTip.y + hy * scale * 0.085f * tf - hyN * scale * 0.032f
                    )
                )
            }
        }

        val axisDx = bodyBase.x - upperBout.x
        val axisDy = bodyBase.y - upperBout.y
        val axisLen = hypot(axisDx, axisDy).coerceAtLeast(1f)
        val ux = axisDx / axisLen
        val uy = axisDy / axisLen
        val px = -uy
        val py = ux

        val lowerW = scale * 0.215f
        val waistW = scale * 0.135f
        val upperW = scale * 0.165f
        val sideDepth = scale * 0.042f

        fun buildDreadnoughtPath(shiftX: Float, shiftY: Float): Path {
            val topC = Offset(upperBout.x - ux * scale * 0.11f + shiftX, upperBout.y - uy * scale * 0.11f + shiftY)
            val botC = Offset(bodyBase.x + ux * scale * 0.15f + shiftX, bodyBase.y + uy * scale * 0.15f + shiftY)
            val uL = Offset(upperBout.x - px * upperW + shiftX, upperBout.y - py * upperW + shiftY)
            val uR = Offset(upperBout.x + px * upperW + shiftX, upperBout.y + py * upperW + shiftY)
            val wL = Offset(waistPt.x - px * waistW + shiftX, waistPt.y - py * waistW + shiftY)
            val wR = Offset(waistPt.x + px * waistW + shiftX, waistPt.y + py * waistW + shiftY)
            val lL = Offset(bodyBase.x - px * lowerW + shiftX, bodyBase.y - py * lowerW + shiftY)
            val lR = Offset(bodyBase.x + px * lowerW + shiftX, bodyBase.y + py * lowerW + shiftY)

            return Path().apply {
                moveTo(topC.x, topC.y)
                cubicTo(
                    topC.x + px * upperW * 0.85f, topC.y + py * upperW * 0.85f,
                    uR.x - ux * scale * 0.05f, uR.y - uy * scale * 0.05f,
                    uR.x, uR.y
                )
                cubicTo(
                    uR.x + ux * scale * 0.07f, uR.y + uy * scale * 0.07f,
                    wR.x - ux * scale * 0.04f, wR.y - uy * scale * 0.04f,
                    wR.x, wR.y
                )
                cubicTo(
                    wR.x + ux * scale * 0.05f, wR.y + uy * scale * 0.05f,
                    lR.x - ux * scale * 0.07f, lR.y - uy * scale * 0.07f,
                    lR.x, lR.y
                )
                cubicTo(
                    lR.x + ux * scale * 0.12f, lR.y + uy * scale * 0.12f,
                    botC.x + px * lowerW * 0.72f, botC.y + py * lowerW * 0.72f,
                    botC.x, botC.y
                )
                cubicTo(
                    botC.x - px * lowerW * 0.72f, botC.y - py * lowerW * 0.72f,
                    lL.x + ux * scale * 0.12f, lL.y + uy * scale * 0.12f,
                    lL.x, lL.y
                )
                cubicTo(
                    lL.x - ux * scale * 0.07f, lL.y - uy * scale * 0.07f,
                    wL.x + ux * scale * 0.05f, wL.y + uy * scale * 0.05f,
                    wL.x, wL.y
                )
                cubicTo(
                    wL.x - ux * scale * 0.04f, wL.y - uy * scale * 0.04f,
                    uL.x + ux * scale * 0.07f, uL.y + uy * scale * 0.07f,
                    uL.x, uL.y
                )
                cubicTo(
                    uL.x - ux * scale * 0.05f, uL.y - uy * scale * 0.05f,
                    topC.x - px * upperW * 0.85f, topC.y - py * upperW * 0.85f,
                    topC.x, topC.y
                )
                close()
            }
        }

        val depthDir = if (facingFront) -1f else 1f
        val sideRimPath = buildDreadnoughtPath(px * sideDepth * depthDir * 0.45f, -sideDepth * 0.25f)
        val mainBodyPath = buildDreadnoughtPath(0f, 0f)

        // Lateral cinza-aço característica do violão da foto
        drawScope.drawPath(
            path = sideRimPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF838892), Color(0xFF51555E), Color(0xFF282A30)),
                start = Offset(upperBout.x - lowerW, upperBout.y),
                end = Offset(bodyBase.x + lowerW, bodyBase.y)
            )
        )
        // Fundo/tampo grafite escuro
        drawScope.drawPath(
            path = mainBodyPath,
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3E4148), Color(0xFF25272D), Color(0xFF17181C)),
                center = bodyBase,
                radius = lowerW * 1.45f
            )
        )

        // Textura marmorizada recortada rigorosamente dentro da silhueta do violão
        drawScope.clipPath(mainBodyPath) {
            for (i in 0 until 42) {
                val angle = i * 2.39996f
                val dist = ((i % 9) + 1) / 9.5f * lowerW * 1.05f
                val fx = cos(angle) * dist
                val fy = sin(angle) * dist * 1.15f - scale * 0.05f
                val sc = Offset(bodyBase.x + fx, bodyBase.y + fy)
                val r = scale * (0.008f + (i % 4) * 0.0065f)
                val col = when (i % 3) {
                    0 -> Color(0xFF8C919C).copy(alpha = 0.20f)
                    1 -> Color(0xFF636873).copy(alpha = 0.26f)
                    else -> Color(0xFFB5BAC4).copy(alpha = 0.13f)
                }
                drawCircle(color = col, radius = r, center = sc)
            }
            // Riscos sutis de desgaste acústico
            for (k in 0 until 10) {
                val sx = bodyBase.x + ((k - 5) * 0.14f) * lowerW
                val sy = bodyBase.y + ((k % 3 - 1) * 0.28f) * lowerW
                drawLine(
                    color = Color(0xFF9EA3AE).copy(alpha = 0.18f),
                    start = Offset(sx, sy),
                    end = Offset(sx + px * scale * 0.045f, sy + py * scale * 0.045f),
                    strokeWidth = 0.9f
                )
            }
        }

        // Friso claro (binding) ao redor do corpo
        drawScope.drawPath(
            path = mainBodyPath,
            color = Color(0xFFB4B9C2).copy(alpha = 0.72f),
            style = Stroke(width = 1.7f)
        )

        if (!facingFront) {
            val soundHole = pt(0.205f + swayX * 0.5f, chestY + 0.13f, 0.188f)
            drawScope.drawCircle(color = Color(0xFF0C0D10), radius = scale * 0.068f, center = soundHole)
            drawScope.drawCircle(color = Color(0xFF9B8B77), radius = scale * 0.080f, center = soundHole, style = Stroke(width = 1.8f))
            drawScope.drawCircle(color = Color(0xFFD0D4DC), radius = scale * 0.087f, center = soundHole, style = Stroke(width = 1.0f))

            val bridgeCenter = pt(0.235f + swayX * 0.4f, hipY - 0.03f, 0.188f)
            drawScope.drawLine(
                color = Color(0xFF151211),
                start = Offset(bridgeCenter.x - px * scale * 0.064f, bridgeCenter.y - py * scale * 0.064f),
                end = Offset(bridgeCenter.x + px * scale * 0.064f, bridgeCenter.y + py * scale * 0.064f),
                strokeWidth = (scale * 0.028f).coerceAtLeast(2.4f),
                cap = StrokeCap.Round
            )
            // 6 cordas metálicas + pinos do cavalete
            for (s in -2..3) {
                val sOff = s * scale * 0.006f
                val bPt = Offset(bridgeCenter.x + px * sOff * 1.4f, bridgeCenter.y + py * sOff * 1.4f)
                drawScope.drawLine(
                    color = Color(0xFFD9DEE6).copy(alpha = 0.70f),
                    start = Offset(neckTip.x + hnx * sOff, neckTip.y + hyN * sOff),
                    end = bPt,
                    strokeWidth = 0.9f
                )
                drawScope.drawCircle(color = Color(0xFFEAECEF), radius = (scale * 0.003f).coerceAtLeast(0.8f), center = bPt)
            }

            // Alça de couro marrom conhaque atrás do tronco com costura dupla
            val backStrapTop = pt(0.145f + swayX * 0.7f, shoulderY + 0.01f, 0.118f)
            val backStrapMid = pt(0.01f + swayX * 0.5f, waistY + 0.02f, 0.122f)
            val backStrapBot = pt(-0.155f + swayX * 0.4f, hipY - 0.01f, 0.120f)
            val backStrapPath = Path().apply {
                moveTo(backStrapTop.x, backStrapTop.y)
                quadraticTo(backStrapMid.x, backStrapMid.y, backStrapBot.x, backStrapBot.y)
            }
            drawScope.drawPath(
                path = backStrapPath,
                color = Color(0xFF542913),
                style = Stroke(width = (scale * 0.064f).coerceAtLeast(4.2f), cap = StrokeCap.Round)
            )
            drawScope.drawPath(
                path = backStrapPath,
                color = Color(0xFF91502B),
                style = Stroke(width = (scale * 0.052f).coerceAtLeast(3.4f), cap = StrokeCap.Round)
            )
            drawScope.drawPath(
                path = backStrapPath,
                color = Color(0xFFE1B894).copy(alpha = 0.55f),
                style = Stroke(
                    width = 1.1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                )
            )
        }
    }

    if (spec.hasSlungAcousticGuitar && facingFront) {
        // Ponta da alça de couro pendendo atrás entre as pernas como na fotografia
        val strapTailTop = pt(0.02f + swayX * 0.3f, hipY + 0.08f, 0.06f)
        val strapTailBot = pt(-0.02f, -0.45f, 0.08f)
        drawTaperedSegment(
            p0 = strapTailTop,
            p1 = strapTailBot,
            r0 = scale * 0.024f,
            r1 = scale * 0.020f,
            light = Color(0xFF9C5931),
            base = Color(0xFF7B4222),
            shadow = Color(0xFF4D2612)
        )
        drawAcousticGuitarOnBack()
    }

    // 1. PERNAS, CALÇA PRETA DE ALFAIATARIA & BORDADO METÁLICO COROA + ROSA
    fun drawLeg(side: Float) {
        val hip = pt(side * 0.105f + swayX * 0.35f, hipY + 0.04f, 0f)
        val knee = pt(side * 0.118f + swayX * 0.15f, -0.45f, -0.02f)
        val ankle = pt(side * 0.132f, -0.07f, 0.02f)

        drawTaperedSegment(hip, knee, scale * 0.088f, scale * 0.064f, pantsLight, pantsBase, pantsShadow)
        drawTaperedSegment(knee, ankle, scale * 0.062f, scale * 0.045f, pantsLight, pantsBase, pantsShadow)

        if (facingFront) {
            // Vinco de alfaiataria + costura de bolso lateral fiel à calça preta
            drawScope.drawLine(
                color = pantsHighlight.copy(alpha = 0.55f),
                start = pt(side * 0.112f + swayX * 0.35f, hipY + 0.06f, -0.074f),
                end = pt(side * 0.125f, -0.14f, -0.048f),
                strokeWidth = 1.2f
            )
            val pocketStart = pt(side * 0.168f + swayX * 0.35f, hipY + 0.032f, -0.062f)
            val pocketEnd = pt(side * 0.132f + swayX * 0.35f, hipY + 0.132f, -0.080f)
            drawScope.drawLine(
                color = Color(0xFF2D303B).copy(alpha = 0.65f),
                start = pocketStart,
                end = pocketEnd,
                strokeWidth = 1.3f,
                cap = StrokeCap.Round
            )
        }

        // Bordado metálico de coroa e rosa prateada na coxa esquerda (side > 0f)
        if (spec.hasCrownRoseEmbroidery && side > 0f && facingFront) {
            val crownPt = pt(0.134f + swayX * 0.35f, hipY + 0.132f, -0.083f)
            val rosePt = pt(0.138f + swayX * 0.35f, hipY + 0.222f, -0.083f)
            val stemMid = pt(0.134f + swayX * 0.32f, hipY + 0.295f, -0.083f)
            val stemBot = pt(0.156f + swayX * 0.28f, hipY + 0.362f, -0.080f)
            val embSpec = Color(0xFFF0F2F7)
            val embLight = Color(0xFFD6DBE4)
            val embMid = Color(0xFF9EA4B0)
            val embDark = Color(0xFF4B505A)

            // Coroa real bordada acima da rosa
            val cw = scale * 0.052f
            val ch = scale * 0.029f
            val crownPath = Path().apply {
                moveTo(crownPt.x - cw * 0.46f, crownPt.y + ch * 0.44f)
                lineTo(crownPt.x - cw * 0.54f, crownPt.y - ch * 0.22f)
                lineTo(crownPt.x - cw * 0.22f, crownPt.y + ch * 0.04f)
                lineTo(crownPt.x, crownPt.y - ch * 0.48f)
                lineTo(crownPt.x + cw * 0.22f, crownPt.y + ch * 0.04f)
                lineTo(crownPt.x + cw * 0.54f, crownPt.y - ch * 0.22f)
                lineTo(crownPt.x + cw * 0.46f, crownPt.y + ch * 0.44f)
                close()
            }
            drawScope.drawPath(crownPath, color = embMid.copy(alpha = 0.88f), style = Fill)
            drawScope.drawPath(crownPath, color = embLight, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
            // Pérolas nas pontas da coroa + faixa da base
            listOf(-0.54f to -0.22f, 0f to -0.48f, 0.54f to -0.22f).forEach { (pxF, pyF) ->
                drawScope.drawCircle(
                    color = embSpec,
                    radius = (scale * 0.0042f).coerceAtLeast(0.8f),
                    center = Offset(crownPt.x + cw * pxF, crownPt.y + ch * pyF)
                )
            }
            drawScope.drawLine(
                color = embDark,
                start = Offset(crownPt.x - cw * 0.44f, crownPt.y + ch * 0.34f),
                end = Offset(crownPt.x + cw * 0.44f, crownPt.y + ch * 0.34f),
                strokeWidth = 1.1f
            )

            // Rosa bordada prateada com pétalas em espiral e sombreado de linha metálica
            val roseR = scale * 0.046f
            drawScope.drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(embSpec, embMid, embDark),
                    center = Offset(rosePt.x - roseR * 0.2f, rosePt.y - roseR * 0.2f),
                    radius = roseR * 1.1f
                ),
                radius = roseR,
                center = rosePt
            )
            // Pétalas concêntricas texturizadas
            for (ring in listOf(0.88f, 0.64f, 0.40f)) {
                drawScope.drawCircle(
                    color = if (ring > 0.6f) embDark else embLight,
                    radius = roseR * ring,
                    center = Offset(rosePt.x + roseR * (0.05f * (1f - ring)), rosePt.y),
                    style = Stroke(width = 1.2f)
                )
            }
            drawScope.drawCircle(color = embDark, radius = roseR * 0.18f, center = rosePt)

            // Caule e 3 folhas serrilhadas inclinadas para a coxa externa
            val stemPath = Path().apply {
                moveTo(rosePt.x, rosePt.y + roseR * 0.92f)
                quadraticTo(stemMid.x, stemMid.y, stemBot.x, stemBot.y)
            }
            drawScope.drawPath(stemPath, color = embLight, style = Stroke(width = 1.8f, cap = StrokeCap.Round))

            val leafConfigs = listOf(
                Triple(stemMid, Offset(-0.058f, 0.008f), Offset(-0.025f, -0.022f)),
                Triple(Offset(stemMid.x + scale * 0.006f, stemMid.y + scale * 0.016f), Offset(0.054f, 0.024f), Offset(0.032f, -0.008f)),
                Triple(Offset(stemMid.x + scale * 0.012f, stemMid.y + scale * 0.040f), Offset(-0.040f, 0.028f), Offset(-0.016f, 0.002f))
            )
            leafConfigs.forEach { (origin, tipD, ctrlD) ->
                val tip = Offset(origin.x + scale * tipD.x, origin.y + scale * tipD.y)
                val ctrlTop = Offset(origin.x + scale * ctrlD.x, origin.y + scale * ctrlD.y)
                val ctrlBot = Offset(origin.x + scale * tipD.x * 0.48f, origin.y + scale * (tipD.y + 0.018f))
                val lp = Path().apply {
                    moveTo(origin.x, origin.y)
                    quadraticTo(ctrlTop.x, ctrlTop.y, tip.x, tip.y)
                    quadraticTo(ctrlBot.x, ctrlBot.y, origin.x, origin.y)
                    close()
                }
                drawScope.drawPath(lp, color = embMid.copy(alpha = 0.92f))
                drawScope.drawPath(lp, color = embLight, style = Stroke(width = 0.95f))
                drawScope.drawLine(color = embDark, start = origin, end = tip, strokeWidth = 0.8f)
            }
        }

        // Sapato / Bota social preta
        val heel = pt(side * 0.132f, -0.02f, 0.04f)
        val toe = pt(side * 0.145f, -0.015f, -0.145f)
        drawTaperedSegment(heel, toe, scale * 0.046f, scale * 0.036f, Color(0xFF282B34), Color(0xFF111216), Color(0xFF060608))
    }

    if (zOf(-0.14f, hipY, 0f) > zOf(0.14f, hipY, 0f)) {
        drawLeg(-1f); drawLeg(1f)
    } else {
        drawLeg(1f); drawLeg(-1f)
    }

    // Quadril / pélvis conectando perfeitamente as duas pernas
    val pelvisL = pt(-0.175f + swayX * 0.4f, hipY, -0.075f)
    val pelvisR = pt(0.175f + swayX * 0.4f, hipY, -0.075f)
    val pelvisBotR = pt(0.155f + swayX * 0.35f, hipY + 0.13f, -0.07f)
    val pelvisCrotch = pt(swayX * 0.35f, hipY + 0.155f, -0.06f)
    val pelvisBotL = pt(-0.155f + swayX * 0.35f, hipY + 0.13f, -0.07f)
    val pelvisPath = Path().apply {
        moveTo(pelvisL.x, pelvisL.y); lineTo(pelvisR.x, pelvisR.y); lineTo(pelvisBotR.x, pelvisBotR.y)
        lineTo(pelvisCrotch.x, pelvisCrotch.y); lineTo(pelvisBotL.x, pelvisBotL.y); close()
    }
    drawScope.drawPath(
        path = pelvisPath,
        brush = Brush.linearGradient(colors = listOf(pantsLight, pantsBase, pantsShadow), start = pelvisL, end = pelvisR)
    )
    if (facingFront) {
        drawScope.drawLine(
            color = Color(0xFF252832).copy(alpha = 0.6f),
            start = pt(swayX * 0.38f, hipY + 0.03f, -0.090f),
            end = pt(swayX * 0.35f, hipY + 0.14f, -0.078f),
            strokeWidth = 1.2f
        )
    }

    // 1B. BRAÇOS ANATÔMICOS, MÃOS ESCULPIDAS, TATUAGEM DUPLA, RELÓGIO, PULSEIRAS & MICROFONE 3D
    fun drawLuanArm(side: Float, elbowVec: Vec3, handVec: Vec3, drawWristAccessories: Boolean) {
        val sh = pt(side * 0.232f + swayX * 0.9f, shoulderY + 0.045f, -0.01f)
        val el = pt(elbowVec.x, elbowVec.y, elbowVec.z)
        val wr = pt(handVec.x, handVec.y, handVec.z)

        // Manga curta preta com dobra dupla e aba abotoada
        val cuffPos = Offset(sh.x * 0.38f + el.x * 0.62f, sh.y * 0.38f + el.y * 0.62f)
        drawTaperedSegment(sh, cuffPos, scale * 0.069f, scale * 0.063f, shirtLight, shirtBase, shirtShadow)
        val cuffEnd = Offset(sh.x * 0.30f + el.x * 0.70f, sh.y * 0.30f + el.y * 0.70f)
        drawTaperedSegment(cuffPos, cuffEnd, scale * 0.066f, scale * 0.065f, shirtHighlight, Color(0xFF1A1C22), shirtShadow)
        val tabMid = Offset(sh.x * 0.55f + cuffEnd.x * 0.45f, sh.y * 0.55f + cuffEnd.y * 0.45f)
        drawScope.drawLine(
            color = Color(0xFF2E323D),
            start = tabMid,
            end = cuffEnd,
            strokeWidth = (scale * 0.016f).coerceAtLeast(1.6f),
            cap = StrokeCap.Round
        )
        drawScope.drawCircle(
            color = Color(0xFF474C5A),
            radius = (scale * 0.0068f).coerceAtLeast(1.1f),
            center = Offset(cuffEnd.x, (tabMid.y + cuffEnd.y) * 0.5f)
        )

        // Braço e antebraço com volume muscular natural e sombreamento cilíndrico
        drawTaperedSegment(cuffEnd, el, scale * 0.055f, scale * 0.050f, skinLight, skinBase, skinShadow)
        drawTaperedSegment(el, wr, scale * 0.050f, scale * 0.036f, skinLight, skinBase, skinShadow)

        // Tatuagem cursiva dupla na borda externa do antebraço direito (side < 0f)
        if (side < 0f && facingFront) {
            val dx = wr.x - el.x
            val dy = wr.y - el.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / len
            val ny = dx / len
            for (colIdx in 0..1) {
                val latOff = if (colIdx == 0) scale * 0.016f else scale * 0.025f
                val tatPath = Path()
                for (step in 0..9) {
                    val f = 0.18f + step * 0.048f
                    val wave = sin(step * 2.0f + colIdx * 1.1f) * scale * 0.0065f
                    val tx = el.x + dx * f - nx * (latOff - wave)
                    val ty = el.y + dy * f - ny * (latOff - wave)
                    if (step == 0) tatPath.moveTo(tx, ty) else tatPath.lineTo(tx, ty)
                }
                drawScope.drawPath(
                    path = tatPath,
                    color = Color(0xFF1F242D).copy(alpha = 0.76f),
                    style = Stroke(
                        width = (scale * 0.0078f).coerceAtLeast(1.15f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Relógio prata (mostrador branco), pulseira de contas escuras & corrente prata no pulso direito
        if (drawWristAccessories && side < 0f) {
            val dx = wr.x - el.x
            val dy = wr.y - el.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / len
            val ny = dx / len

            val bracPos = Offset(el.x * 0.29f + wr.x * 0.71f, el.y * 0.29f + wr.y * 0.71f)
            val chainPos = Offset(el.x * 0.37f + wr.x * 0.63f, el.y * 0.37f + wr.y * 0.63f)
            val watchPos = Offset(el.x * 0.17f + wr.x * 0.83f, el.y * 0.17f + wr.y * 0.83f)

            // Corrente prata entrelaçada
            drawScope.drawLine(
                color = Color(0xFFD5DCE6),
                start = Offset(chainPos.x - nx * scale * 0.040f, chainPos.y - ny * scale * 0.040f),
                end = Offset(chainPos.x + nx * scale * 0.040f, chainPos.y + ny * scale * 0.040f),
                strokeWidth = (scale * 0.013f).coerceAtLeast(1.6f),
                cap = StrokeCap.Round
            )
            // Pulseira de contas ônix escuras com brilho individual
            for (b in -2..2) {
                val bf = b * 0.014f * scale
                val beadC = Offset(bracPos.x + nx * bf, bracPos.y + ny * bf)
                drawScope.drawCircle(color = Color(0xFF1F2128), radius = (scale * 0.0092f).coerceAtLeast(1.4f), center = beadC)
                drawScope.drawCircle(
                    color = Color(0xFF6B707D),
                    radius = (scale * 0.003f).coerceAtLeast(0.6f),
                    center = Offset(beadC.x - scale * 0.002f, beadC.y - scale * 0.002f)
                )
            }
            // Pulseira Milanese prata do relógio + caixa prata + mostrador branco + ponteiros
            drawScope.drawLine(
                color = Color(0xFFB5BCC7),
                start = Offset(watchPos.x - nx * scale * 0.040f, watchPos.y - ny * scale * 0.040f),
                end = Offset(watchPos.x + nx * scale * 0.040f, watchPos.y + ny * scale * 0.040f),
                strokeWidth = (scale * 0.022f).coerceAtLeast(2.2f),
                cap = StrokeCap.Round
            )
            val dialCenter = Offset(watchPos.x + nx * scale * 0.009f, watchPos.y + ny * scale * 0.009f)
            val caseR = (scale * 0.032f).coerceAtLeast(3.2f)
            drawScope.drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFECEFF4), Color(0xFFAAB2BE), Color(0xFF707782)),
                    start = Offset(dialCenter.x - caseR, dialCenter.y - caseR),
                    end = Offset(dialCenter.x + caseR, dialCenter.y + caseR)
                ),
                radius = caseR,
                center = dialCenter
            )
            drawScope.drawCircle(color = Color(0xFFF9FAFC), radius = caseR * 0.78f, center = dialCenter)
            drawScope.drawLine(
                color = Color(0xFF2D3138),
                start = dialCenter,
                end = Offset(dialCenter.x + caseR * 0.42f, dialCenter.y - caseR * 0.34f),
                strokeWidth = 1.0f
            )
            drawScope.drawLine(
                color = Color(0xFF2D3138),
                start = dialCenter,
                end = Offset(dialCenter.x - caseR * 0.25f, dialCenter.y - caseR * 0.48f),
                strokeWidth = 1.0f
            )
        }
    }

    fun drawGrippingHand(wristCenter: Offset, curlDirX: Float, showRaisedThumb: Boolean = false) {
        val palmR = scale * 0.037f
        drawScope.drawOval(
            brush = Brush.radialGradient(
                colors = listOf(skinLight, skinBase, skinWarm),
                center = Offset(wristCenter.x - palmR * 0.2f, wristCenter.y - palmR * 0.2f),
                radius = palmR * 1.4f
            ),
            topLeft = Offset(wristCenter.x - palmR, wristCenter.y - palmR * 0.85f),
            size = Size(palmR * 2.0f, palmR * 1.7f)
        )
        if (showRaisedThumb) {
            val thumbBase = Offset(wristCenter.x + palmR * 0.62f, wristCenter.y - palmR * 0.15f)
            val thumbTip = Offset(wristCenter.x + palmR * 0.82f, wristCenter.y - palmR * 1.06f)
            drawTaperedSegment(thumbBase, thumbTip, palmR * 0.24f, palmR * 0.16f, skinLight, skinBase, skinWarm)
        }
        for (f in 0..3) {
            val fy = wristCenter.y - palmR * 0.55f + f * (palmR * 0.37f)
            val fStart = Offset(wristCenter.x - curlDirX * palmR * 0.18f, fy)
            val fEnd = Offset(wristCenter.x + curlDirX * palmR * 0.92f, fy + palmR * 0.06f)
            drawTaperedSegment(fStart, fEnd, palmR * 0.21f, palmR * 0.16f, skinLight, skinBase, skinShadow)
            // Sombra sutil entre os dedos fechados
            drawScope.drawCircle(
                color = skinSpec.copy(alpha = 0.45f),
                radius = palmR * 0.09f,
                center = Offset(wristCenter.x + curlDirX * palmR * 0.38f, fy)
            )
        }
    }

    fun drawLuanArmsAndGear() {
        when (instrument) {
            Instrument.GUITAR, Instrument.ACOUSTIC_GUITAR, Instrument.BASS -> {
                val cfg = when (instrument) {
                    Instrument.ACOUSTIC_GUITAR -> studioProfile.acousticConfig
                    Instrument.BASS -> studioProfile.bassConfig
                    else -> studioProfile.guitarConfig
                }
                drawLuanArm(-1f, Vec3(-0.34f, shoulderY + 0.26f, -0.09f), Vec3(-0.32f, shoulderY + 0.18f, -0.25f), true)
                val instCenter = pt(0.02f + swayX * 0.5f, hipY - 0.18f, -0.22f)
                drawInstrument3D(
                    drawScope = drawScope,
                    instrument = instrument,
                    config = cfg,
                    centerX = instCenter.x,
                    centerY = instCenter.y,
                    scale = scale * 0.50f,
                    yawDeg = yawDeg + 18f,
                    pitchDeg = pitchDeg - 12f,
                    pulsePhase = animTimeSec
                )
                drawLuanArm(1f, Vec3(0.32f, shoulderY + 0.26f, -0.09f), Vec3(0.14f, hipY - 0.10f + pose.strumSwing, -0.25f), false)
            }
            Instrument.DRUMS -> {
                drawLuanArm(-1f, Vec3(-0.32f, shoulderY + 0.26f, -0.09f), Vec3(-0.24f, hipY - 0.10f + pose.strumSwing, -0.23f), true)
                drawLuanArm(1f, Vec3(0.32f, shoulderY + 0.26f, -0.09f), Vec3(0.24f, hipY - 0.10f - pose.strumSwing, -0.23f), false)
                val drumPos = pt(0f, hipY + 0.10f, -0.34f)
                drawInstrument3D(
                    drawScope = drawScope,
                    instrument = Instrument.DRUMS,
                    config = studioProfile.drumsConfig,
                    centerX = drumPos.x,
                    centerY = drumPos.y,
                    scale = scale * 0.46f,
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    pulsePhase = animTimeSec
                )
            }
            Instrument.VOCALS -> {
                val standBase = pt(-0.285f, 0.0f, -0.24f)
                val standClutchBot = pt(-0.282f, -0.44f, -0.24f)
                val standClutchTop = pt(-0.280f, -0.68f, -0.24f)
                val standTop = pt(-0.278f, headY + 0.175f + pose.micHandBob, -0.24f)
                val micHead = pt(-0.425f, headY + 0.165f + pose.micHandBob, -0.258f)
                val micTail = pt(-0.185f, headY + 0.132f + pose.micHandBob, -0.228f)

                // Base circular pesada + haste metálica reta + trava telescópica central
                drawScope.drawOval(
                    color = Color(0xFF0D0E12),
                    topLeft = Offset(standBase.x - scale * 0.105f, standBase.y - scale * 0.026f),
                    size = Size(scale * 0.21f, scale * 0.052f)
                )
                drawScope.drawLine(
                    color = Color(0xFF14151A),
                    start = standBase,
                    end = standTop,
                    strokeWidth = (scale * 0.022f).coerceAtLeast(2.2f),
                    cap = StrokeCap.Round
                )
                drawScope.drawLine(
                    color = Color(0xFF474C59),
                    start = Offset(standBase.x - scale * 0.004f, standBase.y),
                    end = Offset(standTop.x - scale * 0.004f, standTop.y),
                    strokeWidth = 1.0f
                )
                drawScope.drawLine(
                    color = Color(0xFF101115),
                    start = standClutchBot,
                    end = standClutchTop,
                    strokeWidth = (scale * 0.038f).coerceAtLeast(3.4f),
                    cap = StrokeCap.Round
                )

                // Braço direito subindo para segurar o microfone com tatuagem, pulseiras e relógio
                val rElbow = Vec3(-0.33f, chestY + 0.14f, -0.05f)
                val rWrist = Vec3(-0.275f, headY + 0.188f + pose.micHandBob, -0.236f)
                drawLuanArm(-1f, rElbow, rWrist, drawWristAccessories = true)

                // Cachimbo / suporte do microfone + corpo preto fosco + cápsula com reflexo metálico
                drawTaperedSegment(
                    p0 = standTop,
                    p1 = pt(-0.275f, headY + 0.145f + pose.micHandBob, -0.240f),
                    r0 = scale * 0.013f,
                    r1 = scale * 0.017f,
                    light = Color(0xFF282A32),
                    base = Color(0xFF141519),
                    shadow = Color(0xFF090A0D)
                )
                drawTaperedSegment(
                    p0 = micTail,
                    p1 = micHead,
                    r0 = scale * 0.018f,
                    r1 = scale * 0.022f,
                    light = Color(0xFF383C46),
                    base = Color(0xFF15161B),
                    shadow = Color(0xFF0A0A0D)
                )
                val micHeadR = (scale * 0.030f).coerceAtLeast(3.0f)
                drawScope.drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF414652), Color(0xFF1A1B21), Color(0xFF0B0C0F)),
                        center = Offset(micHead.x - scale * 0.006f, micHead.y - scale * 0.006f),
                        radius = micHeadR * 1.1f
                    ),
                    radius = micHeadR,
                    center = micHead
                )

                // Braço esquerdo cruzando o tórax até a parte superior do microfone
                val lElbow = Vec3(0.06f + swayX * 0.7f, chestY + 0.15f, -0.175f)
                val lWrist = Vec3(-0.228f, headY + 0.132f + pose.micHandBob, -0.246f)
                drawLuanArm(1f, lElbow, lWrist, drawWristAccessories = false)

                drawGrippingHand(pt(rWrist.x, rWrist.y, rWrist.z), curlDirX = -1f, showRaisedThumb = false)
                drawGrippingHand(pt(lWrist.x, lWrist.y, lWrist.z), curlDirX = -1f, showRaisedThumb = true)
            }
        }
    }

    if (!facingFront) {
        drawLuanArmsAndGear()
    }

    // 2. TRONCO / CAMISA SOCIAL PRETA DE GOLA V, COLAR PRATA, ALÇA COSTURADA E CINTO
    val shL = pt(-0.235f + swayX * 0.9f, shoulderY + 0.01f, -0.02f)
    val shR = pt(0.235f + swayX * 0.9f, shoulderY + 0.01f, -0.02f)
    val chL = pt(-0.215f + swayX * 0.8f, chestY, -0.105f)
    val chR = pt(0.215f + swayX * 0.8f, chestY, -0.105f)
    val waL = pt(-0.175f + swayX * 0.6f, waistY, -0.098f)
    val waR = pt(0.175f + swayX * 0.6f, waistY, -0.098f)
    val hiL = pt(-0.178f + swayX * 0.4f, hipY, -0.090f)
    val hiR = pt(0.178f + swayX * 0.4f, hipY, -0.090f)

    val shLB = pt(-0.235f + swayX * 0.9f, shoulderY + 0.01f, 0.07f)
    val shRB = pt(0.235f + swayX * 0.9f, shoulderY + 0.01f, 0.07f)
    val hiLB = pt(-0.178f + swayX * 0.4f, hipY, 0.088f)
    val hiRB = pt(0.178f + swayX * 0.4f, hipY, 0.088f)

    for ((topF, botF, topB, botB) in listOf(
        listOf(shL, hiL, shLB, hiLB),
        listOf(shR, hiR, shRB, hiRB)
    )) {
        val sideP = Path().apply {
            moveTo(topF.x, topF.y); lineTo(topB.x, topB.y); lineTo(botB.x, botB.y); lineTo(botF.x, botF.y); close()
        }
        drawScope.drawPath(sideP, color = shirtShadow)
    }

    val colRootL = pt(-0.072f + swayX * 0.95f, shoulderY - 0.02f, -0.06f)
    val colRootR = pt(0.072f + swayX * 0.95f, shoulderY - 0.02f, -0.06f)
    val torsoPath = if (facingFront) {
        Path().apply {
            moveTo(colRootL.x, colRootL.y)
            lineTo(colRootR.x, colRootR.y)
            quadraticTo(shR.x, shR.y, chR.x, chR.y)
            lineTo(waR.x, waR.y); lineTo(hiR.x, hiR.y)
            lineTo(hiL.x, hiL.y); lineTo(waL.x, waL.y)
            lineTo(chL.x, chL.y)
            quadraticTo(shL.x, shL.y, colRootL.x, colRootL.y)
            close()
        }
    } else {
        Path().apply {
            moveTo(shLB.x, shLB.y); lineTo(shRB.x, shRB.y); lineTo(hiRB.x, hiRB.y); lineTo(hiLB.x, hiLB.y); close()
        }
    }
    drawScope.drawPath(
        path = torsoPath,
        brush = Brush.linearGradient(colors = listOf(shirtHighlight, shirtBase, shirtShadow), start = shL, end = hiR)
    )
    if (styleOutlineAlpha > 0.25f) {
        drawScope.drawPath(torsoPath, color = Color.Black.copy(alpha = styleOutlineAlpha), style = Stroke(width = styleOutlineW))
    }

    if (facingFront) {
        val colTopL = pt(-0.072f + swayX * 0.95f, shoulderY - 0.015f, -0.068f)
        val colTopR = pt(0.072f + swayX * 0.95f, shoulderY - 0.015f, -0.068f)
        val vTip = pt(swayX * 0.85f, chestY + 0.015f, -0.106f)

        // Pele exposta no decote em V com sombreamento natural
        val chestSkinPath = Path().apply {
            moveTo(colTopL.x, colTopL.y)
            lineTo(colTopR.x, colTopR.y)
            lineTo(vTip.x, vTip.y)
            close()
        }
        drawScope.drawPath(
            path = chestSkinPath,
            brush = Brush.verticalGradient(colors = listOf(skinBase, skinWarm), startY = colTopL.y, endY = vTip.y)
        )

        // Colar prata fino + pequeno pingente prateado dentro do decote em V
        val chainL = pt(-0.052f + swayX * 0.95f, shoulderY + 0.005f, -0.075f)
        val chainR = pt(0.052f + swayX * 0.95f, shoulderY + 0.005f, -0.075f)
        val chainMid = pt(swayX * 0.9f, shoulderY + 0.140f, -0.098f)
        val chainPath = Path().apply {
            moveTo(chainL.x, chainL.y)
            quadraticTo(chainMid.x, chainMid.y + scale * 0.018f, chainR.x, chainR.y)
        }
        drawScope.drawPath(chainPath, color = Color(0xFFDCE2EB), style = Stroke(width = 1.4f))
        drawScope.drawLine(
            color = Color(0xFFF0F4FA),
            start = Offset(chainMid.x, chainMid.y + scale * 0.004f),
            end = Offset(chainMid.x, chainMid.y + scale * 0.028f),
            strokeWidth = 2.0f,
            cap = StrokeCap.Round
        )

        // Golas pontudas da camisa social preta
        val lapelOuterL = pt(-0.112f + swayX * 0.9f, shoulderY + 0.075f, -0.095f)
        val lapelOuterR = pt(0.112f + swayX * 0.9f, shoulderY + 0.075f, -0.095f)
        val leftCollar = Path().apply { moveTo(colTopL.x, colTopL.y); lineTo(lapelOuterL.x, lapelOuterL.y); lineTo(vTip.x, vTip.y); close() }
        val rightCollar = Path().apply { moveTo(colTopR.x, colTopR.y); lineTo(lapelOuterR.x, lapelOuterR.y); lineTo(vTip.x, vTip.y); close() }
        drawScope.drawPath(leftCollar, color = Color(0xFF1D1F27))
        drawScope.drawPath(rightCollar, color = Color(0xFF1D1F27))
        drawScope.drawPath(leftCollar, color = Color(0xFF343844), style = Stroke(width = 1.1f))
        drawScope.drawPath(rightCollar, color = Color(0xFF343844), style = Stroke(width = 1.1f))

        // Carcela central e botões escuros brilhantes
        val placketBot = pt(swayX * 0.45f, hipY + 0.015f, -0.096f)
        drawScope.drawLine(color = Color(0xFF282B36), start = vTip, end = placketBot, strokeWidth = 1.4f)
        listOf(-1.14f, -0.99f).forEach { by ->
            drawScope.drawCircle(color = Color(0xFF2B2E38), radius = scale * 0.011f, center = pt(swayX * 0.6f, by, -0.102f))
            drawScope.drawCircle(color = Color(0xFF545968), radius = scale * 0.0042f, center = pt(-0.003f + swayX * 0.6f, by - 0.003f, -0.104f))
        }

        // Dobras diagonais sutis da camisa encaixada na calça
        listOf(
            Triple(-0.14f, waistY + 0.06f, -0.02f),
            Triple(0.13f, waistY + 0.07f, 0.04f),
            Triple(-0.08f, chestY + 0.14f, 0.05f)
        ).forEach { (sx, sy, ex) ->
            drawScope.drawLine(
                color = shirtHighlight.copy(alpha = 0.52f),
                start = pt(sx + swayX * 0.6f, sy, -0.098f),
                end = pt(ex + swayX * 0.5f, hipY - 0.01f, -0.095f),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
        }

        // Alça de couro marrom conhaque diagonal sobre o ombro esquerdo com costura dupla e fivela
        val strapSh = pt(0.138f + swayX * 0.9f, shoulderY + 0.005f, -0.096f)
        val strapChest = pt(0.02f + swayX * 0.7f, chestY + 0.09f, -0.114f)
        val strapHip = pt(-0.155f + swayX * 0.4f, hipY - 0.02f, -0.096f)
        val strapPath = Path().apply {
            moveTo(strapSh.x, strapSh.y)
            quadraticTo(strapChest.x, strapChest.y, strapHip.x, strapHip.y)
        }
        drawScope.drawPath(
            path = strapPath,
            color = Color(0xFF542913),
            style = Stroke(width = (scale * 0.070f).coerceAtLeast(4.6f), cap = StrokeCap.Round)
        )
        drawScope.drawPath(
            path = strapPath,
            color = Color(0xFF9F5B32),
            style = Stroke(width = (scale * 0.056f).coerceAtLeast(3.6f), cap = StrokeCap.Round)
        )
        drawScope.drawPath(
            path = strapPath,
            color = Color(0xFFDFB089).copy(alpha = 0.55f),
            style = Stroke(
                width = 1.1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
            )
        )
        // Aba de couro na altura do peito onde a alça encaixa
        val strapTab = pt(0.052f + swayX * 0.8f, shoulderY + 0.145f, -0.108f)
        drawScope.drawCircle(color = Color(0xFF4F2612), radius = (scale * 0.014f).coerceAtLeast(1.8f), center = strapTab)

        // Cinto fino preto com passadores e fivela prateada retangular
        val beltL = pt(-0.178f + swayX * 0.4f, hipY + 0.008f, -0.094f)
        val beltR = pt(0.178f + swayX * 0.4f, hipY + 0.008f, -0.094f)
        drawScope.drawLine(color = Color(0xFF07070A), start = beltL, end = beltR, strokeWidth = (scale * 0.036f).coerceAtLeast(2.8f))
        drawScope.drawLine(
            color = Color(0xFF393D49).copy(alpha = 0.72f),
            start = Offset(beltL.x, beltL.y - scale * 0.012f),
            end = Offset(beltR.x, beltR.y - scale * 0.012f),
            strokeWidth = 1.0f
        )
        listOf(-0.11f, 0.11f).forEach { lx ->
            val loopP = pt(lx + swayX * 0.4f, hipY + 0.008f, -0.097f)
            drawScope.drawLine(
                color = Color(0xFF191B22),
                start = Offset(loopP.x, loopP.y - scale * 0.022f),
                end = Offset(loopP.x, loopP.y + scale * 0.022f),
                strokeWidth = (scale * 0.012f).coerceAtLeast(1.5f)
            )
        }
        val buckle = pt(swayX * 0.4f, hipY + 0.008f, -0.098f)
        drawScope.drawRect(
            color = Color(0xFFD0D7E0),
            topLeft = Offset(buckle.x - scale * 0.032f, buckle.y - scale * 0.018f),
            size = Size(scale * 0.064f, scale * 0.036f),
            style = Stroke(width = 2.0f)
        )
    } else {
        if (spec.hasSlungAcousticGuitar) {
            drawAcousticGuitarOnBack()
        }
    }

    // 3. PESCOÇO & CABEÇA 3D ULTRA-REALISTA (SOMBREAMENTO FACIAL MULTI-CAMADA + RIG VOCAL)
    val neckBase = pt(swayX * 0.9f, shoulderY + 0.02f, -0.01f)
    val neckTop = pt(swayX * 0.95f + pose.headTiltX * 0.4f, neckTopY - 0.01f, -0.01f)
    drawTaperedSegment(neckBase, neckTop, scale * 0.056f, scale * 0.048f, skinBase, skinWarm, skinShadow)

    if (facingFront) {
        val chinShadow = pt(swayX + pose.headTiltX * 0.6f, neckTopY + 0.022f, -0.035f)
        drawScope.drawOval(
            brush = Brush.radialGradient(listOf(skinDeepAO.copy(alpha = 0.68f), Color.Transparent), chinShadow, scale * 0.06f),
            topLeft = Offset(chinShadow.x - scale * 0.054f, chinShadow.y - scale * 0.024f),
            size = Size(scale * 0.108f, scale * 0.048f)
        )
    }

    val hxShift = swayX + pose.headTiltX

    fun drawLuanHair(backLayerOnly: Boolean = false) {
        val crown = pt(-0.01f + hxShift, headY - 0.095f, 0.005f)
        val backCrown = pt(hxShift, headY - 0.02f, 0.055f)
        val sideLTop = pt(-0.088f + hxShift, headY - 0.065f, 0.005f)
        val sideLBot = pt(-0.085f + hxShift, headY + 0.025f, 0.012f)
        val sideRTop = pt(0.088f + hxShift, headY - 0.065f, 0.005f)
        val sideRBot = pt(0.085f + hxShift, headY + 0.025f, 0.012f)

        if (backLayerOnly) {
            drawScope.drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(hairMid, hairDark, hairDeep),
                    center = Offset(backCrown.x, backCrown.y - scale * 0.02f),
                    radius = scale * 0.125f
                ),
                topLeft = Offset(backCrown.x - scale * 0.105f, backCrown.y - scale * 0.125f),
                size = Size(scale * 0.21f, scale * 0.23f)
            )
        }

        drawTaperedSegment(sideLTop, sideLBot, scale * 0.023f, scale * 0.014f, hairMid, hairDark, hairDeep)
        drawTaperedSegment(sideRTop, sideRBot, scale * 0.023f, scale * 0.014f, hairMid, hairDark, hairDeep)

        val quiffL = pt(-0.086f + hxShift, headY - 0.082f, -0.055f)
        val quiffPeakL = pt(-0.055f + hxShift, headY - 0.178f, -0.025f)
        val quiffPeakC = pt(0.006f + hxShift, headY - 0.166f, -0.020f)
        val quiffPeakR = pt(0.072f + hxShift, headY - 0.132f, -0.015f)
        val quiffR = pt(0.088f + hxShift, headY - 0.075f, -0.040f)
        val hairlineC = pt(0.012f + hxShift, headY - 0.085f, -0.085f)

        // Base escura cobrindo têmporas e alto do crânio
        val baseCap = Path().apply {
            moveTo(sideLTop.x, sideLTop.y + scale * 0.015f)
            quadraticTo(crown.x, crown.y - scale * 0.038f, sideRTop.x, sideRTop.y + scale * 0.015f)
            quadraticTo(hairlineC.x, hairlineC.y - scale * 0.010f, sideLTop.x, sideLTop.y + scale * 0.015f)
            close()
        }
        drawScope.drawPath(baseCap, color = hairDark)

        val quiffPath = Path().apply {
            moveTo(quiffL.x, quiffL.y)
            cubicTo(
                quiffL.x - scale * 0.012f, quiffPeakL.y + scale * 0.02f,
                quiffPeakL.x - scale * 0.015f, quiffPeakL.y,
                quiffPeakL.x, quiffPeakL.y
            )
            cubicTo(
                quiffPeakC.x - scale * 0.01f, quiffPeakL.y - scale * 0.012f,
                quiffPeakR.x - scale * 0.02f, quiffPeakR.y - scale * 0.018f,
                quiffPeakR.x, quiffPeakR.y
            )
            quadraticTo(quiffR.x + scale * 0.008f, quiffR.y - scale * 0.01f, quiffR.x, quiffR.y)
            quadraticTo(hairlineC.x, hairlineC.y - scale * 0.012f, quiffL.x, quiffL.y)
            close()
        }

        drawScope.drawPath(
            path = quiffPath,
            brush = Brush.linearGradient(colors = listOf(hairCaramel, hairMid, hairDark), start = quiffPeakL, end = quiffR)
        )

        // Mechas caramelo em camadas com sombras profundas entre fios para textura realista
        val strandSpecs = listOf(
            Triple(pt(-0.072f + hxShift, headY - 0.095f, -0.072f), pt(-0.048f + hxShift, headY - 0.172f, -0.032f), hairHighlight),
            Triple(pt(-0.056f + hxShift, headY - 0.093f, -0.075f), pt(-0.030f + hxShift, headY - 0.168f, -0.028f), hairDeep.copy(alpha = 0.55f)),
            Triple(pt(-0.042f + hxShift, headY - 0.092f, -0.078f), pt(-0.012f + hxShift, headY - 0.164f, -0.025f), hairCaramel),
            Triple(pt(-0.015f + hxShift, headY - 0.090f, -0.080f), pt(0.026f + hxShift, headY - 0.148f, -0.020f), hairHighlight),
            Triple(pt(0.002f + hxShift, headY - 0.089f, -0.078f), pt(0.042f + hxShift, headY - 0.140f, -0.019f), hairDeep.copy(alpha = 0.50f)),
            Triple(pt(0.018f + hxShift, headY - 0.088f, -0.075f), pt(0.058f + hxShift, headY - 0.132f, -0.018f), hairCaramel),
            Triple(pt(-0.058f + hxShift, headY - 0.125f, -0.050f), pt(0.010f + hxShift, headY - 0.155f, -0.010f), hairHighlight.copy(alpha = 0.72f))
        )
        strandSpecs.forEach { (s0, s1, col) ->
            val mid = Offset((s0.x + s1.x) * 0.5f - scale * 0.008f, (s0.y + s1.y) * 0.5f - scale * 0.012f)
            val strandP = Path().apply {
                moveTo(s0.x, s0.y)
                quadraticTo(mid.x, mid.y, s1.x, s1.y)
            }
            drawScope.drawPath(
                path = strandP,
                color = col,
                style = Stroke(width = (scale * 0.014f).coerceAtLeast(1.3f), cap = StrokeCap.Round)
            )
        }

        if (!facingFront) {
            drawScope.drawCircle(color = hairDark, radius = scale * 0.055f, center = crown)
        }
    }

    // Orelhas proporcionais + Ponto eletrônico (IEM) preto & brinco prateado na orelha esquerda
    fun drawEars(drawLeft: Boolean, drawRight: Boolean) {
        val earR = pt(-0.092f + hxShift, headY + 0.008f, 0.005f)
        val earL = pt(0.092f + hxShift, headY + 0.008f, 0.005f)
        val ew = scale * 0.022f
        val eh = scale * 0.044f

        if (drawRight) {
            drawScope.drawOval(color = skinWarm, topLeft = Offset(earR.x - ew * 0.65f, earR.y - eh * 0.5f), size = Size(ew, eh))
        }
        if (drawLeft) {
            drawScope.drawOval(color = skinBase, topLeft = Offset(earL.x - ew * 0.35f, earL.y - eh * 0.5f), size = Size(ew, eh))
            if (spec.hasLeftInEarMonitor) {
                drawScope.drawOval(
                    color = Color(0xFF14151A),
                    topLeft = Offset(earL.x - ew * 0.15f, earL.y - eh * 0.22f),
                    size = Size(ew * 0.74f, eh * 0.46f)
                )
                drawScope.drawCircle(
                    color = Color(0xFF474C5A),
                    radius = (scale * 0.0046f).coerceAtLeast(0.9f),
                    center = Offset(earL.x + ew * 0.18f, earL.y - eh * 0.04f)
                )
                drawScope.drawCircle(
                    color = Color(0xFFE7ECF4),
                    radius = (scale * 0.0066f).coerceAtLeast(1.1f),
                    center = Offset(earL.x + ew * 0.12f, earL.y + eh * 0.42f)
                )
            }
        }
    }

    val drawRightEarFront = sin(yawRad) >= -0.35f
    val drawLeftEarFront = sin(yawRad) <= 0.35f
    drawEars(drawLeft = !drawLeftEarFront, drawRight = !drawRightEarFront)

    val fTop = pt(hxShift, headY - 0.115f, -0.025f)
    val fTemL = pt(-0.086f + hxShift, headY - 0.055f, -0.035f)
    val fTemR = pt(0.086f + hxShift, headY - 0.055f, -0.035f)
    val fChkL = pt(-0.086f + hxShift, headY + 0.025f, -0.048f)
    val fChkR = pt(0.086f + hxShift, headY + 0.025f, -0.048f)
    val fJawL = pt(-0.068f + hxShift, headY + 0.085f, -0.052f)
    val fJawR = pt(0.068f + hxShift, headY + 0.085f, -0.052f)
    val fChin = pt(hxShift, headY + 0.132f, -0.072f)

    val facePath = Path().apply {
        moveTo(fTop.x, fTop.y)
        cubicTo(fTop.x + scale * 0.058f, fTop.y, fTemR.x, fTemR.y - scale * 0.03f, fTemR.x, fTemR.y)
        lineTo(fChkR.x, fChkR.y)
        quadraticTo(fJawR.x + scale * 0.012f, fJawR.y, fJawR.x, fJawR.y)
        cubicTo(fJawR.x - scale * 0.015f, fChin.y - scale * 0.01f, fChin.x + scale * 0.028f, fChin.y, fChin.x, fChin.y)
        cubicTo(fChin.x - scale * 0.028f, fChin.y, fJawL.x + scale * 0.015f, fChin.y - scale * 0.01f, fJawL.x, fJawL.y)
        quadraticTo(fJawL.x - scale * 0.012f, fJawL.y, fChkL.x, fChkL.y)
        lineTo(fTemL.x, fTemL.y)
        cubicTo(fTemL.x, fTemL.y - scale * 0.03f, fTop.x - scale * 0.058f, fTop.y, fTop.x, fTop.y)
        close()
    }

    val faceCenterProj = pt(hxShift, headY - 0.015f, -0.06f)
    drawScope.drawPath(
        path = facePath,
        brush = Brush.radialGradient(
            colors = listOf(skinSpec, skinLight, skinBase, skinWarm, skinShadow),
            center = Offset(faceCenterProj.x - scale * 0.012f, faceCenterProj.y - scale * 0.022f),
            radius = scale * 0.155f
        )
    )
    if (styleOutlineAlpha > 0.25f) {
        drawScope.drawPath(
            path = facePath,
            color = Color.Black.copy(alpha = styleOutlineAlpha * 0.5f),
            style = Stroke(width = styleOutlineW)
        )
    }

    if (facingFront) {
        // Sombra suave da cavidade orbital + brilho natural das maçãs do rosto e testa
        val foreheadPt = pt(hxShift, headY - 0.066f, -0.098f)
        drawScope.drawOval(
            brush = Brush.radialGradient(listOf(skinSpec.copy(alpha = 0.55f), Color.Transparent), foreheadPt, scale * 0.048f),
            topLeft = Offset(foreheadPt.x - scale * 0.048f, foreheadPt.y - scale * 0.022f),
            size = Size(scale * 0.096f, scale * 0.044f)
        )

        val cheekL = pt(-0.048f + hxShift, headY + 0.024f, -0.090f)
        val cheekR = pt(0.048f + hxShift, headY + 0.024f, -0.090f)
        listOf(cheekL, cheekR).forEach { ck ->
            drawScope.drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x3AD67B60), Color.Transparent), ck, scale * 0.038f),
                radius = scale * 0.038f,
                center = ck
            )
        }

        // Barba rala (stubble) realista na mandíbula, queixo e bochecha + pelos texturizados
        val stubblePath = Path().apply {
            moveTo(fTemL.x * 0.1f + fChkL.x * 0.9f, fChkL.y + scale * 0.01f)
            lineTo(fJawL.x, fJawL.y)
            quadraticTo(fChin.x, fChin.y + scale * 0.004f, fJawR.x, fJawR.y)
            lineTo(fTemR.x * 0.1f + fChkR.x * 0.9f, fChkR.y + scale * 0.01f)
            val mCornerR = pt(0.044f + hxShift, headY + 0.068f, -0.095f)
            val mBot = pt(hxShift, headY + 0.104f, -0.095f)
            val mCornerL = pt(-0.044f + hxShift, headY + 0.068f, -0.095f)
            lineTo(mCornerR.x, mCornerR.y)
            quadraticTo(mBot.x, mBot.y, mCornerL.x, mCornerL.y)
            close()
        }
        drawScope.drawPath(stubblePath, color = Color(0xFF312018).copy(alpha = 0.28f))

        // Pontilhado orgânico de pelos curtos ao longo da linha da barba e queixo
        for (i in -10..10) {
            val f = i / 10f
            val bx = f * 0.072f + hxShift
            val by = headY + 0.123f - abs(f) * 0.052f
            val bz = -0.078f + abs(f) * 0.028f
            drawScope.drawCircle(
                color = Color(0xFF231510).copy(alpha = 0.36f),
                radius = (scale * 0.0038f).coerceAtLeast(0.75f),
                center = pt(bx, by, bz)
            )
            if (i % 2 == 0) {
                drawScope.drawCircle(
                    color = Color(0xFF231510).copy(alpha = 0.26f),
                    radius = (scale * 0.0034f).coerceAtLeast(0.7f),
                    center = pt(bx * 0.96f + hxShift * 0.04f, by - 0.016f, bz - 0.006f)
                )
            }
        }

        // Bigode bem definido acompanhando o arco do sorriso
        val stacheL = pt(-0.036f + hxShift, headY + 0.048f, -0.100f)
        val stacheC = pt(hxShift, headY + 0.039f, -0.108f)
        val stacheR = pt(0.036f + hxShift, headY + 0.048f, -0.100f)
        drawScope.drawPath(
            path = Path().apply { moveTo(stacheL.x, stacheL.y); quadraticTo(stacheC.x, stacheC.y, stacheR.x, stacheR.y) },
            color = Color(0xFF241610).copy(alpha = 0.54f),
            style = Stroke(width = (scale * 0.0115f).coerceAtLeast(1.4f), cap = StrokeCap.Round)
        )

        // Sobrancelhas expressivas espessas castanho-escuras com elevação vocal animada
        val browL0 = pt(-0.060f + hxShift, headY - 0.046f - pose.browLift, -0.088f)
        val browL1 = pt(-0.038f + hxShift, headY - 0.054f - pose.browLift * 1.2f, -0.098f)
        val browL2 = pt(-0.016f + hxShift, headY - 0.049f - pose.browLift, -0.102f)
        val browR0 = pt(0.016f + hxShift, headY - 0.049f - pose.browLift, -0.102f)
        val browR1 = pt(0.038f + hxShift, headY - 0.054f - pose.browLift * 1.2f, -0.098f)
        val browR2 = pt(0.060f + hxShift, headY - 0.046f - pose.browLift, -0.088f)

        drawTaperedSegment(browL2, browL1, scale * 0.0085f, scale * 0.0074f, hairDark, hairDark, hairDeep)
        drawTaperedSegment(browL1, browL0, scale * 0.0074f, scale * 0.0036f, hairDark, hairDark, hairDeep)
        drawTaperedSegment(browR0, browR1, scale * 0.0085f, scale * 0.0074f, hairDark, hairDark, hairDeep)
        drawTaperedSegment(browR1, browR2, scale * 0.0074f, scale * 0.0036f, hairDark, hairDark, hairDeep)

        // Olhos castanhos amendoados em cavidade orbital com duplo brilho e piscar natural
        val eyeL = pt(-0.036f + hxShift, headY - 0.022f, -0.096f)
        val eyeR = pt(0.036f + hxShift, headY - 0.022f, -0.096f)
        val eyeW = scale * 0.030f
        val baseEyeH = scale * when (spec.artStyle) {
            CharacterArtStyle.ANIME -> 0.022f
            CharacterArtStyle.CARTOON -> 0.019f
            CharacterArtStyle.STYLIZED_REALISTIC -> 0.0148f
        }
        val eyeH = baseEyeH * pose.eyeOpenScale

        listOf(eyeL, eyeR).forEach { ec ->
            // Sombra suave da cavidade orbital
            drawScope.drawOval(
                brush = Brush.radialGradient(listOf(skinShadow.copy(alpha = 0.30f), Color.Transparent), ec, eyeW * 0.72f),
                topLeft = Offset(ec.x - eyeW * 0.68f, ec.y - baseEyeH * 1.15f),
                size = Size(eyeW * 1.36f, baseEyeH * 2.15f)
            )

            val eyeOpening = Path().apply {
                moveTo(ec.x - eyeW * 0.5f, ec.y + eyeH * 0.05f)
                quadraticTo(ec.x, ec.y - eyeH * 0.72f, ec.x + eyeW * 0.5f, ec.y + eyeH * 0.05f)
                quadraticTo(ec.x, ec.y + eyeH * 0.55f, ec.x - eyeW * 0.5f, ec.y + eyeH * 0.05f)
                close()
            }
            if (pose.eyeOpenScale > 0.24f) {
                drawScope.drawPath(eyeOpening, color = Color(0xFFF3EFEA))
                val irisR = (baseEyeH * 0.55f).coerceAtMost(eyeH * 0.68f)
                drawScope.drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF6B3E22), Color(0xFF3E2214), Color(0xFF23110A)),
                        center = ec,
                        radius = irisR
                    ),
                    radius = irisR,
                    center = ec
                )
                drawScope.drawCircle(color = Color(0xFF140A06), radius = irisR * 0.50f, center = ec)
                drawScope.drawCircle(
                    color = Color.White,
                    radius = (irisR * 0.25f).coerceAtLeast(0.8f),
                    center = Offset(ec.x - irisR * 0.28f, ec.y - irisR * 0.28f)
                )
                drawScope.drawCircle(
                    color = Color.White.copy(alpha = 0.7f),
                    radius = (irisR * 0.12f).coerceAtLeast(0.5f),
                    center = Offset(ec.x + irisR * 0.22f, ec.y + irisR * 0.20f)
                )
            }
            val upperLid = Path().apply {
                moveTo(ec.x - eyeW * 0.52f, ec.y + eyeH * 0.05f)
                quadraticTo(ec.x, ec.y - eyeH * 0.76f, ec.x + eyeW * 0.52f, ec.y + eyeH * 0.05f)
            }
            drawScope.drawPath(upperLid, color = Color(0xFF1D120D), style = Stroke(width = 1.65f, cap = StrokeCap.Round))
            val crease = Path().apply {
                moveTo(ec.x - eyeW * 0.42f, ec.y - baseEyeH * 0.50f)
                quadraticTo(ec.x, ec.y - baseEyeH * 0.98f, ec.x + eyeW * 0.42f, ec.y - baseEyeH * 0.50f)
            }
            drawScope.drawPath(crease, color = skinShadow.copy(alpha = 0.55f), style = Stroke(width = 0.9f))
        }

        // Nariz esculpido com ponte luminosa e sombra suave na ponta e narinas
        val noseBridge = pt(hxShift, headY - 0.015f, -0.106f)
        val noseTip = pt(hxShift, headY + 0.022f, -0.119f)
        val nostrilL = pt(-0.014f + hxShift, headY + 0.024f, -0.106f)
        val nostrilR = pt(0.014f + hxShift, headY + 0.024f, -0.106f)

        drawScope.drawLine(
            color = skinSpec.copy(alpha = 0.78f),
            start = noseBridge,
            end = noseTip,
            strokeWidth = (scale * 0.011f).coerceAtLeast(1.3f),
            cap = StrokeCap.Round
        )
        drawScope.drawCircle(color = skinSpec.copy(alpha = 0.50f), radius = (scale * 0.0085f).coerceAtLeast(1.0f), center = noseTip)
        drawScope.drawLine(
            color = skinShadow.copy(alpha = 0.45f),
            start = Offset(noseTip.x - scale * 0.008f, noseTip.y + scale * 0.004f),
            end = Offset(noseTip.x + scale * 0.008f, noseTip.y + scale * 0.004f),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round
        )
        drawScope.drawCircle(color = skinDeepAO.copy(alpha = 0.52f), radius = (scale * 0.0045f).coerceAtLeast(0.9f), center = nostrilL)
        drawScope.drawCircle(color = skinDeepAO.copy(alpha = 0.52f), radius = (scale * 0.0045f).coerceAtLeast(0.9f), center = nostrilR)

        // Sorriso aberto com articulação vocal e dentes naturais
        val mouthL = pt(-0.036f + hxShift, headY + 0.058f, -0.098f)
        val mouthR = pt(0.036f + hxShift, headY + 0.058f, -0.098f)
        val mouthTopC = pt(hxShift, headY + 0.054f, -0.106f)
        val mouthBotC = pt(hxShift, headY + 0.072f + pose.mouthOpenAmount * 0.022f, -0.102f)

        drawScope.drawLine(
            color = skinShadow.copy(alpha = 0.36f),
            start = Offset(nostrilL.x - scale * 0.008f, nostrilL.y + scale * 0.004f),
            end = Offset(mouthL.x - scale * 0.006f, mouthL.y + scale * 0.006f),
            strokeWidth = 1.0f
        )
        drawScope.drawLine(
            color = skinShadow.copy(alpha = 0.36f),
            start = Offset(nostrilR.x + scale * 0.008f, nostrilR.y + scale * 0.004f),
            end = Offset(mouthR.x + scale * 0.006f, mouthR.y + scale * 0.006f),
            strokeWidth = 1.0f
        )

        val mouthOpening = Path().apply {
            moveTo(mouthL.x, mouthL.y)
            quadraticTo(mouthTopC.x, mouthTopC.y, mouthR.x, mouthR.y)
            quadraticTo(mouthBotC.x, mouthBotC.y, mouthL.x, mouthL.y)
            close()
        }
        drawScope.drawPath(mouthOpening, color = Color(0xFF421517))

        val teethPath = Path().apply {
            moveTo(mouthL.x * 0.88f + mouthTopC.x * 0.12f, mouthL.y + scale * 0.002f)
            quadraticTo(mouthTopC.x, mouthTopC.y + scale * 0.002f, mouthR.x * 0.88f + mouthTopC.x * 0.12f, mouthR.y + scale * 0.002f)
            quadraticTo(mouthTopC.x, (mouthTopC.y + mouthBotC.y) * 0.56f, mouthL.x * 0.88f + mouthTopC.x * 0.12f, mouthL.y + scale * 0.002f)
            close()
        }
        drawScope.drawPath(teethPath, color = Color(0xFFF8F6F3))

        drawScope.drawPath(
            path = Path().apply {
                moveTo(mouthL.x, mouthL.y)
                quadraticTo(mouthTopC.x, mouthTopC.y - scale * 0.004f, mouthR.x, mouthR.y)
            },
            color = Color(0xFF9F584F),
            style = Stroke(width = 1.4f, cap = StrokeCap.Round)
        )
        drawScope.drawPath(
            path = Path().apply {
                moveTo(mouthL.x, mouthL.y)
                quadraticTo(mouthBotC.x, mouthBotC.y + scale * 0.010f, mouthR.x, mouthR.y)
                quadraticTo(mouthBotC.x, mouthBotC.y, mouthL.x, mouthL.y)
                close()
            },
            color = Color(0xFFC4796F)
        )

        drawLuanHair(backLayerOnly = false)
        drawEars(drawLeft = drawLeftEarFront, drawRight = drawRightEarFront)
    } else {
        drawEars(drawLeft = drawLeftEarFront, drawRight = drawRightEarFront)
        drawLuanHair(backLayerOnly = true)
    }

    if (facingFront) {
        drawLuanArmsAndGear()
    }
}
