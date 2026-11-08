package com.example

import kotlin.math.roundToInt

data class PreviewRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

object PreviewLayoutCalculator {
    fun calculate(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        pixelRatio: Float,
        rootWidth: Int,
        rootHeight: Int,
        webOffsetX: Int = 0,
        webOffsetY: Int = 0
    ): PreviewRect? {
        if (!left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite()) return null
        if (!pixelRatio.isFinite() || pixelRatio !in 0.5f..8f) return null
        if (width <= 0f || height <= 0f) return null
        if (rootWidth <= 0 || rootHeight <= 0) return null

        val requestedLeft = webOffsetX + (left * pixelRatio).roundToInt()
        val requestedTop = webOffsetY + (top * pixelRatio).roundToInt()
        val requestedRight = requestedLeft + (width * pixelRatio).roundToInt()
        val requestedBottom = requestedTop + (height * pixelRatio).roundToInt()

        // Check for gross layout errors (completely off-screen rects)
        if (requestedRight <= 0 || requestedBottom <= 0 || requestedLeft >= rootWidth || requestedTop >= rootHeight) {
            return null
        }

        // Clamp coordinates safely to the container boundary to absorb sub-pixel/border rounding
        val safeLeft = requestedLeft.coerceIn(0, rootWidth)
        val safeTop = requestedTop.coerceIn(0, rootHeight)
        val safeRight = requestedRight.coerceIn(0, rootWidth)
        val safeBottom = requestedBottom.coerceIn(0, rootHeight)
        val safeWidth = safeRight - safeLeft
        val safeHeight = safeBottom - safeTop
        if (safeWidth <= 0 || safeHeight <= 0) return null

        return PreviewRect(safeLeft, safeTop, safeWidth, safeHeight)
    }
}
