package com.example.guitarlegends.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.guitarlegends.model.*
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// Lane color palette (Green, Red, Yellow, Blue, Orange)
val LaneColors = listOf(
    Color(0xFF00E676), // 0: Green
    Color(0xFFFF1744), // 1: Red
    Color(0xFFFFEA00), // 2: Yellow
    Color(0xFF2979FF), // 3: Blue
    Color(0xFFFF9100)  // 4: Orange
)

val LaneFretBgColors = listOf(
    Color(0x3300E676),
    Color(0x33FF1744),
    Color(0x33FFEA00),
    Color(0x332979FF),
    Color(0x33FF9100)
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float, // 1.0 down to 0.0
    val color: Color,
    val size: Float
)

class ParticleSystem {
    private val particles = mutableListOf<Particle>()
    private val random = Random(42)

    fun spawnBurst(centerX: Float, centerY: Float, color: Color, count: Int = 18) {
        for (i in 0 until count) {
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = random.nextFloat() * 12f + 4f
            particles.add(
                Particle(
                    x = centerX,
                    y = centerY,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 3f,
                    life = 1.0f,
                    color = color,
                    size = random.nextFloat() * 8f + 4f
                )
            )
        }
    }

    fun updateAndDraw(drawScope: DrawScope) {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy
            p.vy += 0.4f
            p.life -= 0.05f

            if (p.life <= 0f) {
                iter.remove()
            } else {
                drawScope.drawCircle(
                    color = p.color.copy(alpha = p.life),
                    radius = p.size * p.life,
                    center = Offset(p.x, p.y)
                )
            }
        }
    }
}

/**
 * Renderizador de Palco 3D e Esteira de Notas Estilo Console Clássico Modernizado.
 * - Esteira em perspectiva roxo-índigo clássica com bordas douradas/bronze metálicas.
 * - Conectores horizontais entre alvos circulares (o--o--o--o) e notas 3D chanfradas.
 * - Suporte a notas sustentadas (sustain contínuo + quebra ao soltar cedo) e efeitos de palhetada.
 * - Suporte a duas esteiras simultâneas lado a lado no modo Multiplayer mantendo o palco visível.
 */
data class HighwayGeometry(
    val horizonY: Float,
    val strikeY: Float,
    val centerX: Float,
    val topWidth: Float,
    val bottomWidth: Float
)

fun computeHighwayLaneNormalizedOffset(lane: Int, numLanes: Int): Float {
    val rawOffset = ((lane + 0.5f) / numLanes.toFloat()) - 0.5f
    return rawOffset
}

fun computeSingleHighwayGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    difficulty: Difficulty
): HighwayGeometry {
    val horizonY = canvasHeight * 0.138f
    val strikeY = canvasHeight * 0.842f
    val centerX = canvasWidth * 0.50f
    val topFraction = when (difficulty.lanesUsed) {
        3 -> 0.098f
        4 -> 0.110f
        else -> 0.122f
    }
    val bottomFraction = when (difficulty.lanesUsed) {
        3 -> 0.340f
        4 -> 0.382f
        else -> 0.422f
    }
    val topWidth = (canvasWidth * topFraction).coerceAtLeast(36f)
    val bottomWidth = (canvasWidth * bottomFraction).coerceAtLeast(120f)
    return HighwayGeometry(horizonY, strikeY, centerX, topWidth, bottomWidth)
}

fun computeDualLeftHighwayGeometry(
    canvasWidth: Float,
    canvasHeight: Float
): HighwayGeometry {
    val horizonY = canvasHeight * 0.16f
    val strikeY = canvasHeight * 0.82f
    val centerX = canvasWidth * 0.275f
    val topWidth = (canvasWidth * 0.098f).coerceAtLeast(34f)
    val bottomWidth = (canvasWidth * 0.325f).coerceAtLeast(115f)
    return HighwayGeometry(horizonY, strikeY, centerX, topWidth, bottomWidth)
}

fun drawHighway(
    drawScope: DrawScope,
    canvasWidth: Float,
    canvasHeight: Float,
    currentTimeMs: Long,
    notes: List<Note>,
    difficulty: Difficulty,
    activeFretLanes: Set<Int>,
    isStarPowerActive: Boolean,
    particleSystem: ParticleSystem,
    highwayTheme: HighwayTheme = HighwayTheme.PS2_WOOD_STEEL,
    rockMeter: Float = 0.6f,
    combo: Int = 0,
    instrument: Instrument = Instrument.GUITAR,
    playerStats: PlayerStats = PlayerStats(),
    highwayOverlayBitmap: ImageBitmap? = null,
    overlayOpacity: Float = 0.55f,
    activePickFlashes: Map<Int, Long> = emptyMap(),
    strikeEnabled: Boolean = true,
    nowWallMs: Long = System.currentTimeMillis()
) {
    val geo = computeSingleHighwayGeometry(canvasWidth, canvasHeight, difficulty)
    val timeSec = currentTimeMs / 1000f
    val effectiveStarPower = isStarPowerActive

    // 1. DESENHO DO PALCO DE SHOW TEMÁTICO COM BANDA COMPLETA AO REDOR DA ESTEIRA
    drawStageEnvironment(
        drawScope = drawScope,
        width = canvasWidth,
        height = canvasHeight,
        currentTimeMs = currentTimeMs,
        isStarPower = effectiveStarPower,
        rockMeter = rockMeter,
        combo = combo,
        instrument = instrument,
        playerStats = playerStats,
        highwayTheme = highwayTheme,
        horizonY = geo.horizonY,
        centerX = geo.centerX
    )

    // 2. ESTEIRA DE NOTAS CLÁSSICA COM PERSPECTIVA FIXA CENTRALIZADA
    drawSinglePerspectiveHighway(
        drawScope = drawScope,
        centerX = geo.centerX,
        tiltShiftTopX = 0f,
        topWidth = geo.topWidth,
        bottomWidth = geo.bottomWidth,
        horizonY = geo.horizonY,
        strikeY = geo.strikeY,
        currentTimeMs = currentTimeMs,
        notes = notes,
        difficulty = difficulty,
        activeFretLanes = activeFretLanes,
        activePickFlashes = activePickFlashes,
        isStarPowerActive = effectiveStarPower,
        highwayTheme = highwayTheme,
        highwayOverlayBitmap = highwayOverlayBitmap,
        overlayOpacity = overlayOpacity,
        nowWallMs = nowWallMs,
        timeSec = timeSec
    )

    particleSystem.updateAndDraw(drawScope)
}

