package com.moge.app.imaging.book

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

internal object DocumentDetector {
    /** Coordinates always refer to the supplied, upright image, including during live analysis. */
    fun detect(gray: Mat): List<Corner> {
        val image = Mat(); val blurred = Mat(); val edges = Mat(); val ink = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            val scale = min(1.0, 1000.0 / max(gray.cols(), gray.rows()))
            Imgproc.resize(gray, image, Size(), scale, scale, Imgproc.INTER_AREA)
            if (min(image.cols(), image.rows()) < 32) return Geometry.fullFrame
            Imgproc.GaussianBlur(image, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 40.0, 120.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.adaptiveThreshold(image, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 12.0)
            val inkQuads = quads(ink)
            // A large, complete ruled table is a stronger boundary than an irregular stack of paper.
            for (quad in inkQuads.filter { Geometry.area(it) >= .40f }.take(4)) {
                if (hasGrid(image, quad) && !hasOutsideText(image, ink, quad)) return expand(quad, 1.025f)
            }
            return quads(edges).firstOrNull() ?: Geometry.fullFrame
        } finally { listOf(image, blurred, edges, ink, kernel).forEach { it.release() } }
    }

    private fun quads(mask: Mat): List<List<Corner>> {
        val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val copy = mask.clone()
        try {
            Imgproc.findContours(copy, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val candidates = mutableListOf<List<Corner>>()
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(24)) {
                if (Imgproc.contourArea(contour) < mask.total() * .15) continue
                val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                try {
                    for (tolerance in listOf(.015, .025, .04)) {
                        Imgproc.approxPolyDP(curve, approx, tolerance * Imgproc.arcLength(curve, true), true)
                        if (approx.total() != 4L) continue
                        val points = approx.toArray().toList()
                        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
                        val ordered = points.sortedBy { atan2(it.y - cy, it.x - cx) }
                        val first = ordered.indices.minBy { ordered[it].x + ordered[it].y }
                        val corners = (0..3).map { ordered[(first + it) % 4] }.map {
                            Corner((it.x / (mask.cols() - 1)).toFloat().coerceIn(0f, 1f), (it.y / (mask.rows() - 1)).toFloat().coerceIn(0f, 1f))
                        }
                        if (Geometry.isValid(corners) && Geometry.area(corners) < .98f &&
                            corners.count { it.x < .004f || it.x > .996f || it.y < .004f || it.y > .996f } < 2) {
                            candidates += corners
                            break
                        }
                    }
                } finally { curve.release(); approx.release() }
            }
            return candidates.sortedByDescending { Geometry.area(it) }
        } finally { contours.forEach { it.release() }; hierarchy.release(); copy.release() }
    }

    private fun hasGrid(gray: Mat, corners: List<Corner>): Boolean {
        val from = MatOfPoint2f(*corners.map { Point(it.x * (gray.cols() - 1.0), it.y * (gray.rows() - 1.0)) }.toTypedArray())
        val width = max(120, (Geometry.distance(corners[0], corners[1], gray.cols(), gray.rows()) +
            Geometry.distance(corners[3], corners[2], gray.cols(), gray.rows())).roundToInt() / 2)
        val height = max(120, (Geometry.distance(corners[0], corners[3], gray.cols(), gray.rows()) +
            Geometry.distance(corners[1], corners[2], gray.cols(), gray.rows())).roundToInt() / 2)
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0), Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0))
        val transform = Imgproc.getPerspectiveTransform(from, to); val rect = Mat()
        try {
            Imgproc.warpPerspective(gray, rect, transform, Size(width.toDouble(), height.toDouble()))
            // Include the border lines in the analysis instead of eroding them at the image boundary.
            val padded = Mat()
            try {
                Core.copyMakeBorder(rect, padded, 12, 12, 12, 12, Core.BORDER_CONSTANT, Scalar(255.0))
                return TableRectifier.analyze(padded) != null
            } finally { padded.release() }
        } finally { listOf(from, to, transform, rect).forEach { it.release() } }
    }

    /** Keep headings, footnotes, or side notes rather than treating every large table as the whole document. */
    private fun hasOutsideText(gray: Mat, ink: Mat, corners: List<Corner>): Boolean {
        val polygon = MatOfPoint2f(*corners.map { Point(it.x * (gray.cols() - 1.0), it.y * (gray.rows() - 1.0)) }.toTypedArray())
        val pixels = ByteArray(gray.rows() * gray.cols()).also { gray.get(0, 0, it) }
        val tones = mutableListOf<Int>()
        for (y in 0 until gray.rows() step 12) for (x in 0 until gray.cols() step 12) {
            if (Imgproc.pointPolygonTest(polygon, Point(x.toDouble(), y.toDouble()), false) >= 0)
                tones += pixels[y * gray.cols() + x].toInt() and 255
        }
        tones.sort()
        val paperTone = tones.getOrElse((tones.size * .7).toInt()) { 220 }
        val contours = mutableListOf<MatOfPoint>(); val hierarchy = Mat(); val copy = ink.clone()
        val components = mutableListOf<Rect>()
        try {
            // Nested contours matter: an outer paper-edge contour can enclose every title glyph.
            Imgproc.findContours(copy, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                if (rect.width !in 2..40 || rect.height !in 3..40 || rect.width.toFloat() / rect.height !in .18f..6f || Imgproc.contourArea(contour) < 2) continue
                val center = Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
                if (Imgproc.pointPolygonTest(polygon, center, true) >= -7) continue
                val neighborhood = mutableListOf<Int>()
                for (y in max(0, rect.y - 3) until min(gray.rows(), rect.y + rect.height + 3))
                    for (x in max(0, rect.x - 3) until min(gray.cols(), rect.x + rect.width + 3))
                        neighborhood += pixels[y * gray.cols() + x].toInt() and 255
                neighborhood.sort()
                if (neighborhood[neighborhood.size * 3 / 4] >= paperTone * .9) components += rect
            }
            val minSpan = (corners.maxOf { it.x } - corners.minOf { it.x }) * gray.cols() * .10
            return components.any { anchor ->
                // A text row needs neighbouring, similarly sized glyphs. Paper-edge fragments on
                // opposite sides of a stack must not combine into a fictitious page-wide heading.
                val row = components.filter {
                    it.height.toFloat() / anchor.height in .55f..1.8f &&
                        abs((it.y + it.height / 2f) - (anchor.y + anchor.height / 2f)) < max(3f, anchor.height * .45f)
                }.sortedBy { it.x }
                var start = 0; var end = 0; var count = 0
                row.any { glyph ->
                    val gap = max(12f, anchor.height * 2.5f)
                    if (count == 0 || glyph.x - end > gap) {
                        start = glyph.x; end = glyph.x + glyph.width; count = 1
                    } else if (glyph.x + glyph.width > end + 1) {
                        // Nested contours of the same glyph do not count as additional letters.
                        end = glyph.x + glyph.width; count++
                    }
                    count >= 4 && end - start > minSpan
                }
            }
        } finally { polygon.release(); contours.forEach { it.release() }; hierarchy.release(); copy.release() }
    }

    private fun expand(corners: List<Corner>, scale: Float): List<Corner> {
        val cx = corners.map { it.x }.average().toFloat(); val cy = corners.map { it.y }.average().toFloat()
        return corners.map { Corner((cx + (it.x - cx) * scale).coerceIn(0f, 1f), (cy + (it.y - cy) * scale).coerceIn(0f, 1f)) }
            .takeIf { Geometry.isValid(it) } ?: corners
    }
}
