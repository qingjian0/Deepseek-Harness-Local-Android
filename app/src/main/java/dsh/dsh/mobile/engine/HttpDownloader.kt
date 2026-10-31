package dsh.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 运行时/索引下载器（v1.4.20）。
 *
 * 能力：
 *  - URL 列表按顺序回退（MANIFEST 里已带直连 + gh-proxy 前缀版本）；
 *  - 断点续传（`.part` + Range；服务端不支持 206 时自动从头下载）；
 *  - 截断/大小/sha256 校验，失败删除临时文件并尝试下一个 URL。
 *
 * 只用于公开的运行时/索引下载，不携带任何凭据。
 */
class HttpDownloader(private val ctx: Context) {

    data class Result(val sha256: String, val bytes: Long, val url: String)

    /** 调用方主动取消（不是网络错误）：不要按"换下一个源"处理。 */
    class CancelledException : Exception("download cancelled")

    /**
     * @param urls 依次尝试的 URL（直连 / 代理）
     * @param dest 最终文件
     * @param expectedSha256 必填：没有 pin 的包不允许下载
     * @param expectedSize >0 时校验字节数
     * @param onProgress 0f..1f（拿不到 Content-Length 时只在完成时回调 1f）
     * @param shouldCancel 每读一块调用一次；返回 true 立即抛 [CancelledException]
     *        （自动更新的通知栏「取消」按钮用它。取消**不删** `.part`，下次可续传）
     */
    fun download(
        urls: List<String>,
        dest: File,
        expectedSha256: String,
        expectedSize: Long = 0,
        onProgress: (Float) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(expectedSha256.isNotBlank()) { "refusing download without SHA-256 pin" }
        require(urls.isNotEmpty()) { "no download URL" }
        val part = File(dest.parentFile, dest.name + ".part")
        var lastError: Exception? = null

        for (url in urls.distinct()) {
            try {
                val bytes = fetch(url, part, expectedSize, onProgress, shouldCancel)
                val actual = RuntimeInstaller.sha256(part)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException(
                        "checksum mismatch: expected=$expectedSha256 actual=$actual ($url)",
                    )
                }
                if (expectedSize > 0 && bytes != expectedSize) {
                    part.delete()
                    throw IllegalStateException("size mismatch: expected=$expectedSize actual=$bytes ($url)")
                }
                if (dest.exists() && !dest.delete()) dest.deleteOnExit()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Log.i(TAG, "downloaded $url -> ${dest.name} ($bytes bytes)")
                onProgress(1f)
                return Result(actual, bytes, url)
            } catch (e: CancelledException) {
                // 用户取消：保留 .part 供续传，直接向上抛，别再换源重试
                Log.i(TAG, "download cancelled: $url")
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "download failed: $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        part.delete()
        throw IllegalStateException(
            "all download URLs failed: " + (lastError?.message ?: "unknown"),
            lastError,
        )
    }

    /** 下载一次；优先续传。遇到 416 时清理失效偏移并对当前 URL 从头重试一次。 */
    private fun fetch(
        url: String,
        part: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
        shouldCancel: () -> Boolean,
    ): Long {
        var start = if (part.isFile) part.length() else 0L
        if (expectedSize > 0 && start >= expectedSize) {
            // 没有可信的完整性证明时，不能把同尺寸临时文件当作已下载完成。
            part.delete()
            start = 0L
        }

        // 416 通常意味着本地 .part 的 Range 偏移已经不被服务器接受。
        // 清除临时文件后，对同一 URL 做一次全量请求，避免所有镜像重复携带坏偏移。
        var restartedAfter416 = false
        while (true) {
            val requestStart = start
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "dsh-mobile-runtime")
                if (requestStart > 0) setRequestProperty("Range", "bytes=$requestStart-")
            }

            val code = try { conn.responseCode } catch (e: Exception) {
                conn.disconnect()
                throw e
            }

            if (requestStart > 0 && code == 416) {
                conn.disconnect()
                if (!restartedAfter416) {
                    Log.w(TAG, "server rejected resume offset=$requestStart; restarting full download: $url")
                    if (part.exists() && !part.delete()) {
                        throw java.io.IOException("cannot delete stale partial file: ${part.absolutePath}")
                    }
                    start = 0L
                    restartedAfter416 = true
                    continue
                }
                throw IllegalStateException("HTTP 416 after clean restart")
            }

            if (requestStart > 0 && code == HttpURLConnection.HTTP_OK) {
                // 服务端忽略 Range：当前响应是完整文件，必须覆盖而不是追加。
                if (part.exists() && !part.delete()) {
                    conn.disconnect()
                    throw java.io.IOException("cannot reset partial file: ${part.absolutePath}")
                }
            } else if (requestStart > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = conn.getHeaderField("Content-Range")
                val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                    .matchEntire(contentRange?.trim().orEmpty())
                val rangeStart = match?.groupValues?.get(1)?.toLongOrNull()
                if (rangeStart != requestStart) {
                    conn.disconnect()
                    throw java.io.IOException(
                        "invalid Content-Range for resume: expected=$requestStart actual=${contentRange ?: "missing"} ($url)",
                    )
                }
            }

            val rangeTotal = if (code == HttpURLConnection.HTTP_PARTIAL) {
                Regex("bytes\\s+\\d+-\\d+/(\\d+)", RegexOption.IGNORE_CASE)
                    .matchEntire(conn.getHeaderField("Content-Range")?.trim().orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            } else -1L
            val total = when {
                rangeTotal > 0 -> rangeTotal
                code == HttpURLConnection.HTTP_PARTIAL -> requestStart + conn.contentLengthLong.coerceAtLeast(0)
                expectedSize > 0 -> expectedSize
                else -> conn.contentLengthLong
            }
            var done = if (code == HttpURLConnection.HTTP_PARTIAL) requestStart else 0L
            var lastAt = 0L
            try {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, code == HttpURLConnection.HTTP_PARTIAL).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (shouldCancel()) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastAt >= 250L) {
                                lastAt = now
                                if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (total > 0 && done != total) {
                throw java.io.IOException("truncated download: $done/$total bytes ($url)")
            }
            if (expectedSize > 0 && done != expectedSize) {
                throw java.io.IOException("download size mismatch: expected=$expectedSize actual=$done ($url)")
            }
            return done
        }
    }

    companion object {
        private const val TAG = "HttpDownloader"
    }
}