/**
 * Renderiza o palco com DUAS esteiras simultâneas lado a lado em perspectiva inclinada
 * para o centro, preservando a visibilidade do palco e banda ao centro.
 */
fun drawDualMultiplayerHighways(
    drawScope: DrawScope,
    canvasWidth: Float,
    canvasHeight: Float,
    currentTimeMs: Long,
    leftNotes: List<Note>,
    leftDifficulty: Difficulty,
    leftActiveLanes: Set<Int>,
    leftPickFlashes: Map<Int, Long>,
    leftStarPowerActive: Boolean,
    rightNotes: List<Note>,
    rightDifficulty: Difficulty,
    rightActiveLanes: Set<Int>,
    rightPickFlashes: Map<Int, Long>,
    rightStarPowerActive: Boolean,
    particleSystem: ParticleSystem,
    highwayTheme: HighwayTheme = HighwayTheme.PS2_WOOD_STEEL,
    rockMeter: Float = 0.6f,
    combo: Int = 0,
    instrument: Instrument = Instrument.GUITAR,
    playerStats: PlayerStats = PlayerStats(),
    highwayOverlayBitmap: ImageBitmap? = null,
    overlayOpacity: Float = 0.55f,
    strikeEnabled: Boolean = true,
    nowWallMs: Long = System.currentTimeMillis()
) {
    val leftGeo = computeDualLeftHighwayGeometry(canvasWidth, canvasHeight)
    val horizonY = leftGeo.horizonY
    val strikeY = leftGeo.strikeY
    val stageCenterX = canvasWidth * 0.50f
    val timeSec = currentTimeMs / 1000f
    val anyStarPower = leftStarPowerActive || rightStarPowerActive

    drawStageEnvironment(
        drawScope = drawScope,
        width = canvasWidth,
        height = canvasHeight,
        currentTimeMs = currentTimeMs,
        isStarPower = anyStarPower,
        rockMeter = rockMeter,
        combo = combo,
        instrument = instrument,
        playerStats = playerStats,
        highwayTheme = highwayTheme,
        horizonY = horizonY,
        centerX = stageCenterX
    )

    val topWidth = leftGeo.topWidth
    val bottomWidth = leftGeo.bottomWidth
    val inwardTilt = canvasWidth * 0.042f

    // Esteira Esquerda (Jogador Local)
    drawSinglePerspectiveHighway(
        drawScope = drawScope,
        centerX = canvasWidth * 0.275f,
        tiltShiftTopX = inwardTilt,
        topWidth = topWidth,
        bottomWidth = bottomWidth,
        horizonY = horizonY,
        strikeY = strikeY,
        currentTimeMs = currentTimeMs,
        notes = leftNotes,
        difficulty = leftDifficulty,
        activeFretLanes = leftActiveLanes,
        activePickFlashes = leftPickFlashes,
        isStarPowerActive = leftStarPowerActive,
        strikeEnabled = strikeEnabled,
        highwayTheme = highwayTheme,
        highwayOverlayBitmap = highwayOverlayBitmap,
        overlayOpacity = overlayOpacity,
        nowWallMs = nowWallMs,
        timeSec = timeSec
    )

    // Esteira Direita (Parceiro / Adversário Sincronizado)
    drawSinglePerspectiveHighway(
        drawScope = drawScope,
        centerX = canvasWidth * 0.725f,
        tiltShiftTopX = -inwardTilt,
        topWidth = topWidth,
        bottomWidth = bottomWidth,
        horizonY = horizonY,
        strikeY = strikeY,
        currentTimeMs = currentTimeMs,
        notes = rightNotes,
        difficulty = rightDifficulty,
        activeFretLanes = rightActiveLanes,
        activePickFlashes = rightPickFlashes,
        isStarPowerActive = rightStarPowerActive,
        strikeEnabled = strikeEnabled,
        highwayTheme = highwayTheme,
        highwayOverlayBitmap = highwayOverlayBitmap,
        overlayOpacity = overlayOpacity,
        nowWallMs = nowWallMs,
        timeSec = timeSec
    )

    particleSystem.updateAndDraw(drawScope)
}

