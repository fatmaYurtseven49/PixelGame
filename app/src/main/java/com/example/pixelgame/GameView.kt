package com.example.pixelgame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Pixel Runner - Retro 2D Endless Runner Game.
 * Features:
 * - Character selection (Sevimli Tilki 🦊 & Yakışıklı Fiyonklu Tavşan 🐰)
 * - Collectible Gold Coins system (SharedPreferences persistence)
 * - Custom 8-bit PCM synth audio (Jump sweep, Coin arpeggio, Death crash)
 * - Day / Sunset / Night cycle with twinkling stars & pixel moon
 * - Custom virtual pixel canvas scaling with 60 FPS game loop
 * - Multiple obstacle types (Cactus variations & flying Pterodactyls)
 * - Particle system & screen shake effect
 * - High score & character preference persistence
 * - Retro UI panels & touch controls
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class ObstacleType {
        CACTUS_SMALL,
        CACTUS_TALL,
        CACTUS_DOUBLE,
        BIRD
    }

    private class Obstacle(
        var x: Float,
        var y: Float,
        val width: Int,
        val height: Int,
        val type: ObstacleType
    )

    private class Cloud(var x: Float, val y: Float, val speed: Float)

    private class Coin(var x: Float, var y: Float)

    private class Star(val x: Float, val y: Float, val twinkleOffset: Float)

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val color: Int,
        var life: Float,
        val maxLife: Float,
        val size: Float = 1f
    )

    private class RetroAudioSynth {
        private val sampleRate = 22050

        fun playJump() {
            Thread {
                try {
                    val duration = 0.08f
                    val numSamples = (sampleRate * duration).toInt()
                    val buffer = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val t = i.toFloat() / numSamples
                        val freq = 180f + t * 450f // Zıplama frekans yükselişi
                        val period = (sampleRate / freq).toInt().coerceAtLeast(1)
                        val sample = if ((i % period) < (period / 2)) 7000 else -7000
                        buffer[i] = (sample * (1f - t * 0.4f)).toInt().toShort()
                    }
                    playStaticBuffer(buffer)
                } catch (_: Exception) {
                }
            }.start()
        }

        fun playCoin() {
            Thread {
                try {
                    val duration = 0.12f
                    val numSamples = (sampleRate * duration).toInt()
                    val buffer = ShortArray(numSamples)
                    val half = numSamples / 2
                    for (i in 0 until numSamples) {
                        val freq = if (i < half) 987f else 1318f // B5 -> E6 arpej
                        val period = (sampleRate / freq).toInt().coerceAtLeast(1)
                        val sample = if ((i % period) < (period / 2)) 8500 else -8500
                        buffer[i] = sample.toShort()
                    }
                    playStaticBuffer(buffer)
                } catch (_: Exception) {
                }
            }.start()
        }

        fun playDie() {
            Thread {
                try {
                    val duration = 0.25f
                    val numSamples = (sampleRate * duration).toInt()
                    val buffer = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val t = i.toFloat() / numSamples
                        val freq = (300f * (1f - t)).coerceAtLeast(50f)
                        val period = (sampleRate / freq).toInt().coerceAtLeast(1)
                        val square = if ((i % period) < (period / 2)) 6000 else -6000
                        val noise = Random.nextInt(-3500, 3500)
                        buffer[i] = ((square + noise) * (1f - t)).toInt().toShort()
                    }
                    playStaticBuffer(buffer)
                } catch (_: Exception) {
                }
            }.start()
        }

        @Suppress("DEPRECATION")
        private fun playStaticBuffer(buffer: ShortArray) {
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer.size * 2,
                AudioTrack.MODE_STATIC
            )
            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            Thread.sleep((buffer.size.toFloat() / sampleRate * 1000).toLong() + 30)
            audioTrack.release()
        }
    }

    private companion object {
        const val READY = 0
        const val PLAYING = 1
        const val DEAD = 2

        const val CHAR_FOX = 0
        const val CHAR_BUNNY = 1

        const val PLAYER_X = 20
        const val PW = 10         // Oyuncu genişliği (sanal piksel)
        const val PH = 10         // Oyuncu yüksekliği
        const val GRAVITY = 430f
        const val JUMP_V = -138f

        val SKY_DAY = Color.rgb(41, 173, 255)
        val SKY_SUNSET = Color.rgb(255, 120, 80)
        val SKY_NIGHT = Color.rgb(15, 22, 50)

        val CLOUD = Color.rgb(255, 241, 232)
        val GRASS = Color.rgb(0, 228, 54)
        val DIRT = Color.rgb(171, 82, 54)
        val DIRT_DARK = Color.rgb(126, 37, 83)
        val CACTUS = Color.rgb(0, 135, 81)
        val CACTUS_DARK = Color.rgb(0, 95, 60)
        val BIRD_COLOR = Color.rgb(200, 70, 70)
        val BIRD_BEAK = Color.rgb(255, 205, 0)
        val COIN_GOLD = Color.rgb(255, 215, 0)

        // 🦊 Sevimli Piksel Tilki Renk & Spriteleri (10x10)
        val FOX_ORANGE = Color.rgb(255, 130, 20)
        val FOX_WHITE = Color.rgb(255, 245, 235)
        val FOX_DARK = Color.rgb(45, 30, 25)
        val PINK = Color.rgb(255, 150, 175)

        val SPRITE_FOX_BASE = arrayOf(
            ".k....k...",
            "opo..opo..",
            "oooooooo..",
            "oks..kso..",
            "oppkkppo.w",
            ".owwwwo.ww",
            ".oooooo.ww",
            "..oooo..w."
        )
        val SPRITE_FOX_DEAD = arrayOf(
            ".k....k...",
            "opo..opo..",
            "oooooooo..",
            "oxs..xso..",
            "oppkkppo.w",
            ".owwwwo.ww",
            ".oooooo.ww",
            "..oooo..w."
        )
        val FOX_LEGS_A = arrayOf(".k....k...", "k......k..")
        val FOX_LEGS_B = arrayOf("..k..k....", "...k..k...")
        val FOX_LEGS_JUMP = arrayOf(".kk..kk...", "..........")

        // 🐰 Yakışıklı Fiyonklu Piksel Tavşan Renk & Spriteleri (10x10)
        val BUNNY_GRAY = Color.rgb(215, 220, 225)
        val BUNNY_WHITE = Color.rgb(255, 255, 255)
        val BUNNY_BOW = Color.rgb(230, 40, 50)

        val SPRITE_BUNNY_BASE = arrayOf(
            ".gp...gp..",
            ".gp...gp..",
            ".ggggggg..",
            ".gks.ksg..",
            ".gwkpkwg..",
            "..gbbg....",
            ".gwwwwgg..",
            "..gggg...."
        )
        val SPRITE_BUNNY_DEAD = arrayOf(
            ".gp...gp..",
            ".gp...gp..",
            ".ggggggg..",
            ".gxs.xsg..",
            ".gwkpkwg..",
            "..gbbg....",
            ".gwwwwgg..",
            "..gggg...."
        )
        val BUNNY_LEGS_A = arrayOf(".g.....g..", "g.......g.")
        val BUNNY_LEGS_B = arrayOf("..g...g...", "...g...g..")
        val BUNNY_LEGS_JUMP = arrayOf(".gg...gg..", "..........")

        val DUST_COLOR = Color.rgb(220, 200, 160)

        val BIRD_WING_UP = arrayOf(
            "..oooo..",
            ".o.oooo.",
            "ooo.ook.",
            ".o.oooo.",
            "..oooo.."
        )
        val BIRD_WING_DOWN = arrayOf(
            "..oooo..",
            ".oooooo.",
            "ooo.ook.",
            "..oooo..",
            "..o..o.."
        )

        val ICON_SOUND_ON = arrayOf(
            "..o.o..",
            ".oo.o..",
            "oooo.o.",
            "oooo.o.",
            "oooo.o.",
            ".oo.o..",
            "..o.o.."
        )
        val ICON_SOUND_OFF = arrayOf(
            "..o...x",
            ".oo..x.",
            "oooo.x.",
            "oooo.x.",
            "oooo.x.",
            ".oo..x.",
            "..o...x"
        )
    }

    private val prefs = context.getSharedPreferences("pixelgame", Context.MODE_PRIVATE)
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val audioSynth = RetroAudioSynth()

    private val paint = Paint().apply { isAntiAlias = false }
    private val textPaint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        textSize = 7f
    }

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var tapped = false

    private var scale = 1
    private var vw = 160f
    private var vh = 90f
    private var groundY = 70f

    private var selectedChar = prefs.getInt("selected_char", CHAR_FOX)
    private var isMuted = prefs.getBoolean("is_muted", false)
    private var state = READY
    private var py = 0f
    private var vy = 0f
    private var speed = 60f
    private var score = 0f
    private var best = prefs.getInt("best", 0)
    private var totalCoins = prefs.getInt("coins", 0)
    private var sessionCoins = 0
    private var isNewBest = false
    private var deadTime = 0f
    private var spawnIn = 0f
    private var coinSpawnIn = 0f
    private var groundScroll = 0f
    private var time = 0f
    private var shakeTime = 0f
    private var lastWasOnGround = true

    private val obstacles = ArrayList<Obstacle>()
    private val clouds = ArrayList<Cloud>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()
    private val stars = ArrayList<Star>()

    init {
        holder.addCallback(this)
    }

    // ---------- Retro Sound Effects ----------

    private fun playSoundJump() {
        if (!isMuted) audioSynth.playJump()
    }

    private fun playSoundCoin() {
        if (!isMuted) audioSynth.playCoin()
    }

    private fun playSoundDie() {
        if (!isMuted) audioSynth.playDie()
    }

    // ---------- Surface Life Cycle ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        scale = (height / 90f).toInt().coerceAtLeast(1)
        vw = width.toFloat() / scale
        vh = height.toFloat() / scale
        groundY = (vh * 0.78f).roundToInt().toFloat()
        resetWorld()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        var retry = true
        while (retry) {
            try {
                thread?.join()
                retry = false
            } catch (_: InterruptedException) {
            }
        }
        thread = null
    }

    // ---------- Game Loop ----------

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now

            update(dt)

            val canvas = holder.lockCanvas() ?: continue
            try {
                drawGame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            try {
                Thread.sleep(4)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun resetWorld() {
        state = READY
        py = groundY - PH
        vy = 0f
        speed = 60f
        score = 0f
        sessionCoins = 0
        isNewBest = false
        spawnIn = 40f
        coinSpawnIn = 30f
        groundScroll = 0f
        shakeTime = 0f
        obstacles.clear()
        clouds.clear()
        coins.clear()
        particles.clear()
        stars.clear()

        // Yıldızlar
        repeat(16) {
            stars.add(
                Star(
                    Random.nextFloat() * vw,
                    2f + Random.nextFloat() * groundY * 0.5f,
                    Random.nextFloat() * 6.28f
                )
            )
        }

        // Bulutlar
        repeat(4) {
            clouds.add(
                Cloud(
                    Random.nextFloat() * vw,
                    6f + Random.nextFloat() * groundY * 0.35f,
                    3f + Random.nextFloat() * 5f
                )
            )
        }
    }

    private fun onGround() = py + PH >= groundY - 0.01f

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun jump() {
        vy = JUMP_V
        triggerHaptic()
        playSoundJump()
        spawnDustParticles(PLAYER_X + PW / 2f, groundY, count = 5)
    }

    private fun spawnDustParticles(x: Float, y: Float, count: Int = 3) {
        repeat(count) {
            val vx = (Random.nextFloat() - 0.5f) * 20f
            val vy = -Random.nextFloat() * 15f - 5f
            particles.add(Particle(x, y, vx, vy, DUST_COLOR, 0f, 0.25f + Random.nextFloat() * 0.2f))
        }
    }

    private fun spawnCoinSparkle(x: Float, y: Float) {
        repeat(8) {
            val vx = (Random.nextFloat() - 0.5f) * 35f
            val vy = (Random.nextFloat() - 0.5f) * 35f
            particles.add(Particle(x, y, vx, vy, COIN_GOLD, 0f, 0.35f, 1.2f))
        }
    }

    private fun spawnDeathParticles(x: Float, y: Float) {
        val colors = if (selectedChar == CHAR_BUNNY) {
            intArrayOf(BUNNY_GRAY, BUNNY_WHITE, BUNNY_BOW, PINK, Color.RED)
        } else {
            intArrayOf(FOX_ORANGE, FOX_WHITE, PINK, Color.RED, DUST_COLOR)
        }
        repeat(24) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            val spd = 20f + Random.nextFloat() * 60f
            val vx = kotlin.math.cos(angle) * spd
            val vy = kotlin.math.sin(angle) * spd
            val color = colors[Random.nextInt(colors.size)]
            particles.add(Particle(x, y, vx, vy, color, 0f, 0.4f + Random.nextFloat() * 0.3f, 1.5f))
        }
    }

    private fun update(dt: Float) {
        time += dt

        if (shakeTime > 0f) {
            shakeTime -= dt
            if (shakeTime < 0f) shakeTime = 0f
        }

        if (tapped) {
            tapped = false
            when (state) {
                READY -> {
                    state = PLAYING
                    jump()
                }
                PLAYING -> if (onGround()) jump()
                DEAD -> if (deadTime > 0.4f) resetWorld()
            }
        }

        // Bulut güncelleme
        for (c in clouds) {
            c.x -= c.speed * dt
            if (c.x < -16f) c.x = vw + Random.nextFloat() * 20f
        }

        // Parçacık güncelleme
        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.life += dt
            if (p.life >= p.maxLife) {
                pIter.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 180f * dt
        }

        if (state == DEAD) {
            deadTime += dt
            return
        }
        if (state == READY) return

        speed = (60f + score * 0.15f).coerceAtMost(150f)
        score += speed * dt * 0.1f
        if (score.toInt() > best) {
            best = score.toInt()
            isNewBest = true
        }
        groundScroll = (groundScroll + speed * dt) % 8f

        // Fizik
        val currentlyOnGround = onGround()
        vy += GRAVITY * dt
        py += vy * dt

        if (py + PH >= groundY) {
            py = groundY - PH
            vy = 0f
            if (!lastWasOnGround) {
                spawnDustParticles(PLAYER_X + PW / 2f, groundY, count = 4)
            }
        }
        lastWasOnGround = currentlyOnGround

        // Koşma tozu
        if (currentlyOnGround && Random.nextFloat() < 0.25f) {
            particles.add(
                Particle(
                    PLAYER_X.toFloat(),
                    groundY - 1f,
                    -speed * 0.3f - Random.nextFloat() * 10f,
                    -Random.nextFloat() * 8f,
                    DUST_COLOR,
                    0f,
                    0.2f
                )
            )
        }

        // Altın Üretimi
        coinSpawnIn -= speed * dt
        if (coinSpawnIn <= 0f) {
            val coinY = groundY - (14f + Random.nextInt(3) * 9f)
            coins.add(Coin(vw + 4f, coinY))
            if (Random.nextFloat() < 0.4f) {
                coins.add(Coin(vw + 12f, coinY))
            }
            coinSpawnIn = 45f + Random.nextFloat() * 60f
        }

        // Altın Hareketi & Toplama
        val cIter = coins.iterator()
        while (cIter.hasNext()) {
            val c = cIter.next()
            c.x -= speed * dt
            if (c.x + 4f < 0f) {
                cIter.remove()
                continue
            }
            // Çarpışma algılama
            if (PLAYER_X + PW > c.x && PLAYER_X < c.x + 4f && py + PH > c.y && py < c.y + 4f) {
                cIter.remove()
                sessionCoins++
                totalCoins++
                prefs.edit().putInt("coins", totalCoins).apply()
                score += 5f
                playSoundCoin()
                spawnCoinSparkle(c.x + 2f, c.y + 2f)
            }
        }

        // Engel üretimi
        spawnIn -= speed * dt
        if (spawnIn <= 0f) {
            spawnRandomObstacle()
            spawnIn = 50f + Random.nextFloat() * 55f + speed * 0.25f
        }

        // Engel haraket & çarpışma
        val oIter = obstacles.iterator()
        while (oIter.hasNext()) {
            val o = oIter.next()
            o.x -= speed * dt
            if (o.x + o.width < 0f) {
                oIter.remove()
                continue
            }

            val pl = PLAYER_X + 1f
            val pr = PLAYER_X + PW - 1f
            val pt = py + 1f
            val pb = py + PH - 0.5f

            val ol = o.x + 1f
            val or = o.x + o.width - 1f
            val ot = o.y + 1f
            val ob = o.y + o.height - 0.5f

            if (pr > ol && pl < or && pb > ot && pt < ob) {
                die()
                return
            }
        }
    }

    private fun spawnRandomObstacle() {
        val rand = Random.nextFloat()
        val type = when {
            score > 80 && rand < 0.25f -> ObstacleType.BIRD
            rand < 0.50f -> ObstacleType.CACTUS_SMALL
            rand < 0.80f -> ObstacleType.CACTUS_TALL
            else -> ObstacleType.CACTUS_DOUBLE
        }

        when (type) {
            ObstacleType.CACTUS_SMALL -> {
                obstacles.add(Obstacle(vw + 4f, groundY - 10f, 7, 10, type))
            }
            ObstacleType.CACTUS_TALL -> {
                obstacles.add(Obstacle(vw + 4f, groundY - 15f, 9, 15, type))
            }
            ObstacleType.CACTUS_DOUBLE -> {
                obstacles.add(Obstacle(vw + 4f, groundY - 12f, 15, 12, type))
            }
            ObstacleType.BIRD -> {
                val birdY = groundY - (18f + Random.nextInt(3) * 7f)
                obstacles.add(Obstacle(vw + 4f, birdY, 10, 8, type))
            }
        }
    }

    private fun die() {
        state = DEAD
        deadTime = 0f
        shakeTime = 0.3f
        triggerHaptic()
        playSoundDie()

        spawnDeathParticles(PLAYER_X + PW / 2f, py + PH / 2f)

        val s = score.toInt()
        if (s > best) {
            best = s
            prefs.edit().putInt("best", s).apply()
        }
    }

    // ---------- Drawing System ----------

    private fun rect(c: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        c.drawRect(x, y, x + w, y + h, paint)
    }

    private fun rect(c: Canvas, x: Int, y: Int, w: Int, h: Int, color: Int) =
        rect(c, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), color)

    private fun interpolateColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * ratio).roundToInt().coerceIn(0, 255)
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * ratio).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * ratio).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun drawMoon(c: Canvas, x: Float, y: Float, alpha: Float) {
        val mx = x.roundToInt()
        val my = y.roundToInt()
        paint.color = Color.rgb(255, 250, 220)
        paint.alpha = (alpha * 240).toInt()
        rect(c, mx + 2, my, 4, 8, Color.rgb(255, 250, 220))
        rect(c, mx, my + 2, 8, 4, Color.rgb(255, 250, 220))
        // Ay kriterleri
        paint.color = Color.rgb(220, 215, 180)
        paint.alpha = (alpha * 200).toInt()
        rect(c, mx + 2, my + 2, 2, 2, Color.rgb(220, 215, 180))
        rect(c, mx + 4, my + 5, 2, 1, Color.rgb(220, 215, 180))
        paint.alpha = 255
    }

    private fun drawCoin(c: Canvas, coin: Coin) {
        val cx = coin.x.roundToInt()
        val cy = coin.y.roundToInt()
        rect(c, cx + 1, cy, 2, 4, COIN_GOLD)
        rect(c, cx, cy + 1, 4, 2, COIN_GOLD)
        rect(c, cx + 1, cy + 1, 1, 1, Color.WHITE) // Işıltı
    }

    private fun drawSprite(c: Canvas, rows: Array<String>, x: Int, y: Int) {
        for ((r, row) in rows.withIndex()) {
            for ((i, ch) in row.withIndex()) {
                val color = when (ch) {
                    'o' -> FOX_ORANGE
                    'w' -> if (selectedChar == CHAR_BUNNY) BUNNY_WHITE else FOX_WHITE
                    'k' -> FOX_DARK
                    'p' -> PINK
                    'g' -> BUNNY_GRAY
                    'b' -> BUNNY_BOW
                    's' -> Color.WHITE
                    'x' -> Color.RED
                    'y' -> BIRD_BEAK
                    else -> continue
                }
                rect(c, x + i, y + r, 1, 1, color)
            }
        }
    }

    private fun drawCactus(c: Canvas, o: Obstacle) {
        val x = o.x.roundToInt()
        val y = o.y.roundToInt()
        val h = o.height
        val w = o.width

        if (o.type == ObstacleType.CACTUS_DOUBLE) {
            rect(c, x + 1, y + 2, 3, h - 2, CACTUS)
            rect(c, x + 3, y + 2, 1, h - 2, CACTUS_DARK)
            rect(c, x + 8, y, 3, h, CACTUS)
            rect(c, x + 10, y, 1, h, CACTUS_DARK)
            rect(c, x, y + 5, 2, 3, CACTUS)
            rect(c, x + 12, y + 4, 2, 3, CACTUS)
        } else {
            val cx = x + (w / 2) - 1
            rect(c, cx, y, 3, h, CACTUS)
            rect(c, cx + 2, y, 1, h, CACTUS_DARK)
            rect(c, x, y + 3, 2, 4, CACTUS)
            rect(c, x, y + 5, (cx - x) + 1, 2, CACTUS)
            rect(c, cx + 3, y + 4, 2, 4, CACTUS)
            rect(c, cx + 2, y + 6, 3, 2, CACTUS)
        }
    }

    private fun drawBird(c: Canvas, o: Obstacle) {
        val x = o.x.roundToInt()
        val y = o.y.roundToInt()
        val frame = if ((time * 6f).toInt() % 2 == 0) BIRD_WING_UP else BIRD_WING_DOWN

        for ((r, row) in frame.withIndex()) {
            for ((i, ch) in row.withIndex()) {
                val color = when (ch) {
                    'o' -> BIRD_COLOR
                    'k' -> FOX_DARK
                    else -> continue
                }
                rect(c, x + i, y + r, 1, 1, color)
            }
        }
        rect(c, x - 2, y + 2, 2, 1, BIRD_BEAK)
    }

    private fun drawText(c: Canvas, text: String, x: Float, y: Float, align: Paint.Align, color: Int = Color.WHITE) {
        textPaint.textAlign = align
        textPaint.color = Color.rgb(20, 30, 60)
        c.drawText(text, x + 0.6f, y + 0.6f, textPaint)
        textPaint.color = color
        c.drawText(text, x, y, textPaint)
    }

    private fun drawPixelBox(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val ix = x.roundToInt()
        val iy = y.roundToInt()
        val iw = w.roundToInt()
        val ih = h.roundToInt()
        rect(c, ix, iy, iw, ih, Color.rgb(20, 30, 60))
        rect(c, ix + 1, iy + 1, iw - 2, ih - 2, Color.rgb(30, 45, 85))
        rect(c, ix + 2, iy + 2, iw - 4, 1, Color.rgb(70, 95, 150))
    }

    private fun drawSoundIcon(c: Canvas) {
        val ix = (vw - 15f).roundToInt()
        val iy = 15
        val icon = if (isMuted) ICON_SOUND_OFF else ICON_SOUND_ON

        rect(c, ix - 2, iy - 2, 11, 11, Color.rgb(20, 30, 60))
        rect(c, ix - 1, iy - 1, 9, 9, Color.rgb(30, 45, 85))

        for ((r, row) in icon.withIndex()) {
            for ((i, ch) in row.withIndex()) {
                val color = when (ch) {
                    'o' -> Color.rgb(100, 240, 130)
                    'x' -> Color.rgb(255, 90, 90)
                    else -> continue
                }
                rect(c, ix + i, iy + r, 1, 1, color)
            }
        }
    }

    private fun drawGame(canvas: Canvas) {
        // Gece / Gündüz Döngüsü Hesaplama (Skora göre Yumuşak Geçiş)
        val cycleVal = ((score % 300f) / 300f)
        val currentSkyColor = when {
            cycleVal < 0.45f -> SKY_DAY
            cycleVal < 0.55f -> interpolateColor(SKY_DAY, SKY_SUNSET, (cycleVal - 0.45f) * 10f)
            cycleVal < 0.65f -> interpolateColor(SKY_SUNSET, SKY_NIGHT, (cycleVal - 0.55f) * 10f)
            cycleVal < 0.90f -> SKY_NIGHT
            else -> interpolateColor(SKY_NIGHT, SKY_DAY, (cycleVal - 0.90f) * 10f)
        }

        canvas.drawColor(currentSkyColor)
        canvas.save()

        // Ekran Sarsıntısı (Screen Shake)
        if (shakeTime > 0f) {
            val sx = (Random.nextFloat() - 0.5f) * 3f * (shakeTime / 0.3f)
            val sy = (Random.nextFloat() - 0.5f) * 3f * (shakeTime / 0.3f)
            canvas.translate(sx * scale, sy * scale)
        }

        canvas.scale(scale.toFloat(), scale.toFloat())

        // Gece ise Yıldızlar & Ay Çizimi
        val isNight = cycleVal in 0.60f..0.95f
        if (isNight) {
            val nightAlpha = when {
                cycleVal < 0.65f -> (cycleVal - 0.60f) * 20f
                cycleVal > 0.90f -> (0.95f - cycleVal) * 20f
                else -> 1f
            }.coerceIn(0f, 1f)

            drawMoon(canvas, vw - 28f, 8f, nightAlpha)

            for (s in stars) {
                val tw = ((kotlin.math.sin(time * 3.5 + s.twinkleOffset) + 1.0) * 0.5).toFloat() * nightAlpha
                if (tw > 0.15f) {
                    paint.color = Color.WHITE
                    paint.alpha = (tw * 255).toInt()
                    canvas.drawRect(s.x, s.y, s.x + 1f, s.y + 1f, paint)
                    paint.alpha = 255
                }
            }
        }

        // Bulutlar
        for (cl in clouds) {
            val x = cl.x.roundToInt()
            val y = cl.y.roundToInt()
            rect(canvas, x, y, 12, 3, CLOUD)
            rect(canvas, x + 2, y - 2, 7, 2, CLOUD)
        }

        // Zemin
        val gy = groundY.toInt()
        rect(canvas, 0f, groundY, vw, vh - groundY + 1f, DIRT)
        rect(canvas, 0f, groundY, vw, 2f, GRASS)
        var gx = -groundScroll
        var k = 0
        while (gx < vw) {
            rect(canvas, gx.roundToInt(), gy + 5 + (k % 3) * 3, 2, 1, DIRT_DARK)
            gx += 8f
            k++
        }

        // Parçacıklar
        for (p in particles) {
            val alpha = (1f - p.life / p.maxLife).coerceIn(0f, 1f)
            if (alpha > 0f) {
                paint.color = p.color
                paint.alpha = (alpha * 255).toInt()
                canvas.drawRect(p.x, p.y, p.x + p.size, p.y + p.size, paint)
                paint.alpha = 255
            }
        }

        // Altınlar
        for (c in coins) {
            drawCoin(canvas, c)
        }

        // Engeller
        for (o in obstacles) {
            if (o.type == ObstacleType.BIRD) drawBird(canvas, o) else drawCactus(canvas, o)
        }

        // Oyuncu Spriti (Seçili Karakter: Tilki veya Tavşan)
        val px = PLAYER_X
        val pyInt = py.roundToInt()
        val baseSprite = if (selectedChar == CHAR_BUNNY) SPRITE_BUNNY_BASE else SPRITE_FOX_BASE
        val deadSprite = if (selectedChar == CHAR_BUNNY) SPRITE_BUNNY_DEAD else SPRITE_FOX_DEAD
        val legA = if (selectedChar == CHAR_BUNNY) BUNNY_LEGS_A else FOX_LEGS_A
        val legB = if (selectedChar == CHAR_BUNNY) BUNNY_LEGS_B else FOX_LEGS_B
        val legJump = if (selectedChar == CHAR_BUNNY) BUNNY_LEGS_JUMP else FOX_LEGS_JUMP

        when (state) {
            DEAD -> {
                drawSprite(canvas, deadSprite, px, pyInt)
            }
            PLAYING -> {
                if (!onGround()) {
                    drawSprite(canvas, baseSprite + legJump, px, pyInt)
                } else {
                    val legFrame = if (((time * 10f).toInt() % 2 == 0)) legB else legA
                    drawSprite(canvas, baseSprite + legFrame, px, pyInt)
                }
            }
            READY -> {
                drawSprite(canvas, baseSprite + legA, px, pyInt)
            }
        }

        // Skor, Rekor ve Altın Arayüzü (Oyun esnasında o turda toplanan altın gösterilir)
        val scoreColor = if (score.toInt() > 0 && score.toInt() % 100 in 0..10) Color.YELLOW else Color.WHITE
        drawText(canvas, "SKOR ${score.toInt()}", 4f, 9f, Paint.Align.LEFT, scoreColor)
        drawText(canvas, "ALTIN $sessionCoins", vw / 2f, 9f, Paint.Align.CENTER, COIN_GOLD)
        drawText(canvas, "EN İYİ $best", vw - 4f, 9f, Paint.Align.RIGHT)

        // Ses İkonu
        drawSoundIcon(canvas)

        // Durum Panelleri
        val pulse = ((kotlin.math.sin(time * 6.0) + 1.0) * 0.5).toFloat()

        when (state) {
            READY -> {
                drawPixelBox(canvas, vw / 2f - 56f, vh * 0.14f, 112f, 48f)
                drawText(canvas, "FUCHS", vw / 2f, vh * 0.25f, Paint.Align.CENTER, Color.YELLOW)

                val charName = if (selectedChar == CHAR_BUNNY) "TAVSAN" else "TILKI"
                drawText(canvas, "< KARAKTER: $charName >", vw / 2f, vh * 0.38f, Paint.Align.CENTER, Color.CYAN)

                drawText(canvas, "TOPLAM ALTIN: $totalCoins", vw / 2f, vh * 0.50f, Paint.Align.CENTER, COIN_GOLD)

                val tColor = if (pulse > 0.4f) Color.WHITE else Color.rgb(180, 180, 180)
                drawText(canvas, "BAŞLAMAK İÇİN DOKUN", vw / 2f, vh * 0.63f, Paint.Align.CENTER, tColor)
            }
            DEAD -> {
                drawPixelBox(canvas, vw / 2f - 52f, vh * 0.18f, 104f, 48f)
                drawText(canvas, "OYUN BİTTİ", vw / 2f, vh * 0.28f, Paint.Align.CENTER, Color.rgb(255, 80, 80))

                if (isNewBest) {
                    drawText(canvas, "YENİ REKOR! ${score.toInt()}", vw / 2f, vh * 0.39f, Paint.Align.CENTER, Color.YELLOW)
                } else {
                    drawText(canvas, "SKOR: ${score.toInt()}", vw / 2f, vh * 0.39f, Paint.Align.CENTER, Color.WHITE)
                }

                drawText(canvas, "TOPLANAN ALTIN: +$sessionCoins", vw / 2f, vh * 0.50f, Paint.Align.CENTER, COIN_GOLD)

                val tColor = if (pulse > 0.4f) Color.WHITE else Color.rgb(180, 180, 180)
                drawText(canvas, "TEKRAR İÇİN DOKUN", vw / 2f, vh * 0.62f, Paint.Align.CENTER, tColor)
            }
        }

        canvas.restore()
    }

    // ---------- Touch Input ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val touchX = event.x / scale
            val touchY = event.y / scale

            // Sağ üstteki Ses İkonuna tıklanırsa sesi aç/kapat!
            if (touchX >= vw - 22f && touchY <= 30f) {
                isMuted = !isMuted
                prefs.edit().putBoolean("is_muted", isMuted).apply()
                triggerHaptic()
                return true
            }

            if (state == READY) {
                // Karakter Seçimi (Panel ortası)
                if (touchY in (vh * 0.28f)..(vh * 0.44f)) {
                    selectedChar = if (selectedChar == CHAR_FOX) CHAR_BUNNY else CHAR_FOX
                    prefs.edit().putInt("selected_char", selectedChar).apply()
                    triggerHaptic()
                    return true
                }
            }
            tapped = true
        }
        return true
    }
}
