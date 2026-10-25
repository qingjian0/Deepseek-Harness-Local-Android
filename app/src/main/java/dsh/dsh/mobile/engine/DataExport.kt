package dsh.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 数据导出（issue #31 第一步：只导出，不导入）。
 *
 * ## 包里有什么
 *   manifest.json                  schemaVersion / 版本号 / 导出时间 / 各目录文件数
 *   sessions/...                   对话（dsh-home/sessions，通常是指向公开目录的软链）
 *   workspaces/...                 工作区文件
 *   storages/workspace.json        工作区注册表 —— 漏了它，恢复后会话文件在、列表里没有
 *
 * ## 刻意不包含
 *   · profiles/（API key、登录态）：zip 会被随手转发，凭据不能默认进包；
 *   · engine/extensions/：体积大、可重装；
 *   · node_modules/：可由包管理器重建，且动辄数万文件；
 *   · 软链：只记录跳过数量，绝不跟随（防把链指向的外部内容打进包）。
 *
 * ## 失败策略
 * 任何一个文件读不出来就整体失败并抛出，由调用方删掉半成品 —— 宁可让用户看到
 * 「导出失败：<路径>」，也不能给出一个「看起来成功、其实缺东西」的备份。
 * 每一步都写进 engine.log（前缀 `[export]`），用户反馈时导出日志即可定位。
 *
 * 只读操作：不停引擎、不改任何文件。引擎运行中追加写入的会话，导出的是当时的快照。
 */
object DataExport {

    const val SCHEMA_VERSION = 1

    private const val TAG = "DataExport"
    private const val REGISTRY = "storages/workspace.json"
    private val DIRS = listOf("sessions", "workspaces")

    data class Summary(
        val sessionFiles: Int,
        val workspaceFiles: Int,
        val hasRegistry: Boolean,
        val skippedLinks: Int,
        val bytes: Long,
    )

    class ExportException(message: String) : IOException(message)

    /**
     * 把用户数据写成 zip 到 [out]（调用方负责打开与关闭 [out]）。
     * 同步执行，调用方放到后台线程。
     *
     * @param onProgress 已写入文件数 / 文件总数；每个文件回调一次，UI 侧自行节流
     */
    fun export(
        ctx: Context,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Summary {
        val home = EngineConfig.dshHome(ctx)
        log(ctx, "start: home=${home.absolutePath}")
        try {
            // 1) 先列清单：总数驱动进度，计数写进 manifest。目录项以 "/" 结尾（保留空目录）。
            val entries = mutableListOf<Pair<String, File>>()
            val counts = mutableMapOf<String, Int>()
            var skippedLinks = 0
            for (dir in DIRS) {
                val root = File(home, dir)
                // walkNoFollow 对列不出内容的目录是静默跳过的；导出不能接受「看似成功、其实缺数据」，
                // 所以悬空软链 / 读不了的目录在这里显式报错。
                if (EngineConfig.isSymbolicLink(root) && !root.exists()) {
                    throw ExportException("$dir/ points to a missing location")
                }
                if (root.exists() && root.list() == null) throw ExportException("cannot list $dir/")
                var n = 0
                val complete = EngineConfig.walkNoFollow(
                    root,
                    skipDir = { it.name == "node_modules" },
                ) { f, link ->
                    val rel = "$dir/" + f.relativeTo(root).invariantSeparatorsPath
                    when {
                        link -> skippedLinks++
                        f.isDirectory -> {
                            if (f.list() == null) throw ExportException("cannot list $rel/")
                            entries += "$rel/" to f
                        }
                        f.isFile -> { entries += rel to f; n++ }
                    }
                }
                if (!complete) throw ExportException("too many entries under $dir/")
                counts[dir] = n
                log(
                    ctx,
                    "scan $dir: $n file(s) (path=${root.absolutePath}, " +
                        "symlink=${EngineConfig.isSymbolicLink(root)})",
                )
            }
            val registry = File(home, REGISTRY).takeIf { it.isFile }
            if (registry != null) {
                entries += REGISTRY to registry
            } else {
                log(ctx, "warning: $REGISTRY not found; exporting without the workspace registry")
            }
            val total = entries.count { !it.first.endsWith("/") }

            val manifest = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("appVersion", appVersion(ctx))
                .put("engineVersion", engineVersion(ctx))
                .put("exportedAt", java.time.Instant.now().toString())
                .put(
                    "counts",
                    JSONObject()
                        .put("sessions", counts["sessions"] ?: 0)
                        .put("workspaces", counts["workspaces"] ?: 0)
                        .put("workspaceRegistry", if (registry != null) 1 else 0),
                )
                .put("skippedSymlinks", skippedLinks)
                .put(
                    "excluded",
                    JSONArray(listOf("profiles/", "engine/extensions/", "node_modules/", "symlinks")),
                )

            // 2) 写包。会话文件本身已是 zstd 压缩，用最快档位即可。
            var done = 0
            var bytes = 0L
            val zip = ZipOutputStream(out.buffered())
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            onProgress(0, total)
            for ((name, f) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                if (!name.endsWith("/")) {
                    try {
                        f.inputStream().use { bytes += it.copyTo(zip) }
                    } catch (e: IOException) {
                        throw ExportException("cannot read $name: ${e.message}")
                    }
                    onProgress(++done, total)
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()

            val summary = Summary(
                sessionFiles = counts["sessions"] ?: 0,
                workspaceFiles = counts["workspaces"] ?: 0,
                hasRegistry = registry != null,
                skippedLinks = skippedLinks,
                bytes = bytes,
            )
            log(ctx, "done: $summary")
            return summary
        } catch (e: Throwable) {
            log(ctx, "FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** 上游 dsh 包版本（如 0.2.1-rc.2）；运行时未安装时为 "?"。 */
    private fun engineVersion(ctx: Context): String = runCatching {
        val pkg = File(EngineConfig.engineRoot(ctx), "lib/node_modules/@deepseek-ai/dsh/package.json")
        JSONObject(pkg.readText()).getString("version")
    }.getOrNull() ?: "?"

    private fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        EngineLog.append(File(EngineConfig.engineRoot(ctx), "engine.log"), "[export] $msg\n")
    }
}