private fun drawSinglePerspectiveHighway(
    drawScope: DrawScope,
    centerX: Float,
    tiltShiftTopX: Float,
    topWidth: Float,
    bottomWidth: Float,
    horizonY: Float,
    strikeY: Float,
    currentTimeMs: Long,
    notes: List<Note>,
    difficulty: Difficulty,
    activeFretLanes: Set<Int>,
    activePickFlashes: Map<Int, Long>,
    isStarPowerActive: Boolean,
    highwayTheme: HighwayTheme,
    highwayOverlayBitmap: ImageBitmap?,
    overlayOpacity: Float,
    nowWallMs: Long,
    timeSec: Float
) {
    val numLanes = difficulty.lanesUsed
    val topCenterX = centerX + tiltShiftTopX
    val topHalfW = topWidth * 0.5f
    val bottomHalfW = bottomWidth * 0.5f

    val rxTarget = (bottomWidth / numLanes) * 0.36f
    val ryTarget = rxTarget * 0.65f
    // Margem extra para manter o receptor e o brilho totalmente dentro da borda inferior da pista.
    val bottomEdgeY = strikeY + ryTarget * 1.08f

    val highwayTopLeft = Offset(topCenterX - topHalfW, horizonY)
    val highwayTopRight = Offset(topCenterX + topHalfW, horizonY)
    val highwayBottomRight = Offset(centerX + bottomHalfW, bottomEdgeY)
    val highwayBottomLeft = Offset(centerX - bottomHalfW, bottomEdgeY)

    val highwayPath = Path().apply {
        moveTo(highwayTopLeft.x, highwayTopLeft.y)
        lineTo(highwayTopRight.x, highwayTopRight.y)
        lineTo(highwayBottomRight.x, highwayBottomRight.y)
        lineTo(highwayBottomLeft.x, highwayBottomLeft.y)
        close()
    }

    val themeColors = highwayTheme.surfaceGradientHex.map { Color(it) }
    val highwayBrush = if (isStarPowerActive) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF071A2F),
                Color(0xFF103F68),
                Color(0xFF00B0FF).copy(alpha = 0.78f),
                Color(0xFF00E5FF).copy(alpha = 0.90f)
            ),
            startY = horizonY,
            endY = bottomEdgeY
        )
    } else {
        Brush.verticalGradient(
            colors = themeColors,
            startY = horizonY,
            endY = bottomEdgeY
        )
    }

    drawScope.drawPath(highwayPath, brush = highwayBrush, style = Fill)

    if (highwayOverlayBitmap != null) {
        drawScope.clipPath(highwayPath) {
            val minX = minOf(highwayTopLeft.x, highwayBottomLeft.x).toInt()
            val maxX = maxOf(highwayTopRight.x, highwayBottomRight.x).toInt()
            val destTop = horizonY.toInt()
            val destW = (maxX - minX).coerceAtLeast(1)
            val destH = (bottomEdgeY - horizonY).toInt().coerceAtLeast(1)
            drawImage(
                image = highwayOverlayBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(highwayOverlayBitmap.width, highwayOverlayBitmap.height),
                dstOffset = IntOffset(minX, destTop),
                dstSize = IntSize(destW, destH),
                alpha = overlayOpacity.coerceIn(0.15f, 0.90f)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.28f),
                topLeft = Offset(minX.toFloat(), destTop.toFloat()),
                size = Size(destW.toFloat(), destH.toFloat())
            )
        }
    }

    // Trilhos Laterais Dourados/Champanhe Metálicos Limpos (idênticos à referência)
    val railColor = if (isStarPowerActive) Color(0xFF00E5FF) else Color(highwayTheme.primaryColorHex)
    drawScope.drawLine(color = Color(0xFF120E19), start = highwayTopLeft, end = highwayBottomLeft, strokeWidth = 9.0f)
    drawScope.drawLine(color = Color(0xFF120E19), start = highwayTopRight, end = highwayBottomRight, strokeWidth = 9.0f)
    drawScope.drawLine(color = railColor, start = highwayTopLeft, end = highwayBottomLeft, strokeWidth = 5.2f)
    drawScope.drawLine(color = railColor, start = highwayTopRight, end = highwayBottomRight, strokeWidth = 5.2f)
    drawScope.drawLine(color = Color.White.copy(alpha = 0.62f), start = highwayTopLeft, end = highwayBottomLeft, strokeWidth = 1.4f)
    drawScope.drawLine(color = Color.White.copy(alpha = 0.62f), start = highwayTopRight, end = highwayBottomRight, strokeWidth = 1.4f)

    fun widthAtDepth(curvedT: Float): Float = topWidth + (bottomWidth - topWidth) * curvedT

    fun get3dPosition(lane: Int, depth01: Float): Offset {
        val curvedT = depth01.coerceIn(0f, 1.15f).toDouble().pow(1.65).toFloat()
        val currentY = horizonY + (strikeY - horizonY) * curvedT
        val currentWidth = widthAtDepth(curvedT)
        val rowCenterX = centerX + tiltShiftTopX * (1f - curvedT)
        val laneCenterX = rowCenterX + currentWidth * computeHighwayLaneNormalizedOffset(lane, numLanes)
        return Offset(laneCenterX, currentY)
    }

    // Cordas metálicas sutis no centro de cada pista + divisórias de pista
    val bottomEdgeScale = (bottomEdgeY - horizonY) / (strikeY - horizonY)
    val bottomEdgeWidth = topWidth + (bottomWidth - topWidth) * bottomEdgeScale
    for (lane in 0 until numLanes) {
        val offsetNorm = computeHighwayLaneNormalizedOffset(lane, numLanes)
        val topStrX = topCenterX + topWidth * offsetNorm
        val botStrX = centerX + bottomEdgeWidth * offsetNorm
        drawScope.drawLine(
            color = Color(0xFFC2C8D6).copy(alpha = 0.16f),
            start = Offset(topStrX, horizonY),
            end = Offset(botStrX, bottomEdgeY - 2f),
            strokeWidth = 1.2f
        )
    }
    for (i in 1 until numLanes) {
        val divOffsetNorm = (
            computeHighwayLaneNormalizedOffset(i - 1, numLanes) +
                computeHighwayLaneNormalizedOffset(i, numLanes)
            ) * 0.5f
        val topLaneX = topCenterX + topWidth * divOffsetNorm
        val bottomLaneX = centerX + bottomEdgeWidth * divOffsetNorm
        drawScope.drawLine(
            color = Color.White.copy(alpha = 0.22f),
            start = Offset(topLaneX, horizonY),
            end = Offset(bottomLaneX, bottomEdgeY - 2f),
            strokeWidth = 1.4f
        )
    }

    // Trastes horizontais em perspectiva (linhas de batida/compasso)
    val beatDistanceMs = 500L
    val lookAheadMs = (1450L / difficulty.noteSpeedMultiplier).toLong()
    val firstBeat = ((currentTimeMs / beatDistanceMs) * beatDistanceMs)
    for (b in 0..5) {
        val beatTime = firstBeat + b * beatDistanceMs
        val timeUntilStrike = beatTime - currentTimeMs
        if (timeUntilStrike in 0L..lookAheadMs) {
            val depth = 1.0f - (timeUntilStrike.toFloat() / lookAheadMs.toFloat())
            val curved = depth.toDouble().pow(1.65).toFloat()
            val y = horizonY + (strikeY - horizonY) * curved
            val rowCx = centerX + tiltShiftTopX * (1f - curved)
            val w = widthAtDepth(curved)
            drawScope.drawLine(
                color = Color.White.copy(alpha = 0.10f + depth * 0.22f),
                start = Offset(rowCx - w * 0.5f, y),
                end = Offset(rowCx + w * 0.5f, y),
                strokeWidth = (1.0f + 1.6f * depth)
            )
        }
    }

    // Conectores horizontais ligando apenas os vãos entre os anéis externos dos receptores (o--o--o)
    for (lane in 0 until numLanes - 1) {
        val p1 = get3dPosition(lane, 1.0f)
        val p2 = get3dPosition(lane + 1, 1.0f)
        val bridgeStart = Offset(p1.x + rxTarget * 1.02f, strikeY)
        val bridgeEnd = Offset(p2.x - rxTarget * 1.02f, strikeY)
        if (bridgeEnd.x > bridgeStart.x) {
            drawScope.drawLine(color = Color(0xFF100C18), start = bridgeStart, end = bridgeEnd, strokeWidth = 6.0f)
            drawScope.drawLine(color = Color(0xFFEBEEF4).copy(alpha = 0.94f), start = bridgeStart, end = bridgeEnd, strokeWidth = 2.4f)
        }
    }

    // Alvos de Traste 3D em Perspectiva idênticos à imagem de referência (aro externo prateado + poço grafite largo + aro interno branco + domo LED brilhante)
    for (lane in 0 until numLanes) {
        val targetPos = get3dPosition(lane, 1.0f)
        val color = if (isStarPowerActive) Color(0xFF00E5FF) else LaneColors[lane]
        val isPressed = activeFretLanes.contains(lane)
        val flashTimestamp = activePickFlashes[lane] ?: 0L
        val flashProgress = if (flashTimestamp > 0L) {
            1f - ((nowWallMs - flashTimestamp).coerceIn(0L, 240L) / 240f)
        } else 0f

        // 1. Sombra base externa escura + contorno escuro ao redor do anel prateado
        drawScope.drawOval(
            color = Color(0xFF0A0711),
            topLeft = Offset(targetPos.x - rxTarget * 1.06f, targetPos.y - ryTarget * 1.06f + 2.5f),
            size = Size(rxTarget * 2.12f, ryTarget * 2.12f)
        )

        if (isPressed || flashProgress > 0f) {
            drawScope.drawOval(
                color = color.copy(alpha = (0.45f + flashProgress * 0.45f).coerceAtMost(0.9f)),
                topLeft = Offset(targetPos.x - rxTarget * 1.24f, targetPos.y - ryTarget * 1.24f),
                size = Size(rxTarget * 2.48f, ryTarget * 2.48f)
            )
            if (isPressed) {
                drawFretFlameBurst(drawScope, targetPos, rxTarget, color, timeSec + lane)
            }
            if (flashProgress > 0f) {
                drawPickStrumArcBurst(drawScope, targetPos, rxTarget, color, flashProgress)
            }
        }

        // 2. Poço interno preto/grafite profundo dentro do aro metálico externo
        drawScope.drawOval(
            color = Color(0xFF14111B),
            topLeft = Offset(targetPos.x - rxTarget, targetPos.y - ryTarget),
            size = Size(rxTarget * 2f, ryTarget * 2f)
        )
        // Degraus sutis concêntricos dentro do poço escuro (efeito usinado)
        drawScope.drawOval(
            color = Color(0xFF2B2736),
            topLeft = Offset(targetPos.x - rxTarget * 0.87f, targetPos.y - ryTarget * 0.87f),
            size = Size(rxTarget * 1.74f, ryTarget * 1.74f),
            style = Stroke(width = 1.6f)
        )
        drawScope.drawOval(
            color = Color(0xFF201C2B),
            topLeft = Offset(targetPos.x - rxTarget * 0.74f, targetPos.y - ryTarget * 0.74f),
            size = Size(rxTarget * 1.48f, ryTarget * 1.48f),
            style = Stroke(width = 1.2f)
        )

        // 3. Anel externo metálico prateado/branco com contorno escuro interno para alto contraste
        val outerRingW = ((rxTarget * 0.125f).coerceIn(3.5f, 6.2f)) + if (isPressed) 1.0f else 0f
        drawScope.drawOval(
            color = Color(0xFF0D0A14),
            topLeft = Offset(targetPos.x - rxTarget * 0.94f, targetPos.y - ryTarget * 0.94f),
            size = Size(rxTarget * 1.88f, ryTarget * 1.88f),
            style = Stroke(width = outerRingW * 0.85f)
        )
        drawScope.drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF7F9FC), Color(0xFFD6DCE6), Color(0xFFA0A8B6)),
                startY = targetPos.y - ryTarget,
                endY = targetPos.y + ryTarget
            ),
            topLeft = Offset(targetPos.x - rxTarget, targetPos.y - ryTarget),
            size = Size(rxTarget * 2f, ryTarget * 2f),
            style = Stroke(width = outerRingW)
        )

        // 4. Sombra ao redor da gema central + domo interno colorido luminoso (57% do raio externo)
        val innerRx = rxTarget * 0.57f
        val innerRy = ryTarget * 0.57f
        drawScope.drawOval(
            color = Color(0xFF09070F),
            topLeft = Offset(targetPos.x - innerRx * 1.07f, targetPos.y - innerRy * 1.07f),
            size = Size(innerRx * 2.14f, innerRy * 2.14f)
        )
        drawScope.drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isPressed) 0.98f else 0.72f),
                    color.copy(alpha = if (isPressed) 1.0f else 0.94f),
                    color.copy(alpha = if (isPressed) 0.76f else 0.42f)
                ),
                center = Offset(targetPos.x, targetPos.y - innerRy * 0.08f),
                radius = innerRx * 0.98f
            ),
            topLeft = Offset(targetPos.x - innerRx, targetPos.y - innerRy),
            size = Size(innerRx * 2f, innerRy * 2f)
        )

        // 5. Aro interno prateado/branco contornando a gema colorida
        drawScope.drawOval(
            color = if (isPressed) Color.White else Color(0xFFEAEEF5),
            topLeft = Offset(targetPos.x - innerRx * 1.02f, targetPos.y - innerRy * 1.02f),
            size = Size(innerRx * 2.04f, innerRy * 2.04f),
            style = Stroke(width = ((rxTarget * 0.055f).coerceIn(1.7f, 2.9f)) + if (isPressed) 0.7f else 0f)
        )

        // 6. Brilho especular suave + ponto branco nítido no topo da gema
        drawScope.drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = if (isPressed) 1.0f else 0.92f), Color.Transparent),
                center = Offset(targetPos.x, targetPos.y - innerRy * 0.14f),
                radius = innerRx * 0.45f
            ),
            topLeft = Offset(targetPos.x - innerRx * 0.44f, targetPos.y - innerRy * 0.56f),
            size = Size(innerRx * 0.88f, innerRy * 0.88f)
        )
        drawScope.drawOval(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(targetPos.x - innerRx * 0.18f, targetPos.y - innerRy * 0.28f),
            size = Size(innerRx * 0.36f, innerRy * 0.32f)
        )
    }

    // Notas 3D (Discos Chanfrados, Estrelas Star Power & Sustains Contínuos/Quebrados)
    for (note in notes) {
        if (note.isMissed) continue
        if (note.lane >= numLanes) continue

        val noteEndTime = note.timeMs + note.durationMs
        val isActiveSustainNote = note.isHit && note.durationMs > 50L && !note.sustainCompleted && noteEndTime > currentTimeMs
        if (note.isHit && !isActiveSustainNote) continue

        val timeUntilHit = note.timeMs - currentTimeMs
        if (isActiveSustainNote || timeUntilHit in -250L..lookAheadMs) {
            val depth = if (isActiveSustainNote) {
                1.0f
            } else {
                (1.0f - (timeUntilHit.toFloat() / lookAheadMs.toFloat())).coerceIn(0f, 1.15f)
            }
            val curved = depth.toDouble().pow(1.65).toFloat()
            val notePos = get3dPosition(note.lane, depth)
            val laneW = (topWidth + (bottomWidth - topWidth) * curved) / numLanes
            val rx = (laneW * 0.34f).coerceAtLeast(6.5f)
            val ry = rx * 0.62f

            if (note.durationMs > 50L) {
                val tailEndDepth = (1.0f - ((timeUntilHit + note.durationMs).toFloat() / lookAheadMs.toFloat())).coerceIn(0f, 1.0f)
                val tailEndPos = get3dPosition(note.lane, tailEndDepth)
                val useStarColor = note.isStarPower || isStarPowerActive
                val baseSustainColor = if (useStarColor) Color(0xFF00E5FF) else LaneColors[note.lane]

                if (note.sustainBroken) {
                    // Cauda interrompida/opaca ao soltar cedo
                    drawScope.drawLine(
                        color = Color(0xFF6B657A).copy(alpha = 0.55f),
                        start = notePos,
                        end = tailEndPos,
                        strokeWidth = (rx * 0.32f).coerceAtLeast(3f)
                    )
                } else {
                    val activeBoost = if (isActiveSustainNote) 1.25f else 1.0f
                    drawScope.drawLine(
                        color = baseSustainColor.copy(alpha = if (isActiveSustainNote) 0.65f else 0.45f),
                        start = notePos,
                        end = tailEndPos,
                        strokeWidth = (rx * 0.76f * activeBoost).coerceAtLeast(6f)
                    )
                    drawScope.drawLine(
                        color = baseSustainColor.copy(alpha = 0.96f),
                        start = notePos,
                        end = tailEndPos,
                        strokeWidth = (rx * 0.40f * activeBoost).coerceAtLeast(3.2f)
                    )
                    drawScope.drawLine(
                        color = Color.White.copy(alpha = if (isActiveSustainNote) 0.95f else 0.75f),
                        start = notePos,
                        end = tailEndPos,
                        strokeWidth = (rx * 0.16f * activeBoost).coerceAtLeast(1.8f)
                    )
                    if (isActiveSustainNote) {
                        val pulseR = rx * (0.65f + 0.18f * sin(timeSec * 22f))
                        drawScope.drawCircle(
                            color = baseSustainColor.copy(alpha = 0.55f),
                            radius = pulseR,
                            center = notePos
                        )
                        drawScope.drawCircle(
                            color = Color.White.copy(alpha = 0.9f),
                            radius = pulseR * 0.42f,
                            center = notePos
                        )
                    }
                }
            }

            if (!isActiveSustainNote) {
                if (note.isStarPower) {
                    drawStarPowerNote3D(drawScope, notePos, rx, ry, isStarPowerActive)
                } else {
                    val gemColor = if (isStarPowerActive) Color(0xFF00E5FF) else LaneColors[note.lane]
                    // Pedestal escuro 3D chanfrado da nota
                    drawScope.drawOval(
                        color = Color(0xFF0A0710),
                        topLeft = Offset(notePos.x - rx * 1.08f, notePos.y - ry * 0.94f + 3.5f * curved),
                        size = Size(rx * 2.16f, ry * 2.02f)
                    )
                    // Anel colorido principal
                    drawScope.drawOval(
                        color = gemColor,
                        topLeft = Offset(notePos.x - rx, notePos.y - ry),
                        size = Size(rx * 2f, ry * 2f)
                    )
                    // Borda metálica branca externa
                    drawScope.drawOval(
                        color = Color.White.copy(alpha = 0.94f),
                        topLeft = Offset(notePos.x - rx, notePos.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(width = (rx * 0.14f).coerceAtLeast(1.8f))
                    )
                    // Domo interno preto + topo branco brilhante fiel à referência
                    drawScope.drawOval(
                        color = Color(0xFF17131E),
                        topLeft = Offset(notePos.x - rx * 0.56f, notePos.y - ry * 0.54f),
                        size = Size(rx * 1.12f, ry * 1.08f)
                    )
                    drawScope.drawOval(
                        color = Color.White,
                        topLeft = Offset(notePos.x - rx * 0.34f, notePos.y - ry * 0.36f),
                        size = Size(rx * 0.68f, ry * 0.60f)
                    )
                }
            }
        }
    }
}

