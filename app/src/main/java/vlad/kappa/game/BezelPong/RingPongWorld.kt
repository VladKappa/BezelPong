package vlad.kappa.game.BezelPong

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import kotlin.math.*
import kotlin.random.Random

class RingPongWorld(w: Float, h: Float) {

    // Allow exactly 1 wall bounce after each paddle hit
    private var wallBouncesLeft: Int = 0

    // Speed multiplier that increases on wall bounces
    private var speedMul: Float = 1.0f

    private var hitThisFrame = 0
    private var missedThisFrame = false

    // UI helpers
    val dangerRadius: Float get() = outerWallRadius
    val wallBounceAvailable: Boolean get() = wallBouncesLeft > 0


    fun consumeHits(): Int = hitThisFrame.also { hitThisFrame = 0 }
    fun consumeMiss(): Boolean = missedThisFrame.also { missedThisFrame = false }

    fun resetBallServe() {
        ballPos = center
        ballPrevPos = ballPos
        val speed = minSide * 0.55f * speedMul
        val ang = paddleAngle
        val dir = Offset(cos(ang), sin(ang)) // outward from paddle
        var v = dir * speed

        // Add a small tangential component so it never starts perfectly straight.
        val nOut = dir / dir.len().coerceAtLeast(1e-6f)
        v = ensureAngle(v, nOut, minTangentialRatio = 0.18f)

        ballVel = v
        wallBouncesLeft = 0

    }


    fun resetAll() {
        paddleAngle = (PI.toFloat() / 2f)
        paddleAngVel = 0f
        wallBouncesLeft = 0
        speedMul = 1.0f
        resetBallServe()
    }

