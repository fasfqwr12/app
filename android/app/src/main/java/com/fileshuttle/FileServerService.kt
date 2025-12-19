package com.fileshuttle

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FileServerService : Service() {
    
    private var server: FileServer? = null
    private val PORT = 8080
    private val CHANNEL_ID = "FileShuttleChannel"
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)
        
        server = FileServer(PORT)
        server?.start()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Shuttle 服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Shuttle")
            .setContentText("文件服务运行中")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    inner class FileServer(port: Int) : NanoHTTPD(port) {
        
        private val rootDir = Environment.getExternalStorageDirectory()
        private val uploadDir = File(rootDir, "Download/FileShuttle")
        private val uploadSessions = ConcurrentHashMap<String, UploadSession>()
        
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
        private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v")
        private val SCAN_DIRS = listOf("DCIM", "Pictures", "Download", "tencent/MicroMsg/WeiXin", "Movies", "Camera", "Screenshots")
        
        data class UploadSession(
            val filename: String,
            val size: Long,
            var offset: Long,
            val tempPath: String,
            val finalPath: String
        )
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            
            // CORS 预检
            if (method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "").apply {
                    addCorsHeaders(this)
                }
            }
            
            return try {
                val response = when {
                    uri == "/" -> serveHtml()
                    uri == "/api/status" -> serveStatus()
                    uri == "/api/grouped" -> serveGrouped(session)
                    uri == "/api/files" -> serveFiles(session)
                    uri == "/api/file-info" -> serveFileInfo(session)
                    uri.startsWith("/api/thumb") -> serveThumb(session)
                    uri.startsWith("/api/download") -> serveDownload(session)
                    uri == "/api/upload/create" && method == Method.POST -> handleUploadCreate(session)
                    uri.startsWith("/api/upload/") && method == Method.PATCH -> handleUploadChunk(session)
                    uri.startsWith("/api/upload/") && method == Method.HEAD -> handleUploadStatus(session)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
                addCorsHeaders(response)
                response
            } catch (e: Exception) {
                e.printStackTrace()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
            }
        }
        
        private fun addCorsHeaders(response: Response) {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, HEAD, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range, Upload-Offset, Upload-Length, Tus-Resumable")
            response.addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Upload-Offset, Accept-Ranges, X-File-Size")
        }
        
        private fun serveStatus(): Response {
            // 扫描媒体文件
            var imageCount = 0
            var videoCount = 0
            var totalSize = 0L
            
            for (scanDir in SCAN_DIRS) {
                val dir = File(rootDir, scanDir)
                if (!dir.exists()) continue
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && !file.name.startsWith(".")) {
                        val ext = file.extension.lowercase()
                        when {
                            ext in IMAGE_EXT -> { imageCount++; totalSize += file.length() }
                            ext in VIDEO_EXT -> { videoCount++; totalSize += file.length() }
                        }
                    }
                }
            }
            
            val json = JSONObject().apply {
                put("ready", true)
                put("scanning", false)
                put("images", imageCount)
                put("videos", videoCount)
                put("totalSize", totalSize)
                put("version", "2.0.0")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
        
        private fun serveGrouped(session: IHTTPSession): Response {
            val params = session.parms
            val type = params["type"] ?: "image"
            val targetExt = if (type == "video") VIDEO_EXT else IMAGE_EXT
            
            val files = mutableListOf<JSONObject>()
            
            for (scanDir in SCAN_DIRS) {
                val dir = File(rootDir, scanDir)
                if (!dir.exists()) continue
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && !file.name.startsWith(".")) {
                        val ext = file.extension.lowercase()
                        if (ext in targetExt) {
                            files.add(JSONObject().apply {
                                put("name", file.name)
                                put("path", file.absolutePath.removePrefix(rootDir.absolutePath))
                                put("size", file.length())
                                put("time", file.lastModified())
                                put("ext", ext)
                            })
                        }
                    }
                }
            }
            
            // 按时间排序
            files.sortByDescending { it.getLong("time") }
            
            // 按日期分组
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val todayStart = now - (now % dayMs)
            
            val groups = mapOf(
                "today" to mutableListOf<JSONObject>(),
                "yesterday" to mutableListOf<JSONObject>(),
                "week" to mutableListOf<JSONObject>(),
                "month" to mutableListOf<JSONObject>(),
                "older" to mutableListOf<JSONObject>()
            )
            
            for (f in files) {
                val time = f.getLong("time")
                val diff = (todayStart - time) / dayMs
                when {
                    time >= todayStart -> groups["today"]!!.add(f)
                    diff < 1 -> groups["yesterday"]!!.add(f)
                    diff < 7 -> groups["week"]!!.add(f)
                    diff < 30 -> groups["month"]!!.add(f)
                    else -> groups["older"]!!.add(f)
                }
            }
            
            val labels = mapOf(
                "today" to "今天",
                "yesterday" to "昨天", 
                "week" to "本周",
                "month" to "本月",
                "older" to "更早"
            )
            
            val result = JSONObject()
            for ((key, list) in groups) {
                result.put(key, JSONObject().apply {
                    put("label", labels[key])
                    put("files", JSONArray(list))
                    put("count", list.size)
                    put("totalSize", list.sumOf { it.getLong("size") })
                })
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        }
        
        private fun serveFiles(session: IHTTPSession): Response {
            val params = session.parms
            val path = params["path"] ?: "/"
            val dir = File(rootDir, path.trimStart('/'))
            
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", 
                    """{"files":[],"error":"Not a directory"}""")
            }
            
            val files = (dir.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { file ->
                    JSONObject().apply {
                        put("name", file.name)
                        put("path", file.absolutePath.removePrefix(rootDir.absolutePath))
                        put("size", if (file.isDirectory) 0 else file.length())
                        put("isDir", file.isDirectory)
                        put("time", file.lastModified())
                        put("ext", if (file.isDirectory) "" else file.extension.lowercase())
                    }
                }
            
            val result = JSONObject().apply {
                put("files", JSONArray(files))
                put("path", path)
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        }
        
        private fun serveFileInfo(session: IHTTPSession): Response {
            val params = session.parms
            val path = params["path"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Missing path"}""")
            
            val file = File(rootDir, path.trimStart('/'))
            if (!file.exists() || file.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", 
                    """{"error":"File not found"}""")
            }
            
            val json = JSONObject().apply {
                put("name", file.name)
                put("path", path)
                put("size", file.length())
                put("time", file.lastModified())
                put("mime", getMimeType(file.name))
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
        
        private fun serveThumb(session: IHTTPSession): Response {
            val params = session.parms
            val path = params["path"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing path")
            
            val file = File(rootDir, path.trimStart('/'))
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            
            // 返回原图前 500KB
            val maxSize = 500 * 1024L
            val size = minOf(file.length(), maxSize)
            val fis = FileInputStream(file)
            
            val response = newFixedLengthResponse(Response.Status.OK, getMimeType(file.name), fis, size)
            response.addHeader("Cache-Control", "max-age=604800")
            return response
        }
        
        private fun serveDownload(session: IHTTPSession): Response {
            val params = session.parms
            val path = params["path"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing path")
            
            val file = File(rootDir, path.trimStart('/'))
            if (!file.exists() || file.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            
            val fileSize = file.length()
            val rangeHeader = session.headers["range"]
            
            // 断点续传
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.removePrefix("bytes=")
                val parts = range.split("-")
                val start = parts[0].toLongOrNull() ?: 0
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) 
                    minOf(parts[1].toLong(), fileSize - 1) else fileSize - 1
                
                val length = end - start + 1
                val raf = RandomAccessFile(file, "r")
                raf.seek(start)
                
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, 
                    getMimeType(file.name),
                    RafInputStream(raf, length),
                    length
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("X-File-Size", fileSize.toString())
                addContentDisposition(response, file.name)
                return response
            }
            
            // 完整下载
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, getMimeType(file.name), fis, fileSize)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("X-File-Size", fileSize.toString())
            addContentDisposition(response, file.name)
            return response
        }
        
        private fun addContentDisposition(response: Response, filename: String) {
            try {
                filename.toByteArray(Charsets.US_ASCII)
                response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
            } catch (e: Exception) {
                val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
            }
        }
        
        private fun handleUploadCreate(session: IHTTPSession): Response {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                session.inputStream.read(buffer)
                String(buffer)
            } else "{}"
            
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val filename = json.optString("filename", "upload_${System.currentTimeMillis()}")
            val fileSize = json.optLong("size", 0)
            
            uploadDir.mkdirs()
            
            val sessionId = UUID.randomUUID().toString().take(16)
            val tempPath = File(uploadDir, "$filename.part").absolutePath
            val finalPath = File(uploadDir, filename).absolutePath
            
            uploadSessions[sessionId] = UploadSession(filename, fileSize, 0, tempPath, finalPath)
            
            // 创建空文件
            File(tempPath).createNewFile()
            
            val response = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
            response.addHeader("Location", "/api/upload/$sessionId")
            response.addHeader("Upload-Offset", "0")
            response.addHeader("Tus-Resumable", "1.0.0")
            return response
        }
        
        private fun handleUploadChunk(session: IHTTPSession): Response {
            val sessionId = session.uri.removePrefix("/api/upload/")
            val uploadSession = uploadSessions[sessionId] 
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Session not found")
            
            val offset = session.headers["upload-offset"]?.toLongOrNull() ?: 0
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            
            if (offset != uploadSession.offset) {
                return newFixedLengthResponse(Response.Status.CONFLICT, "text/plain", 
                    "Offset mismatch: expected ${uploadSession.offset}")
            }
            
            // 写入数据
            if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = session.inputStream.read(buffer, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                
                RandomAccessFile(uploadSession.tempPath, "rw").use { raf ->
                    raf.seek(offset)
                    raf.write(buffer, 0, read)
                }
                
                uploadSession.offset = offset + read
            }
            
            // 检查完成
            val response = if (uploadSession.offset >= uploadSession.size) {
                File(uploadSession.tempPath).renameTo(File(uploadSession.finalPath))
                uploadSessions.remove(sessionId)
                newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                    addHeader("X-Upload-Complete", "true")
                }
            } else {
                newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            }
            
            response.addHeader("Upload-Offset", uploadSession.offset.toString())
            response.addHeader("Tus-Resumable", "1.0.0")
            return response
        }
        
        private fun handleUploadStatus(session: IHTTPSession): Response {
            val sessionId = session.uri.removePrefix("/api/upload/")
            val uploadSession = uploadSessions[sessionId]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Session not found")
            
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            response.addHeader("Upload-Offset", uploadSession.offset.toString())
            response.addHeader("Upload-Length", uploadSession.size.toString())
            response.addHeader("Tus-Resumable", "1.0.0")
            return response
        }
        
        private fun getMimeType(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }
        
        // RandomAccessFile 输入流包装
        inner class RafInputStream(private val raf: RandomAccessFile, private val length: Long) : java.io.InputStream() {
            private var remaining = length
            
            override fun read(): Int {
                if (remaining <= 0) return -1
                remaining--
                return raf.read()
            }
            
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = raf.read(b, off, toRead)
                if (n > 0) remaining -= n
                return n
            }
            
            override fun close() {
                raf.close()
            }
        }
        
        private fun serveHtml(): Response {
            val html = EMBEDDED_HTML
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        companion object {
            const val EMBEDDED_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>File Shuttle</title>
<style>
:root{--bg:#f5f5f5;--card:#fff;--border:#e5e7eb;--text:#333;--text2:#888;--primary:#6366f1;--primary-hover:#818cf8;--success:#22c55e;--error:#ef4444}
*{margin:0;padding:0;box-sizing:border-box}
html,body{height:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text)}
.app{display:flex;flex-direction:column;height:100%;max-width:1400px;margin:0 auto}
header{padding:12px 16px;background:var(--card);border-bottom:1px solid var(--border);display:flex;justify-content:space-between;align-items:center;position:sticky;top:0;z-index:100;box-shadow:0 1px 3px rgba(0,0,0,.1)}
.logo{display:flex;align-items:center;gap:8px;font-size:18px;font-weight:600;color:var(--primary)}
.status{font-size:13px;color:var(--text2)}
.status-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--success);margin-right:6px;animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}
.tabs{display:flex;gap:4px;padding:8px 16px;background:var(--card);border-bottom:1px solid var(--border);overflow-x:auto}
.tab{padding:8px 16px;background:transparent;border:none;color:var(--text2);cursor:pointer;border-radius:6px;font-size:14px;white-space:nowrap}
.tab:hover{background:var(--border)}
.tab.active{background:var(--primary);color:#fff}
.toolbar{display:flex;justify-content:space-between;align-items:center;padding:8px 16px;background:var(--card);border-bottom:1px solid var(--border);flex-wrap:wrap;gap:8px;position:sticky;top:52px;z-index:99}
.breadcrumb{display:flex;align-items:center;gap:4px;font-size:13px;flex-wrap:wrap;color:var(--text2)}
.breadcrumb span{color:var(--primary);cursor:pointer}
.breadcrumb span:hover{text-decoration:underline}
.actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.actions span{color:var(--text2);font-size:13px}
button{padding:6px 12px;border:none;border-radius:6px;font-size:13px;cursor:pointer;transition:all .2s}
.btn-primary{background:var(--primary);color:#fff}
.btn-primary:hover{background:var(--primary-hover)}
.btn-primary:disabled{background:var(--border);color:var(--text2);cursor:not-allowed}
.btn-secondary{background:var(--border);color:var(--text)}
.btn-secondary:hover{background:#d1d5db}
.content{flex:1;overflow-y:auto;padding:12px}
.group{margin-bottom:16px}
.group-header{display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background:var(--card);border-radius:8px;margin-bottom:8px;box-shadow:0 1px 2px rgba(0,0,0,.05)}
.group-title{font-weight:600;font-size:14px}
.group-meta{color:var(--text2);font-size:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:8px}
.card{background:var(--card);border-radius:8px;overflow:hidden;cursor:pointer;position:relative;aspect-ratio:1;border:2px solid transparent;transition:all .15s;box-shadow:0 1px 2px rgba(0,0,0,.05)}
.card:hover{border-color:var(--border);transform:translateY(-2px)}
.card.selected{border-color:var(--primary);box-shadow:0 0 0 2px rgba(99,102,241,.2)}
.card img{width:100%;height:100%;object-fit:cover}
.card-icon{width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:36px;background:var(--border)}
.card-check{position:absolute;top:6px;right:6px;width:20px;height:20px;background:rgba(0,0,0,.4);border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:11px;color:#fff;opacity:0;transition:opacity .15s}
.card:hover .card-check,.card.selected .card-check{opacity:1}
.card.selected .card-check{background:var(--primary)}
.card-size{position:absolute;bottom:4px;right:4px;background:rgba(0,0,0,.6);color:#fff;padding:2px 6px;border-radius:4px;font-size:10px}
.card-name{position:absolute;bottom:4px;left:4px;right:40px;background:rgba(0,0,0,.6);color:#fff;padding:2px 6px;border-radius:4px;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.empty{text-align:center;padding:40px 16px;color:var(--text2)}
.download-panel{position:fixed;bottom:0;left:0;right:0;background:var(--card);border-top:1px solid var(--border);padding:12px 16px;transform:translateY(100%);transition:transform .3s;max-height:250px;overflow-y:auto;box-shadow:0 -2px 10px rgba(0,0,0,.1)}
.download-panel.show{transform:translateY(0)}
.download-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}
.download-item{margin-bottom:8px;padding:10px;background:var(--bg);border-radius:6px}
.download-name{font-size:12px;margin-bottom:6px;display:flex;justify-content:space-between}
.progress-bar{height:4px;background:var(--border);border-radius:2px;overflow:hidden}
.progress-fill{height:100%;background:var(--primary);transition:width .2s}
.progress-fill.complete{background:var(--success)}
.progress-fill.error{background:var(--error)}
.download-stats{display:flex;justify-content:space-between;margin-top:4px;font-size:11px;color:var(--text2)}
.upload-zone{border:2px dashed var(--border);border-radius:12px;padding:40px 20px;text-align:center;margin:16px;transition:all .2s;background:var(--card)}
.upload-zone.dragover{border-color:var(--primary);background:rgba(99,102,241,.05)}
.upload-zone input{display:none}
.load-more{text-align:center;padding:12px}
@media(max-width:480px){.grid{grid-template-columns:repeat(auto-fill,minmax(80px,1fr));gap:6px}.toolbar{flex-direction:column;align-items:stretch}.actions{justify-content:space-between}}
</style>
</head>
<body>
<div class="app">
<header>
<div class="logo">📁 File Shuttle</div>
<div class="status" id="status"><span class="status-dot"></span>连接中...</div>
</header>
<div class="tabs">
<button class="tab active" data-tab="image">🖼️ 图片</button>
<button class="tab" data-tab="video">🎬 视频</button>
<button class="tab" data-tab="browse">📂 浏览</button>
<button class="tab" data-tab="upload">⬆️ 上传</button>
</div>
<div class="toolbar">
<div class="breadcrumb" id="breadcrumb"></div>
<div class="actions">
<span id="selectInfo">已选 0 项</span>
<button class="btn-secondary" onclick="app.selectVisible()">选当前</button>
<button class="btn-secondary" onclick="app.selectAllFiles()">全选</button>
<button class="btn-secondary" onclick="app.clearSelection()">清空</button>
<button class="btn-primary" id="downloadBtn" onclick="app.downloadSelected()" disabled>下载</button>
</div>
</div>
<div class="content" id="content"></div>
<div class="download-panel" id="downloadPanel">
<div class="download-header"><strong>下载进度</strong><button class="btn-secondary" onclick="app.clearDownloads()">清空</button></div>
<div id="downloadList"></div>
</div>
</div>
<script>
class App{
constructor(){this.api=location.origin;this.tab='image';this.selected=new Set();this.grouped={};this.browsePath='/';this.groupLimits={};this.init()}
init(){document.querySelectorAll('.tab').forEach(t=>{t.onclick=()=>this.switchTab(t.dataset.tab)});this.checkStatus()}
async checkStatus(){try{const r=await fetch(this.api+'/api/status');const s=await r.json();document.getElementById('status').innerHTML='<span class="status-dot"></span>'+s.images+' 图片, '+s.videos+' 视频';this.switchTab('image')}catch(e){document.getElementById('status').innerHTML='<span style="color:var(--error)">连接失败</span>'}}
async switchTab(tab){this.tab=tab;document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('active',t.dataset.tab===tab));this.selected.clear();this.updateSelectInfo();if(tab==='browse')this.loadDirectory('/');else if(tab==='upload')this.showUploadUI();else this.loadGrouped(tab)}
async loadGrouped(type){document.getElementById('content').innerHTML='<div class="empty">加载中...</div>';document.getElementById('breadcrumb').innerHTML='';const r=await fetch(this.api+'/api/grouped?type='+type);this.grouped=await r.json();this.groupLimits={};this.renderGrouped()}
renderGrouped(){const order=['today','yesterday','week','month','older'];const LIMIT=100;let html='';for(const key of order){const g=this.grouped[key];if(!g||!g.files||g.files.length===0)continue;const limit=this.groupLimits[key]||LIMIT;const visible=g.files.slice(0,limit);const hasMore=g.files.length>limit;html+='<div class="group"><div class="group-header"><span class="group-title">'+g.label+' ('+g.count+')</span><span class="group-meta">'+this.formatSize(g.totalSize)+'</span></div><div class="grid">'+visible.map(f=>this.renderCard(f)).join('')+'</div>'+(hasMore?'<div class="load-more"><button class="btn-secondary" onclick="app.loadMore(\''+key+'\')">加载更多 (还有 '+(g.files.length-limit)+' 个)</button></div>':'')+'</div>'}document.getElementById('content').innerHTML=html||'<div class="empty">没有文件</div>';this.bindCards()}
loadMore(key){this.groupLimits[key]=(this.groupLimits[key]||100)+200;this.renderGrouped()}
renderCard(f){const sel=this.selected.has(f.path)?'selected':'';const isImg=this.tab==='image';const icon=isImg?'🖼️':'🎬';const content=isImg?'<img src="'+this.api+'/api/thumb?path='+encodeURIComponent(f.path)+'" loading="lazy" onerror="this.parentElement.innerHTML=\'<div class=card-icon>'+icon+'</div>\'">':'<div class="card-icon">'+icon+'</div>';return '<div class="card '+sel+'" data-path="'+f.path+'" data-size="'+f.size+'">'+content+'<div class="card-check">'+(sel?'✓':'')+'</div><div class="card-size">'+this.formatSize(f.size)+'</div></div>'}
bindCards(){document.querySelectorAll('.card[data-path]').forEach(c=>{if(c.dataset.isdir==='true')return;c.onclick=()=>this.toggleSelect(c)})}
toggleSelect(card){const path=card.dataset.path;if(this.selected.has(path)){this.selected.delete(path);card.classList.remove('selected');card.querySelector('.card-check').textContent=''}else{this.selected.add(path);card.classList.add('selected');card.querySelector('.card-check').textContent='✓'}this.updateSelectInfo()}
selectVisible(){document.querySelectorAll('.card[data-path]').forEach(c=>{if(c.dataset.isdir==='true')return;this.selected.add(c.dataset.path);c.classList.add('selected');const ck=c.querySelector('.card-check');if(ck)ck.textContent='✓'});this.updateSelectInfo()}
selectAllFiles(){if(this.tab==='image'||this.tab==='video'){const order=['today','yesterday','week','month','older'];for(const key of order){const g=this.grouped[key];if(!g||!g.files)continue;for(const f of g.files)this.selected.add(f.path)}document.querySelectorAll('.card[data-path]').forEach(c=>{if(this.selected.has(c.dataset.path)){c.classList.add('selected');const ck=c.querySelector('.card-check');if(ck)ck.textContent='✓'}})}else this.selectVisible();this.updateSelectInfo()}
clearSelection(){this.selected.clear();document.querySelectorAll('.card.selected').forEach(c=>{c.classList.remove('selected');const ck=c.querySelector('.card-check');if(ck)ck.textContent=''});this.updateSelectInfo()}
updateSelectInfo(){let size=0;if((this.tab==='image'||this.tab==='video')&&this.grouped){const order=['today','yesterday','week','month','older'];for(const key of order){const g=this.grouped[key];if(!g||!g.files)continue;for(const f of g.files)if(this.selected.has(f.path))size+=f.size}}else{document.querySelectorAll('.card.selected').forEach(c=>{size+=parseInt(c.dataset.size||0)})}document.getElementById('selectInfo').textContent='已选 '+this.selected.size+' 项 ('+this.formatSize(size)+')';document.getElementById('downloadBtn').disabled=this.selected.size===0}
async loadDirectory(path){this.browsePath=path;document.getElementById('content').innerHTML='<div class="empty">加载中...</div>';const r=await fetch(this.api+'/api/files?path='+encodeURIComponent(path));const data=await r.json();this.renderBreadcrumb(path);this.renderDirectory(data.files||[])}
renderBreadcrumb(path){const parts=path.split('/').filter(p=>p);let html='<span onclick="app.loadDirectory(\'/\')">根目录</span>';let cur='';parts.forEach(p=>{cur+='/'+p;html+=' / <span onclick="app.loadDirectory(\''+cur+'\')">'+p+'</span>'});document.getElementById('breadcrumb').innerHTML=html}
renderDirectory(files){if(!files.length){document.getElementById('content').innerHTML='<div class="empty">空文件夹</div>';return}let html='<div class="grid">';files.forEach(f=>{if(f.isDir){html+='<div class="card" data-isdir="true" onclick="app.loadDirectory(\''+f.path+'\')"><div class="card-icon">📁</div><div class="card-name">'+f.name+'</div></div>'}else{const sel=this.selected.has(f.path)?'selected':'';const isImg=['jpg','jpeg','png','gif','webp'].includes(f.ext);const icon=this.getIcon(f.ext);html+='<div class="card '+sel+'" data-path="'+f.path+'" data-size="'+f.size+'">'+(isImg?'<img src="'+this.api+'/api/thumb?path='+encodeURIComponent(f.path)+'" loading="lazy">':'<div class="card-icon">'+icon+'</div>')+'<div class="card-check">'+(sel?'✓':'')+'</div><div class="card-name">'+f.name+'</div><div class="card-size">'+this.formatSize(f.size)+'</div></div>'}});html+='</div>';document.getElementById('content').innerHTML=html;this.bindCards()}
showUploadUI(){document.getElementById('breadcrumb').innerHTML='';document.getElementById('content').innerHTML='<div class="upload-zone" id="uploadZone"><input type="file" id="fileInput" multiple><div style="font-size:48px;margin-bottom:16px">📤</div><div style="font-size:16px;margin-bottom:8px">拖拽文件到这里上传</div><div style="color:var(--text2);margin-bottom:16px">或点击选择文件</div><button class="btn-primary" onclick="document.getElementById(\'fileInput\').click()">选择文件</button></div><div id="uploadList" style="padding:0 16px"></div>';const zone=document.getElementById('uploadZone');const input=document.getElementById('fileInput');zone.onclick=e=>{if(e.target===zone||e.target.tagName==='DIV')input.click()};input.onchange=()=>this.handleUpload(input.files);zone.ondragover=e=>{e.preventDefault();zone.classList.add('dragover')};zone.ondragleave=()=>zone.classList.remove('dragover');zone.ondrop=e=>{e.preventDefault();zone.classList.remove('dragover');this.handleUpload(e.dataTransfer.files)}}
async handleUpload(files){for(const file of files)await this.uploadFile(file)}
async uploadFile(file){const list=document.getElementById('uploadList');const id='up'+Date.now();list.insertAdjacentHTML('beforeend','<div class="download-item" id="'+id+'"><div class="download-name"><span>'+file.name+'</span><span class="up-status">准备中...</span></div><div class="progress-bar"><div class="progress-fill" style="width:0%"></div></div><div class="download-stats"><span class="up-progress">0%</span><span class="up-speed">-</span></div></div>');try{const createRes=await fetch(this.api+'/api/upload/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({filename:file.name,size:file.size})});const uploadUrl=createRes.headers.get('Location');const chunkSize=1024*1024;let offset=0;const startTime=Date.now();while(offset<file.size){const chunk=file.slice(offset,offset+chunkSize);await fetch(this.api+uploadUrl,{method:'PATCH',headers:{'Content-Type':'application/offset+octet-stream','Upload-Offset':offset.toString(),'Tus-Resumable':'1.0.0'},body:chunk});offset+=chunk.size;const progress=(offset/file.size*100).toFixed(1);const elapsed=(Date.now()-startTime)/1000;const speed=offset/elapsed;const el=document.getElementById(id);el.querySelector('.progress-fill').style.width=progress+'%';el.querySelector('.up-progress').textContent=progress+'%';el.querySelector('.up-speed').textContent=this.formatSize(speed)+'/s';el.querySelector('.up-status').textContent='上传中'}const el=document.getElementById(id);el.querySelector('.progress-fill').classList.add('complete');el.querySelector('.up-status').textContent='✓ 完成'}catch(e){const el=document.getElementById(id);el.querySelector('.progress-fill').classList.add('error');el.querySelector('.up-status').textContent='✗ 失败'}}
async downloadSelected(){if(this.selected.size===0)return;document.getElementById('downloadPanel').classList.add('show');const paths=[...this.selected];const queue=[...paths];const workers=[];for(let i=0;i<Math.min(3,queue.length);i++){workers.push((async()=>{while(queue.length>0){const path=queue.shift();if(path)await this.downloadFile(path)}})())}await Promise.all(workers)}
async downloadFile(path){const name=path.split('/').pop();const id='dl'+Date.now()+Math.random().toString(36).substr(2,5);const list=document.getElementById('downloadList');list.insertAdjacentHTML('afterbegin','<div class="download-item" id="'+id+'"><div class="download-name"><span>'+name+'</span><span class="dl-status">获取信息...</span></div><div class="progress-bar"><div class="progress-fill" style="width:0%"></div></div><div class="download-stats"><span class="dl-progress">0%</span><span class="dl-speed">-</span></div></div>');const el=document.getElementById(id);try{const infoRes=await fetch(this.api+'/api/file-info?path='+encodeURIComponent(path));const info=await infoRes.json();const totalSize=info.size;const MB=1024*1024;const chunkSize=totalSize>100*MB?20*MB:10*MB;const chunks=Math.ceil(totalSize/chunkSize);const concurrency=Math.min(4,chunks);const downloadedChunks=new Array(chunks);let downloadedSize=0;const startTime=Date.now();el.querySelector('.dl-status').textContent='下载中';const downloadChunk=async(i)=>{const start=i*chunkSize;const end=Math.min(start+chunkSize-1,totalSize-1);for(let attempt=0;attempt<3;attempt++){try{const res=await fetch(this.api+'/api/download?path='+encodeURIComponent(path),{headers:{'Range':'bytes='+start+'-'+end}});if(!res.ok)throw new Error('HTTP '+res.status);const data=await res.arrayBuffer();downloadedChunks[i]=data;downloadedSize+=data.byteLength;const progress=(downloadedSize/totalSize*100).toFixed(1);const elapsed=(Date.now()-startTime)/1000;const speed=downloadedSize/elapsed;el.querySelector('.progress-fill').style.width=progress+'%';el.querySelector('.dl-progress').textContent=progress+'%';el.querySelector('.dl-speed').textContent=this.formatSize(speed)+'/s';return}catch(e){if(attempt===2)throw e;await new Promise(r=>setTimeout(r,1000))}}};const q=[...Array(chunks).keys()];const ws=[];for(let w=0;w<concurrency;w++){ws.push((async()=>{while(q.length>0){const i=q.shift();if(i!==undefined)await downloadChunk(i)}})())}await Promise.all(ws);const blob=new Blob(downloadedChunks);const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),60000);el.querySelector('.progress-fill').classList.add('complete');el.querySelector('.dl-status').textContent='✓ 完成';el.querySelector('.dl-speed').textContent=this.formatSize(totalSize)}catch(e){el.querySelector('.progress-fill').classList.add('error');el.querySelector('.dl-status').textContent='✗ 失败'}}
clearDownloads(){document.getElementById('downloadList').innerHTML='';document.getElementById('downloadPanel').classList.remove('show')}
getIcon(ext){const icons={mp4:'🎬',mkv:'🎬',avi:'🎬',mov:'🎬',mp3:'🎵',flac:'🎵',wav:'🎵',pdf:'📄',doc:'📄',docx:'📄',txt:'📄',zip:'📦',rar:'📦','7z':'📦',apk:'📱'};return icons[ext]||'📄'}
formatSize(bytes){if(!bytes||bytes===0)return '0 B';const units=['B','KB','MB','GB','TB'];let i=0;while(bytes>=1024&&i<units.length-1){bytes/=1024;i++}return bytes.toFixed(i>0?1:0)+' '+units[i]}
}
const app=new App();
</script>
</body>
</html>"""
        }

    }
}