private fun drawPickStrumArcBurst(
    drawScope: DrawScope,
    targetPos: Offset,
    rx: Float,
    color: Color,
    progress01: Float
) {
    val expand = 1f + (1f - progress01) * 0.75f
    val arcR = rx * expand
    drawScope.drawOval(
        color = Color.White.copy(alpha = progress01 * 0.85f),
        topLeft = Offset(targetPos.x - arcR, targetPos.y - arcR * 0.78f),
        size = Size(arcR * 2f, arcR * 1.56f),
        style = Stroke(width = (4.5f * progress01).coerceAtLeast(1.5f))
    )
    // Traço estilizado da palhetada atravessando o receptor
    val sweepW = rx * 1.55f * (0.6f + 0.4f * (1f - progress01))
    drawScope.drawLine(
        color = color.copy(alpha = progress01),
        start = Offset(targetPos.x - sweepW, targetPos.y + rx * 0.25f),
        end = Offset(targetPos.x + sweepW, targetPos.y - rx * 0.25f),
        strokeWidth = (5.0f * progress01).coerceAtLeast(1.5f)
    )
    drawScope.drawLine(
        color = Color.White.copy(alpha = progress01 * 0.9f),
        start = Offset(targetPos.x - sweepW * 0.7f, targetPos.y + rx * 0.18f),
        end = Offset(targetPos.x + sweepW * 0.7f, targetPos.y - rx * 0.18f),
        strokeWidth = (2.5f * progress01).coerceAtLeast(1.0f)
    )
}

