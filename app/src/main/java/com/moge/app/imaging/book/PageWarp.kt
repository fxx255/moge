package com.moge.app.imaging.book

/** Normalized backward sampling coordinates, composed with perspective before rendering. */
internal interface PageWarp {
    fun sourceMesh(columns: Int, rows: Int): Pair<FloatArray, FloatArray>
}
