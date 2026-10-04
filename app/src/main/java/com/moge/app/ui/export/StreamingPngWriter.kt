package com.moge.app.ui.export

import android.graphics.Bitmap
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** One continuous PNG stream, fed in horizontal bands instead of allocating the entire long image. */
internal class StreamingPngWriter(
    output: OutputStream,
    private val width: Int,
    private val height: Int,
    private val checkActive: () -> Unit = {},
) : AutoCloseable {
    private val data = DataOutputStream(output)
    private val compressor = Deflater()
    private val chunks = PngDataChunks(data)
    private val compressed = DeflaterOutputStream(chunks, compressor, 32 * 1024)
    private val pixels = IntArray(width)
    private val row = ByteArray(width * 3 + 1)
    private var rows = 0
    private var finished = false

    init {
        require(width > 0 && height > 0)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = java.io.ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height)
            writeByte(8); writeByte(2) // 8-bit opaque RGB; the paper fills every pixel.
            writeByte(0); writeByte(0); writeByte(0)
        }
        pngChunk(data, "IHDR", header.toByteArray(), 13)
    }

    fun append(band: Bitmap) {
        check(!finished && band.width == width && rows + band.height <= height)
        for (y in 0 until band.height) {
            checkActive()
            band.getPixels(pixels, 0, width, 0, y, width, 1)
            row[0] = 1 // PNG Sub filter improves compression without losing detail.
            var previousRed = 0
            var previousGreen = 0
            var previousBlue = 0
            for (x in 0 until width) {
                val color = pixels[x]
                val red = color shr 16 and 255
                val green = color shr 8 and 255
                val blue = color and 255
                val start = x * 3 + 1
                row[start] = (red - previousRed).toByte()
                row[start + 1] = (green - previousGreen).toByte()
                row[start + 2] = (blue - previousBlue).toByte()
                previousRed = red; previousGreen = green; previousBlue = blue
            }
            compressed.write(row)
            rows++
        }
    }

    fun finish() {
        check(!finished && rows == height)
        checkActive()
        compressed.finish()
        chunks.flush()
        pngChunk(data, "IEND", ByteArray(0), 0)
        data.flush()
        finished = true
    }

    override fun close() { compressor.end() }
}

private class PngDataChunks(private val output: DataOutputStream) : OutputStream() {
    private val buffer = ByteArray(64 * 1024)
    private var used = 0

    override fun write(value: Int) {
        buffer[used++] = value.toByte()
        if (used == buffer.size) flush()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var start = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size - used)
            bytes.copyInto(buffer, used, start, start + count)
            used += count; start += count; remaining -= count
            if (used == buffer.size) flush()
        }
    }

    override fun flush() {
        if (used > 0) {
            pngChunk(output, "IDAT", buffer, used)
            used = 0
        }
    }
}

private fun pngChunk(output: DataOutputStream, name: String, bytes: ByteArray, length: Int) {
    val type = name.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(type); update(bytes, 0, length) }
    output.writeInt(length)
    output.write(type)
    output.write(bytes, 0, length)
    output.writeInt(crc.value.toInt())
}