private fun drawStarPowerNote3D(
    drawScope: DrawScope,
    center: Offset,
    rx: Float,
    ry: Float,
    isStarPowerActive: Boolean
) {
    val starColor = if (isStarPowerActive) Color(0xFFFFFFFF) else Color(0xFF00E5FF)
    drawScope.drawCircle(
        color = Color(0x5500E5FF),
        radius = rx * 1.35f,
        center = center
    )
    val starPath = Path()
    val outerR = rx * 1.15f
    val innerR = rx * 0.50f
    for (i in 0 until 10) {
        val angle = -Math.PI / 2.0 + i * (Math.PI / 5.0)
        val rad = if (i % 2 == 0) outerR else innerR
        val px = center.x + (cos(angle) * rad).toFloat()
        val py = center.y + (sin(angle) * rad * (ry / rx).coerceAtLeast(0.6f)).toFloat()
        if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
    }
    starPath.close()
    drawScope.drawPath(starPath, color = Color(0xFF06253A), style = Stroke(width = 4.5f))
    drawScope.drawPath(starPath, color = starColor, style = Fill)
    drawScope.drawPath(starPath, color = Color.White, style = Stroke(width = 1.8f))
    drawScope.drawCircle(color = Color.White, radius = rx * 0.24f, center = center)
}

