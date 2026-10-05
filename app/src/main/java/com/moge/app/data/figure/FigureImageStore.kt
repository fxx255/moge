package com.moge.app.data.figure

import android.graphics.Bitmap
import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 图表 PNG 的持久化骨架：函数图与框图共用。
 *
 * 目录结构（都在 filesDir 下，系统不会像 cacheDir 那样按需清空）：
 * - `<prefix>_<light|dark>_<key>.png`：两种主题各一份变体，按需渲染；
 * - `spec_<key>.json`：与主题无关的 spec 台账。PNG 丢了、被「清理图表缓存」删了，
 *   或者用户切换了日夜主题，都凭它原样重画，不会出现「图表已过期」的空白占位。
 *
 * [key] 只由 spec 内容和渲染版本决定，与屏幕尺寸无关：重画总是写回同一个文件名，
 * 消息里存的路径永远有效。
 *
 * 任何异常都吞掉返回 null —— 画不出图绝不能影响文字回答。
 */
abstract class FigureImageStore<T>(
    private val rootDir: () -> File,
    private val dirName: String,
    private val prefix: String,
    private val serializer: KSerializer<T>,
    /** 改过布局、字体或配色后递增，让旧 PNG 失效。 */
    private val renderVersion: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    protected abstract fun draw(spec: T, dark: Boolean): Bitmap

    /** 日志里帮助复现的 spec 摘要。 */
    protected abstract fun describe(spec: T): String

    fun dir(): File = File(rootDir(), dirName).apply { mkdirs() }

    @Synchronized
    fun render(spec: T, dark: Boolean): String? {
        val encoded = runCatching { json.encodeToString(serializer, spec) }
            .onFailure { Log.w(TAG, "$prefix spec encode failed: ${describe(spec)}", it) }
            .getOrNull() ?: return renderUncached(spec, dark)
        val key = sha256("$renderVersion:$encoded")
        val sidecar = File(dir(), "spec_$key.json")
        if (!sidecar.isFile) {
            // 台账先于 PNG 写入：即便渲染失败，之后仍有机会凭它重画
            runCatching { sidecar.writeText(encoded) }
                .onFailure { Log.w(TAG, "$prefix sidecar write failed: ${describe(spec)}", it) }
        }
        return drawTo(spec, dark, pngFile(key, dark))?.also {
            runCatching { versionFile(key, dark).writeText(renderVersion) }
        }
    }

    /**
     * 把消息里存的路径解析成**当前主题**下可用的 PNG：
     * 主题和渲染版本均匹配才复用 PNG；否则凭台账重画，历史消息路径保持有效。
     */
    @Synchronized
    fun resolve(storedPath: String, dark: Boolean): String? {
        val name = File(storedPath).name
        val key = keyOf(name)
        if (key == null) {
            // 不是本 store 命名的文件（例如 spec 编码失败时的一次性 PNG），只能原样使用
            return storedPath.takeIf { isUsable(File(it)) }
        }
        val target = pngFile(key, dark)
        if (isUsable(target) && runCatching { versionFile(key, dark).readText() }.getOrNull() == renderVersion) {
            return target.absolutePath
        }
        val sidecar = File(dir(), "spec_$key.json")
        if (!sidecar.isFile) {
            return storedPath.takeIf { isUsable(File(it)) }
        }
        val encoded = runCatching { sidecar.readText() }.getOrNull()
            ?: return storedPath.takeIf { isUsable(File(it)) }
        val stale = key != sha256("$renderVersion:$encoded")
        val spec = runCatching { json.decodeFromString(serializer, encoded) }
            .onFailure { Log.w(TAG, "$prefix sidecar decode failed: ${sidecar.name}", it) }
            .getOrNull() ?: return storedPath.takeIf { isUsable(File(it)) }
        return drawTo(spec, dark, target, forceRefresh = stale)?.also {
            runCatching { versionFile(key, dark).writeText(renderVersion) }
        } ?: storedPath.takeIf { isUsable(File(it)) }
    }

    /** 是否是本 store 生成的文件路径。 */
    fun owns(path: String): Boolean = keyOf(File(path).name) != null

    /** PNG 占用字节数（台账很小，不计入）。 */
    fun pngBytes(): Long = dir().listFiles()?.filter { it.name.endsWith(".png") }?.sumOf { it.length() } ?: 0L

    /** 删掉所有 PNG，保留台账；之后显示时会按需重画。返回删掉的字节数。 */
    @Synchronized
    fun clearPngs(): Long {
        var freed = 0L
        dir().listFiles()?.filter { it.name.endsWith(".png") }?.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /**
     * 删掉没有任何消息引用、且早于 [cutoffMs] 的 PNG 与台账；返回删掉的字节数。
     * 同一 key 的日 / 夜变体与台账同进退：消息只存其中一个变体的路径。
     * [dryRun] 时只统计不删。
     */
    @Synchronized
    fun retainOnly(referencedNames: Set<String>, cutoffMs: Long, dryRun: Boolean = false): Long {
        val keys = referencedNames.mapNotNull(::keyOf).toSet()
        var freed = 0L
        dir().listFiles()?.filter { it.isFile && it.lastModified() < cutoffMs }?.forEach { file ->
            val key = keyOf(file.name) ?: Regex("^spec_([0-9a-f]{64})\\.json$").find(file.name)?.groupValues?.get(1)
                ?: Regex("^render_(?:light|dark)_([0-9a-f]{64})\\.version$").find(file.name)?.groupValues?.get(1)
            val referenced = if (key != null) key in keys else file.name in referencedNames
            if (!referenced) {
                val size = file.length()
                if (dryRun || file.delete()) freed += size
            }
        }
        return freed
    }

    private fun renderUncached(spec: T, dark: Boolean): String? {
        val file = File(dir(), "${prefix}_once_${System.nanoTime()}.png")
        return drawTo(spec, dark, file)
    }

    private fun drawTo(spec: T, dark: Boolean, file: File, forceRefresh: Boolean = false): String? {
        if (!forceRefresh && isUsable(file)) return file.absolutePath
        return runCatching {
            val bitmap = draw(spec, dark)
            // 先写临时文件再改名：进程在写一半时被杀，不会留下半截 PNG 被当成缓存命中
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(tmp).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } finally {
                bitmap.recycle()
            }
            if (tmp.length() <= 0L || !(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file)))) {
                tmp.delete()
                Log.w(TAG, "$prefix png empty or rename failed: ${describe(spec)}")
                return@runCatching null
            }
            file.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "$prefix render failed: ${describe(spec)}", error)
            runCatching { File(file.parentFile, file.name + ".tmp").delete() }
        }.getOrNull()
    }

    private fun pngFile(key: String, dark: Boolean): File =
        File(dir(), "${prefix}_${if (dark) "dark" else "light"}_$key.png")

    // Keep old message paths stable, but redraw each theme once after a renderer upgrade.
    private fun versionFile(key: String, dark: Boolean): File =
        File(dir(), "render_${if (dark) "dark" else "light"}_$key.version")

    private fun keyOf(fileName: String): String? {
        val match = Regex("^${Regex.escape(prefix)}_(?:light|dark)_([0-9a-f]{64})\\.png$").find(fileName)
        return match?.groupValues?.get(1)
    }

    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "FigureImageStore"
    }
}