    private var lastDbgMs: Long = 0L
    private fun dbg(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDbgMs > 700) { // at most once every 0.7s
            Log.d("PONG", msg)
            lastDbgMs = now
        }
    }

    // Screen / arena
    val center = Offset(w / 2f, h / 2f)
    private val minSide = min(w, h)

    // Ring where paddle sits
    val ringRadius = minSide * 0.38f
    val paddleThickness = minSide * 0.07f
    val paddleHalfWidth = (18f * PI.toFloat() / 180f) // 36 deg total arc

    // Ball
    val ballRadius = minSide * 0.02f
    var ballPos: Offset = center
        private set
    var ballPrevPos: Offset = ballPos
        private set

    // Paddle rotation
    var paddleAngle: Float = (PI.toFloat() / 2f) // 90 deg => bottom
        private set

    private var ballVel: Offset = run {
        val speed = minSide * 0.55f
        val dir = Offset(cos(paddleAngle), sin(paddleAngle)) // Y down coords
        dir * speed
    }



    // For tangential "spin" feel when rotating platform
    private var paddleAngVel: Float = 0f

    // Optional: keep ball in an outer circle boundary too
    private val outerWallRadius = minSide * 0.48f

    fun onRotary(deltaRad: Float) {
        paddleAngle = wrapAngle(paddleAngle + deltaRad)

        // rough angular velocity estimate (feel)
        paddleAngVel = (paddleAngVel + deltaRad * 60f).coerceIn(-10f, 10f)
    }

    private fun bounceOuterWall() {
        val rel = ballPos - center
        val r = rel.len()
        val maxR = outerWallRadius - ballRadius
        if (r > maxR) {
            val n = rel / r
            // push ball back inside
            ballPos = center + n * maxR
            // reflect velocity off the radial normal
            ballVel = reflect(ballVel, n)
        }
    }

    private fun ensureAngle(v: Offset, nOut: Offset, minTangentialRatio: Float): Offset {
        // Decompose velocity into radial (along nOut) and tangential components.
        val tangent = Offset(-nOut.y, nOut.x)
        val vTan = v dot tangent
        val speed = v.len().coerceAtLeast(1f)

        // If tangential component is too small, inject some tangent.
        val minTan = speed * minTangentialRatio
        if (abs(vTan) >= minTan) return v

        val sign = if (vTan >= 0f) 1f else -1f
        val v2 = v + tangent * (sign * (minTan - abs(vTan)))

        // keep total speed same as before
        val s2 = v2.len().coerceAtLeast(1f)
        return v2 * (speed / s2)
    }

    fun update(dt: Float) {
        ballPrevPos = ballPos

        val proposedPos = ballPos + ballVel * dt
        val hit = bouncePaddleSwept(ballPrevPos, proposedPos)

        if (hit) {
            hitThisFrame++
        } else {
            ballPos = proposedPos

            val r = (ballPos - center).len()
            val maxR = outerWallRadius - ballRadius

            if (r > maxR) {
                if (wallBouncesLeft > 0) {
                    wallBouncesLeft-- // consume the credit

                    // Do the bounce (push inside + reflect)
                    bounceOuterWall()

                    // Speed up slightly on every wall hit
                    speedMul = (speedMul * 1.03f).coerceAtMost(2.0f)

                    // Re-normalize velocity to new speed
                    val desired = minSide * 0.55f * speedMul
                    val vLen = ballVel.len().coerceAtLeast(1f)
                    ballVel = ballVel * (desired / vLen)

                    Log.d("PONG", "WALL HIT speedMul=$speedMul bouncesLeft=$wallBouncesLeft")
                } else {
                    missedThisFrame = true
                    resetBallServe()
                }
            }
        }

        paddleAngVel *= 0.86f
    }

    private fun bouncePaddleSwept(prev: Offset, curr: Offset): Boolean {
        // Work in center-relative coords
        val p0 = prev - center
        val p1 = curr - center
        val d = p1 - p0

        val inner = ringRadius - paddleThickness / 2f
        val outer = ringRadius + paddleThickness / 2f

        // Test both surfaces (inflated/deflated by ball radius)
        val surfaces = arrayOf(
            HitSurface(r = outer + ballRadius, isOuter = true),
            HitSurface(r = inner - ballRadius, isOuter = false)
        )

        var bestHit: Hit? = null
        for (s in surfaces) {
            val hit = segmentCircleHit(p0, d, s.r) ?: continue
            val tagged = hit.copy(isOuter = s.isOuter)
            if (bestHit == null || tagged.t < bestHit!!.t) bestHit = tagged
        }

        val h = bestHit ?: return false

        val hitRel = h.pos
        val hitLen = h.posLen

        // Angle in screen coords (Y down): atan2(y,x) matches drawArc clockwise
        val hitAng = wrapAngle(atan2(hitRel.y, hitRel.x))

        val dAng = angleDelta(paddleAngle, hitAng)
        val edgeMargin = (4f * PI.toFloat() / 180f) // 4 degrees of forgiveness (tune 2..6)
        val angPad = asin((ballRadius / hitLen).coerceIn(0f, 0.9f))
        if (abs(dAng) > (paddleHalfWidth + edgeMargin + angPad)) {
            dbg("MISS dAng=$dAng hitAng=$hitAng paddle=$paddleAngle")
            return false
        }

        // Outward radial at impact
        val nOut = hitRel / hitLen

        // Normal for reflection:
        // - If we hit the OUTER surface, the normal points inward (toward center)
        // - If we hit the INNER surface, the normal points outward
        val n = if (h.isOuter) Offset(-nOut.x, -nOut.y) else nOut

        val vDotN = ballVel dot n
        if (vDotN <= 0f) return false

        // Place ball slightly away from the contacted surface to avoid re-hits
        val separateR = if (h.isOuter) (outer + ballRadius - 0.5f) else (inner - ballRadius + 0.5f)
        ballPos = center + nOut * separateR

        // Reflect velocity
        var newV = reflect(ballVel, n)

        // Spin/tangential kick based on outward radial
        val tangent = Offset(-nOut.y, nOut.x)

        // 1) Stronger kick from bezel rotation (spin)
        val spinKick = 0.28f
        val tangentialSpeed = paddleAngVel * ringRadius
        newV += tangent * (tangentialSpeed * spinKick)

        // 2) "English" based on where on the paddle you hit
        val maxEnglish = 0.55f
        val hitNorm = (dAng / (paddleHalfWidth + (4f * PI.toFloat() / 180f))).coerceIn(-1f, 1f)
        newV += tangent * (hitNorm * maxEnglish * newV.len())

        // Prevent boring straight/radial returns (anti-infinite farm).
        newV = ensureAngle(newV, nOut, minTangentialRatio = 0.22f)

        // Add a tiny random angular jitter on paddle hits to break perfect back-and-forth loops.
        if (abs(hitNorm) < 0.6f) {
            val jitterDeg = 2.0f
            val jitter = (Random.nextFloat() * 2f - 1f) * (jitterDeg * PI.toFloat() / 180f)
            newV = rotate(newV, jitter)
        }

        // 3) Speed ramp after each hit
        val baseSpeed = minSide * 0.55f * speedMul
        val ramp = 1.10f
        val desiredSpeed = baseSpeed * ramp

        val speed = newV.len().coerceAtLeast(1f)
        ballVel = newV * (desiredSpeed / speed)


        // Keep ONE important log
        Log.d("PONG", "HIT side=${if (h.isOuter) "OUTER" else "INNER"} dAng=$dAng")

        wallBouncesLeft = 1
        return true
    }

    private data class HitSurface(val r: Float, val isOuter: Boolean)
    private data class Hit(val t: Float, val pos: Offset, val posLen: Float, val isOuter: Boolean)

    /**
     * Intersect segment p(t)=p0 + d*t (t in [0..1]) with circle |p|=R.
     * Returns earliest intersection in [0..1], or null.
     */
    private fun segmentCircleHit(p0: Offset, d: Offset, R: Float): Hit? {
        val a = d dot d
        if (a < 1e-8f) return null

        val b = 2f * (p0 dot d)
        val c = (p0 dot p0) - R * R

        val disc = b * b - 4f * a * c
        if (disc < 0f) return null

        val sqrtDisc = sqrt(disc)
        val t1 = (-b - sqrtDisc) / (2f * a)
        val t2 = (-b + sqrtDisc) / (2f * a)

        val t = when {
            t1 in 0f..1f -> t1
            t2 in 0f..1f -> t2
            else -> return null
        }

        val pos = p0 + d * t
        val len = pos.len().coerceAtLeast(1e-6f)
        return Hit(t = t, pos = pos, posLen = len, isOuter = true)
    }
}