private fun drawFretFlameBurst(
    drawScope: DrawScope,
    targetPos: Offset,
    rx: Float,
    color: Color,
    phase: Float
) {
    val flicker = sin(phase * 18f) * (rx * 0.14f)
    val flameH = rx * 1.55f + flicker
    val outerFlame = Path().apply {
        moveTo(targetPos.x - rx * 0.65f, targetPos.y - rx * 0.12f)
        lineTo(targetPos.x - rx * 0.35f, targetPos.y - flameH * 0.72f)
        lineTo(targetPos.x, targetPos.y - flameH)
        lineTo(targetPos.x + rx * 0.35f, targetPos.y - flameH * 0.68f)
        lineTo(targetPos.x + rx * 0.65f, targetPos.y - rx * 0.12f)
        close()
    }
    drawScope.drawPath(outerFlame, color = color.copy(alpha = 0.72f), style = Fill)

    val coreFlame = Path().apply {
        moveTo(targetPos.x - rx * 0.34f, targetPos.y - rx * 0.10f)
        lineTo(targetPos.x, targetPos.y - flameH * 0.78f)
        lineTo(targetPos.x + rx * 0.34f, targetPos.y - rx * 0.10f)
        close()
    }
    drawScope.drawPath(coreFlame, color = Color(0xFFFFF59D).copy(alpha = 0.90f), style = Fill)
}

/**
 * Desenha uma prévia compacta da Esteira Fixa (sem gameplay) para o Estúdio de Customização.
 */
