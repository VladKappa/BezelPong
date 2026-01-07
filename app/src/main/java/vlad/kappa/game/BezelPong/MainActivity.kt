package vlad.kappa.game.BezelPong

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.foundation.CurvedAlignment
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class GamePhase { Start, Playing, GameOver }

class MainActivity : ComponentActivity() {

    // Tune sensitivity (smaller = slower rotation)
    @Volatile
    private var radiansPerStep = 0.3f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RingPongScreen() }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isRotary =
            (event.source and InputDevice.SOURCE_ROTARY_ENCODER) == InputDevice.SOURCE_ROTARY_ENCODER

        if (isRotary && event.action == MotionEvent.ACTION_SCROLL) {
            val steps = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (steps != 0f) Log.d("ROTARY", "steps=$steps")
            RotaryBus.push(steps, steps * radiansPerStep)
            return true
        }

        return super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun RingPongScreen() {
        val focusRequester = remember { FocusRequester() }
        val scope = rememberCoroutineScope()

        var redrawTick by remember { mutableIntStateOf(0) }
        var phase by remember { mutableStateOf(GamePhase.Start) }
        var score by remember { mutableIntStateOf(0) }

        // Settings UI state (no Material3).
        var showMenu by remember { mutableStateOf(false) }
        var sensitivityUi by remember { mutableFloatStateOf(radiansPerStep) }
        var smoothInput by remember { mutableStateOf(true) }

        val ctx = LocalContext.current
        var highScore by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            HighScoreStore.flow(ctx).collectLatest { highScore = it }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
        ) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            if (w <= 0f || h <= 0f) return@BoxWithConstraints

            val world = remember(w, h) { RingPongWorld(w, h) }

            LaunchedEffect(Unit) { world.resetAll() }

            // Game loop
            LaunchedEffect(world, phase) {
                var lastNanos = 0L
                var pendingDelta = 0f
                while (true) {
                    val frameNanos = awaitFrame()
                    if (lastNanos == 0L) lastNanos = frameNanos
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    lastNanos = frameNanos

                    val steps = RotaryBus.consumeSteps()
                    val delta = RotaryBus.consumeRadians()
                    if (delta != 0f) Log.d("ROTARY", "delta=$delta")
                    if (showMenu) {
                        pendingDelta = 0f
                        if (steps != 0f) {
                            val stepSize = 0.02f
                            val adj = when {
                                steps > 0f -> stepSize
                                steps < 0f -> -stepSize
                                else -> 0f
                            }
                            sensitivityUi = (sensitivityUi + adj).coerceIn(0.05f, 0.9f)
                            radiansPerStep = sensitivityUi
                        }
                    } else {
                        if (delta != 0f) pendingDelta += delta
                        if (pendingDelta != 0f) {
                            val applied = if (smoothInput) pendingDelta * 0.35f else pendingDelta
                            pendingDelta -= applied
                            world.onRotary(applied)
                        }
                    }

                    if (phase == GamePhase.Playing) {
                        world.update(dt)

                        val hits = world.consumeHits()
                        if (hits > 0) score += hits

                        if (world.consumeMiss()) {
                            HighScoreStore.setIfGreater(ctx, score)
                            phase = GamePhase.GameOver
                        }
                    }

                    redrawTick++ // forces redraw
                }
            }

            // TOP-LEVEL OVERLAY BOX:
            // Canvas has ONLY draw calls.
            // UI elements are OUTSIDE Canvas to avoid UiComposable warnings.
            Box(modifier = Modifier.fillMaxSize()) {

                // --------- DRAW ONLY ----------
                Canvas(modifier = Modifier.fillMaxSize()) {
                    redrawTick // depend on state

                    // Ring
                    drawCircle(
                        color = Color(0x33FFFFFF),
                        radius = world.ringRadius,
                        center = world.center,
                        style = Stroke(width = 1.5f)
                    )

                    val rect = Rect(
                        left = world.center.x - world.ringRadius,
                        top = world.center.y - world.ringRadius,
                        right = world.center.x + world.ringRadius,
                        bottom = world.center.y + world.ringRadius
                    )

                    // Paddle arc
                    drawArc(
                        color = Color.White,
                        startAngle = radToDeg(world.paddleAngle - world.paddleHalfWidth),
                        sweepAngle = radToDeg(2f * world.paddleHalfWidth),
                        useCenter = false,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = world.paddleThickness, cap = StrokeCap.Round)
                    )

                    // Ball
                    drawCircle(
                        color = Color.White,
                        radius = world.ballRadius,
                        center = world.ballPos
                    )

                    // Edge indicator ring (green = safe wall bounce available, red = death)
                    val edgeColor =
                        if (world.wallBounceAvailable) Color(0xAA00FF00) else Color(0xAAFF0000)

                    drawCircle(
                        color = edgeColor,
                        radius = world.dangerRadius,
                        center = world.center,
                        style = Stroke(width = 4f)
                    )
                }

                // --------- CURVED SCORE (UI, not inside Canvas) ----------
                CurvedLayout(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    anchor = 270f // top
                ) {
                    curvedRow(radialAlignment = CurvedAlignment.Radial.Outer) {
                        curvedText(
                            text = "Score: $score   Best: $highScore",
                            style = CurvedTextStyle(
                                TextStyle(
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            )
                        )
                    }
                }

                // --------- MENU BUTTON + MENU PANEL (only when not playing) ----------
                if (phase != GamePhase.Playing) {

                    // 1) The dim overlay + title/subtitle (tap to start)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xAA000000))
                            .pointerInput(phase, showMenu) {
                                detectTapGestures {
                                    // tap anywhere (except gear/menu) starts/restarts
                                    if (!showMenu) {
                                        score = 0
                                        world.resetAll()
                                        phase = GamePhase.Playing
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val title = when (phase) {
                            GamePhase.Start -> "Bezel Pong"
                            GamePhase.GameOver -> "Game Over"
                            else -> ""
                        }
                        val subtitle = when (phase) {
                            GamePhase.Start -> "Tap to start\nRotate bezel to move"
                            GamePhase.GameOver -> "Score: $score\nBest: $highScore\nTap to restart"
                            else -> ""
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, color = Color.White)
                            Text(subtitle, color = Color.White)
                        }

                        // 2) Gear button on top (higher zIndex + separate input)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, 28) } // slightly down from top; tune 20..60
                                .size(68.dp) // big tap target
                                .zIndex(10f) // above overlay contents
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        // Toggle menu; do not start the game.
                                        showMenu = !showMenu
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u2699", color = Color.White, fontSize = 34.sp)
                        }
                    }

                    // 3) Settings panel (separate layer above everything)
                    if (showMenu) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xCC000000))
                                .zIndex(20f) // above overlay + gear
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { showMenu = false })
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .pointerInput(Unit) { /* consume clicks inside panel */ }
                            ) {
                                Text("Settings", color = Color.White)

                                Text(
                                    "Sensitivity: ${"%.2f".format(sensitivityUi)}",
                                    color = Color.White,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                                )

                                Row {
                                    Box(
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .pointerInput(Unit) {
                                                detectTapGestures {
                                                    sensitivityUi =
                                                        (sensitivityUi - 0.02f).coerceAtLeast(0.05f)
                                                    radiansPerStep = sensitivityUi
                                                }
                                            }
                                    ) { Text("\u2212", color = Color.White, fontSize = 18.sp) }

                                    Box(
                                        modifier = Modifier
                                            .padding(6.dp)
                                            .pointerInput(Unit) {
                                                detectTapGestures {
                                                    sensitivityUi =
                                                        (sensitivityUi + 0.02f).coerceAtMost(0.9f)
                                                    radiansPerStep = sensitivityUi
                                                }
                                            }
                                    ) { Text("+", color = Color.White, fontSize = 18.sp) }
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures { smoothInput = !smoothInput }
                                        }
                                ) {
                                    val label = if (smoothInput) "ON" else "OFF"
                                    Text("Smoothing: $label", color = Color.White)
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures {
                                                scope.launch { HighScoreStore.reset(ctx) }
                                            }
                                        }
                                ) {
                                    Text("Reset High Score", color = Color.White)
                                }

                                Box(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures { showMenu = false }
                                        }
                                ) {
                                    Text("Close", color = Color.White)
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    object RotaryBus {
        var steps: Float = 0f
        var radians: Float = 0f

        fun push(steps: Float, radians: Float) {
            this.steps += steps
            this.radians += radians
        }

        fun consumeSteps(): Float = steps.also { steps = 0f }
        fun consumeRadians(): Float = radians.also { radians = 0f }
    }
}

private fun radToDeg(rad: Float): Float = (rad * (180f / Math.PI.toFloat()))