/* ---------- Math helpers ---------- */

private operator fun Offset.plus(o: Offset) = Offset(x + o.x, y + o.y)
private operator fun Offset.minus(o: Offset) = Offset(x - o.x, y - o.y)
private operator fun Offset.times(s: Float) = Offset(x * s, y * s)
private operator fun Offset.div(s: Float) = Offset(x / s, y / s)

private fun Offset.len(): Float = sqrt(x * x + y * y)
private infix fun Offset.dot(o: Offset): Float = x * o.x + y * o.y

private fun reflect(v: Offset, nUnit: Offset): Offset {
    val k = 2f * (v dot nUnit)
    return Offset(v.x - k * nUnit.x, v.y - k * nUnit.y)
}

private fun wrapAngle(a: Float): Float {
    val twoPi = 2f * PI.toFloat()
    var x = a % twoPi
    if (x < 0f) x += twoPi
    return x
}

private fun angleDelta(source: Float, target: Float): Float {
    val twoPi = 2f * PI.toFloat()
    var d = (target - source) % twoPi
    if (d <= -PI.toFloat()) d += twoPi
    if (d > PI.toFloat()) d -= twoPi
    return d
}

private fun rotate(v: Offset, angle: Float): Offset {
    val c = cos(angle)
    val s = sin(angle)
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}