fun drawHighwayStudioPreview(
    drawScope: DrawScope,
    width: Float,
    height: Float,
    highwayTheme: HighwayTheme,
    overlayBitmap: ImageBitmap?,
    overlayOpacity: Float,
    difficulty: Difficulty = Difficulty.MEDIUM
) {
    val numLanes = difficulty.lanesUsed
    val horizonY = height * 0.10f
    val strikeY = height * 0.88f
    val topW = width * 0.42f
    val botW = width * 0.88f
    val cx = width * 0.5f

    val path = Path().apply {
        moveTo(cx - topW * 0.5f, horizonY)
        lineTo(cx + topW * 0.5f, horizonY)
        lineTo(cx + botW * 0.5f, strikeY)
        lineTo(cx - botW * 0.5f, strikeY)
        close()
    }
    drawScope.drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = highwayTheme.surfaceGradientHex.map { Color(it) },
            startY = horizonY,
            endY = strikeY
        )
    )
    if (overlayBitmap != null) {
        drawScope.clipPath(path) {
            drawImage(
                image = overlayBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(overlayBitmap.width, overlayBitmap.height),
                dstOffset = IntOffset((cx - botW * 0.5f).toInt(), horizonY.toInt()),
                dstSize = IntSize(botW.toInt().coerceAtLeast(1), (strikeY - horizonY).toInt().coerceAtLeast(1)),
                alpha = overlayOpacity.coerceIn(0.15f, 0.90f)
            )
            drawRect(Color.Black.copy(alpha = 0.25f))
        }
    }
    val railColor = Color(highwayTheme.primaryColorHex)
    drawScope.drawLine(railColor, Offset(cx - topW * 0.5f, horizonY), Offset(cx - botW * 0.5f, strikeY), strokeWidth = 5f)
    drawScope.drawLine(railColor, Offset(cx + topW * 0.5f, horizonY), Offset(cx + botW * 0.5f, strikeY), strokeWidth = 5f)
    for (i in 1 until numLanes) {
        val frac = i / numLanes.toFloat()
        drawScope.drawLine(
            Color.White.copy(alpha = 0.25f),
            Offset((cx - topW * 0.5f) + topW * frac, horizonY),
            Offset((cx - botW * 0.5f) + botW * frac, strikeY),
            strokeWidth = 1.5f
        )
    }
    val r = botW * (0.28f / numLanes)
    for (lane in 0 until numLanes - 1) {
        val x1 = (cx - botW * 0.5f) + (botW / numLanes) * (lane + 0.5f)
        val x2 = (cx - botW * 0.5f) + (botW / numLanes) * (lane + 1.5f)
        drawScope.drawLine(Color(0xFFDDE2EB).copy(alpha = 0.8f), Offset(x1 + r, strikeY - 12f), Offset(x2 - r, strikeY - 12f), strokeWidth = 2.5f)
    }
    for (lane in 0 until numLanes) {
        val x = (cx - botW * 0.5f) + (botW / numLanes) * (lane + 0.5f)
        drawScope.drawOval(
            color = LaneColors[lane],
            topLeft = Offset(x - r, strikeY - 12f - r * 0.78f),
            size = Size(r * 2f, r * 1.56f),
            style = Stroke(width = 4f)
        )
    }
}

