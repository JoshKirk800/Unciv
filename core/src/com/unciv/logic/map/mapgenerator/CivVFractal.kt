package com.unciv.logic.map.mapgenerator

import kotlin.random.Random

/**
 *  The height field the Civ V map scripts draw terrain and features from (CvFractal as `Fractal.Create` in the Lua scripts).
 *
 *  A grid of 2^[fracXExp] x 2^[fracYExp] cells (Civ V: 128 x 64, whatever the size of the map) gets random heights 0..255
 *  at every 2^smooth-th point, where `smooth = min(exps) - grain`: a lower grain is a coarser field. Then the gaps are
 *  filled pass by pass, halving the distance: a point between four others is their mean plus a random offset, a point
 *  between two the mean of the two plus an offset, the offsets shrink by half each pass.
 *  The map's tiles read it by bilinear interpolation ([getHeight]), the scripts cut it at a percentile of its cells
 *  ([getHeightFromPercent]) so a share of the land gets a terrain no matter what the field looks like.
 *
 *  With [polar] the edges that do not wrap are zero in every pass (the scripts' land fields use it to leave the poles in the sea).
 *  With [rifts] (another field) two long valleys are cut into the finished field, wandering around a quarter and three quarters of the
 *  way across (Fractal.CreateRifts: tectonicAction).
 */
class CivVFractal(
    private val width: Int,
    private val height: Int,
    grain: Int,
    private val wrapX: Boolean,
    private val wrapY: Boolean,
    rng: Random,
    polar: Boolean = false,
    rifts: CivVFractal? = null,
    fracXExp: Int = DEFAULT_X_EXP,
    fracYExp: Int = DEFAULT_Y_EXP
) {
    companion object {
        const val DEFAULT_X_EXP = 7
        const val DEFAULT_Y_EXP = 6
    }

    private val fracX = 1 shl fracXExp
    private val fracY = 1 shl fracYExp
    private val grid = Array(fracX + 1) { IntArray(fracY + 1) }

    init {
        val minExp = minOf(fracXExp, fracYExp)
        val smooth = (minExp - grain).coerceIn(0, minExp)

        fun offset(pass: Int) = rng.nextInt(1 shl (8 - smooth + pass)) - (1 shl (7 - smooth + pass))

        for (pass in smooth downTo 0) {
            // Close the seams, or set the poles to zero
            if (wrapY) for (x in 0..fracX) grid[x][fracY] = grid[x][0]
            else if (polar) for (x in 0..fracX) { grid[x][0] = 0; grid[x][fracY] = 0 }
            if (wrapX) for (y in 0..fracY) grid[fracX][y] = grid[0][y]
            else if (polar) for (y in 0..fracY) { grid[0][y] = 0; grid[fracX][y] = 0 }

            val step = 1 shl pass
            val xCount = (fracX shr pass) + if (wrapX) 0 else 1
            val yCount = (fracY shr pass) + if (wrapY) 0 else 1
            for (ix in 0 until xCount) {
                for (iy in 0 until yCount) {
                    val x = ix * step
                    val y = iy * step
                    if (pass == smooth) {
                        grid[x][y] = rng.nextInt(256)
                        continue
                    }
                    val xOdd = ix % 2 == 1
                    val yOdd = iy % 2 == 1
                    val mean = when {
                        xOdd && yOdd -> (grid[x - step][y - step] + grid[x + step][y - step] +
                            grid[x - step][y + step] + grid[x + step][y + step]) shr 2
                        xOdd -> (grid[x - step][y] + grid[x + step][y]) shr 1
                        yOdd -> (grid[x][y - step] + grid[x][y + step]) shr 1
                        else -> continue // set in an earlier pass
                    }
                    grid[x][y] = (mean + offset(pass)).coerceIn(0, 255)
                }
            }
        }
    }

    init {
        if (rifts != null) cutRifts(rifts)
    }

    /** CvFractal::tectonicAction: the valley along the line a quarter of the way across, pushed sideways by the rifts field,
     *  16 columns wide, deepening to zero in the middle (and its twin to the left of the seam) */
    private fun cutRifts(rifts: CivVFractal) {
        val riftX = (fracX / 4) * 3
        val width = 16
        fun wrapX(x: Int) = if (x < 0) x + fracX else if (x >= fracX) x - fracX else x
        for (y in 0..fracY) {
            for (i in 0 until width) {
                val shift = (((rifts.grid[riftX][y] - 128) * fracX) / 128) / 8
                val right = wrapX(shift + i)
                val left = wrapX(shift - i)
                grid[right][y] = (grid[right][y] * i) / width
                grid[left][y] = (grid[left][y] * i) / width
            }
        }
        for (y in 0..fracY) grid[fracX][y] = grid[0][y]
    }

    /** The height 0..255 at the tile ([x], [y]) of a map of the size the field was made for */
    fun getHeight(x: Int, y: Int): Int {
        val gx = x.toDouble() * fracX / width
        val gy = y.toDouble() * fracY / height
        val lowX = gx.toInt().coerceAtMost(fracX - 1)
        val lowY = gy.toInt().coerceAtMost(fracY - 1)
        val errX = gx - lowX
        val errY = gy - lowY
        val sum = (1 - errX) * (1 - errY) * grid[lowX][lowY] + errX * (1 - errY) * grid[lowX + 1][lowY] +
            (1 - errX) * errY * grid[lowX][lowY + 1] + errX * errY * grid[lowX + 1][lowY + 1]
        return sum.toInt().coerceIn(0, 255)
    }

    /** The height below which [percent] of the field's cells lie, found by the same bisection as the original */
    fun getHeightFromPercent(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        var lower = 0
        var upper = 255
        var estimate = 255 * p / 100
        while (estimate != lower) {
            var below = 0
            for (x in 0 until fracX) for (y in 0 until fracY) if (grid[x][y] < estimate) below++
            if (100 * below / (fracX * fracY) > p) {
                upper = estimate
                estimate = (upper + lower) / 2
            } else {
                lower = estimate
                estimate = (upper + lower) / 2
            }
        }
        return estimate
    }
}