private fun drawStageEnvironment(
    drawScope: DrawScope,
    width: Float,
    height: Float,
    currentTimeMs: Long,
    isStarPower: Boolean,
    rockMeter: Float,
    combo: Int,
    instrument: Instrument,
    playerStats: PlayerStats,
    highwayTheme: HighwayTheme,
    horizonY: Float,
    centerX: Float
) {
    val timeSec = currentTimeMs / 1000f

    // 0. Elementos Temáticos de Palco (Synthwave Sunset, Inferno Pyro, Catedral Gótica ou Club PS2)
    when (highwayTheme) {
        HighwayTheme.NEON_CYBER_OVERDRIVE -> {
            // Sol Synthwave atrás do horizonte da esteira + montanhas wireframe + grade laser
            val sunR = minOf(width, height) * 0.24f
            drawScope.drawCircle(
                brush = Brush.verticalGradient(listOf(Color(0xFFFFD54F), Color(0xFFFF6D00), Color(0xFFFF1744))),
                radius = sunR,
                center = Offset(centerX, horizonY + sunR * 0.35f)
            )
            // Linhas horizontais retro-grid no chão
            for (g in 0..5) {
                val gy = height * (0.42f + g * 0.10f)
                drawScope.drawLine(Color(0x5500E5FF), Offset(0f, gy), Offset(width, gy), strokeWidth = 1.5f)
            }
            // Montanhas wireframe ciano
            val mPath = Path().apply {
                moveTo(0f, height * 0.42f)
                lineTo(width * 0.14f, height * 0.22f)
                lineTo(width * 0.28f, height * 0.39f)
                lineTo(width * 0.72f, height * 0.38f)
                lineTo(width * 0.86f, height * 0.20f)
                lineTo(width, height * 0.42f)
            }
            drawScope.drawPath(mPath, color = Color(0xFF00E5FF).copy(alpha = 0.55f), style = Stroke(width = 2.2f))
        }
        HighwayTheme.FIRE_AND_BRIMSTONE -> {
            // Pilares de fogo pirotécnicos ao fundo do palco (como Arena Inferno)
            listOf(width * 0.28f, width * 0.72f).forEachIndexed { idx, fx ->
                val pulseH = height * (0.28f + 0.08f * sin(timeSec * 7f + idx * 1.3f))
                drawScope.drawOval(
                    brush = Brush.verticalGradient(listOf(Color(0xAAFFEA00), Color(0xAAFF3D00), Color.Transparent)),
                    topLeft = Offset(fx - 26f, height * 0.55f - pulseH),
                    size = Size(52f, pulseH)
                )
            }
        }
        HighwayTheme.GALACTIC_STAR -> {
            // Rosácea de vitral / catedral de arena atrás do centro
            val roseCenter = Offset(width * 0.82f, height * 0.34f)
            val roseR = minOf(width, height) * 0.16f
            drawScope.drawCircle(
                brush = Brush.radialGradient(listOf(Color(0x66FF4081), Color(0x447C4DFF), Color.Transparent)),
                radius = roseR,
                center = roseCenter
            )
            drawScope.drawCircle(color = Color(0x55CE93D8), radius = roseR, center = roseCenter, style = Stroke(width = 2.5f))
        }
        HighwayTheme.PS2_WOOD_STEEL -> {
            // Placa luminosa de clube rock clássico no fundo esquerdo/direito
            drawScope.drawRoundRect(
                color = Color(0x33FF1744),
                topLeft = Offset(width * 0.78f, height * 0.16f),
                size = Size(width * 0.14f, height * 0.09f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )
            drawScope.drawRoundRect(
                color = Color(0xAAFF2A55),
                topLeft = Offset(width * 0.78f, height * 0.16f),
                size = Size(width * 0.14f, height * 0.09f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
                style = Stroke(width = 2.2f)
            )
        }
    }

    // 1. Treliça superior de alumínio do palco
    drawScope.drawLine(
        color = Color(0xFF2D2A3A),
        start = Offset(0f, 18f),
        end = Offset(width, 18f),
        strokeWidth = 8f
    )
    for (x in 0..(width.toInt()) step 40) {
        drawScope.drawLine(
            color = Color(0xFF1F1D2A),
            start = Offset(x.toFloat(), 14f),
            end = Offset(x.toFloat() + 20f, 32f),
            strokeWidth = 2.5f
        )
    }

    // 2. Refletores Volumétricos Dinâmicos
    val spotlightColors = if (isStarPower) {
        listOf(Color(0x5500E5FF), Color(0x6600B0FF), Color(0x5580D8FF), Color(0x6600E5FF))
    } else {
        listOf(
            Color(0x44FF1744),
            Color(0x44FFEA00),
            Color(0x447C4DFF),
            Color(0x4400E676)
        )
    }

    val spotPositions = listOf(width * 0.14f, width * 0.34f, width * 0.66f, width * 0.86f)
    for (i in spotPositions.indices) {
        val spotX = spotPositions[i]
        val swing = sin(timeSec * 1.8f + i * 1.2f) * (width * 0.10f)
        val beamPath = Path().apply {
            moveTo(spotX, 22f)
            lineTo(spotX + swing - 60f, height * 0.90f)
            lineTo(spotX + swing + 60f, height * 0.90f)
            close()
        }
        drawScope.drawPath(
            path = beamPath,
            brush = Brush.verticalGradient(
                colors = listOf(spotlightColors[i], Color.Transparent),
                startY = 20f,
                endY = height * 0.90f
            ),
            style = Fill
        )
    }

    // 3. Torres de Amplificadores Clássicos nos dois lados do palco
    val bassPulse = (sin(timeSec * 8f) * 3.5f).coerceAtLeast(0f)
    listOf(width * 0.03f, width * 0.87f).forEach { ampLeft ->
        val ampTop = height * 0.32f
        val ampWidth = width * 0.10f
        val ampHeight = height * 0.40f
        drawScope.drawRect(color = Color(0xFF15121B), topLeft = Offset(ampLeft, ampTop), size = Size(ampWidth, ampHeight))
        drawScope.drawRect(
            color = Color(highwayTheme.primaryColorHex).copy(alpha = 0.55f),
            topLeft = Offset(ampLeft, ampTop),
            size = Size(ampWidth, ampHeight),
            style = Stroke(width = 2.2f)
        )
        val speakerRadius = ampWidth * 0.17f
        for (r in 0..1) {
            for (c in 0..1) {
                val cx = ampLeft + ampWidth * (0.28f + c * 0.44f)
                val cy = ampTop + ampHeight * (0.28f + r * 0.44f)
                drawScope.drawCircle(color = Color(0xFF0D0C10), radius = speakerRadius + bassPulse, center = Offset(cx, cy))
                drawScope.drawCircle(
                    color = if (highwayTheme == HighwayTheme.FIRE_AND_BRIMSTONE) Color(0xFF9E1B1B) else Color(0xFF34313F),
                    radius = (speakerRadius + bassPulse) * 0.48f,
                    center = Offset(cx, cy)
                )
            }
        }
    }

    // 4. Banda no Palco: Baterista + Baixista à direita + Músico Principal 3D à esquerda
    drawStageBandCompanions(
        drawScope = drawScope,
        width = width,
        height = height,
        timeSec = timeSec,
        isStarPowerActive = isStarPower,
        primaryInstrument = instrument,
        stats = playerStats
    )

    val rockerX = width * 0.22f
    val rockerBaseY = height * 0.79f
    val rockerScale = (height * 0.31f).coerceIn(85f, 210f)
    val idleYaw = 22f + sin(timeSec * 1.4f) * 8f

    drawRockerCharacter3D(
        drawScope = drawScope,
        stats = playerStats,
        instrument = instrument,
        centerX = rockerX,
        baseY = rockerBaseY,
        scale = rockerScale,
        yawDeg = idleYaw,
        pitchDeg = 4f,
        animTimeSec = timeSec,
        isStarPowerActive = isStarPower,
        combo = combo
    )

    // 5. Silhueta da Plateia Reativa na Base do Palco (vibra no Star Power / combo, retrai em risco de vaia)
    val highEnergy = isStarPower || combo >= 20 || rockMeter > 0.72f
    val crowdDanger = rockMeter < 0.30f
    val crowdEdgeColor = when {
        isStarPower -> Color(0x4400E5FF)
        highEnergy -> Color(0x33FFD54F)
        crowdDanger -> Color(0x44FF1744)
        else -> Color.Transparent
    }
    if (crowdEdgeColor != Color.Transparent) {
        drawScope.drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, crowdEdgeColor)),
            topLeft = Offset(0f, height - 72f),
            size = Size(width, 72f)
        )
    }

    // Plateia em silhuetas individuais: movimento discreto e dessincronizado,
    // sem a coreografia repetitiva de braços levantados.
    val crowdColor = Color(0xEE090510)
    val crowdCount = (width / 24f).toInt().coerceIn(12, 64)
    val crowdStep = width / crowdCount
    for (index in 0 until crowdCount) {
        val phase = index * 1.731f
        val bob = sin(timeSec * (1.25f + (index % 4) * 0.13f) + phase) * 2.2f
        val headRadius = 4.2f + (index % 3) * 0.8f
        val personX = (index + 0.5f) * crowdStep
        val shoulderY = height - 13f + bob
        val torsoWidth = 12f + (index % 4) * 1.8f
        val torsoHeight = 19f + (index % 3) * 3f

        // Tronco e ombros com pequenas variações individuais de altura/largura.
        drawScope.drawRoundRect(
            color = crowdColor,
            topLeft = Offset(personX - torsoWidth * 0.5f, shoulderY),
            size = Size(torsoWidth, torsoHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(torsoWidth * 0.42f, torsoWidth * 0.42f)
        )
        drawScope.drawCircle(
            color = crowdColor,
            radius = headRadius,
            center = Offset(personX + sin(phase) * 1.5f, shoulderY - headRadius * 1.45f)
        )
    }
}
