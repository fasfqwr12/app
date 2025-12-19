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
import java.security.MessageDigest
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

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
    
    // HTTP 文件服务器
    inner class FileServer(port: Int) : NanoHTTPD(port) {
        
        private val rootDir = Environment.getExternalStorageDirectory()
        private val thumbCache = LinkedHashMap<String, ByteArray>(100, 0.75f, true)
        private val MAX_CACHE = 100
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            
            return try {
                when {
                    uri == "/" -> serveHtml()
                    uri == "/api/status" -> serveStatus()
                    uri == "/api/grouped" -> serveGroupedFiles(session)
                    uri.startsWith("/api/download") -> serveDownload(session)
                    uri.startsWith("/api/thumb") -> serveThumb(session)
                    uri.startsWith("/api/upload") && method == Method.POST -> handleUpload(session)
                    uri.startsWith("/api/upload") && method == Method.PATCH -> handleUploadChunk(session)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
            }
        }
        
        private fun serveStatus(): Response {
            val json = JSONObject().apply {
                put("status", "running")
                put("version", "1.0")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }
        
        private fun serveGroupedFiles(session: IHTTPSession): Response {
            val params = session.parms
            val path = params["path"] ?: ""
            val dir = if (path.isEmpty()) rootDir else File(rootDir, path)
            
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", 
                    """{"error":"Directory not found"}""")
            }
            
            val files = dir.listFiles() ?: emptyArray()
            val grouped = mutableMapOf<String, MutableList<JSONObject>>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            
            for (file in files.sortedByDescending { it.lastModified() }) {
                val dateKey = dateFormat.format(Date(file.lastModified()))
                val fileObj = JSONObject().apply {
                    put("name", file.name)
                    put("path", file.absolutePath.removePrefix(rootDir.absolutePath))
                    put("size", file.length())
                    put("isDir", file.isDirectory)
                    put("modified", file.lastModified())
                    put("ext", file.extension.lowercase())
                }
                grouped.getOrPut(dateKey) { mutableListOf() }.add(fileObj)
            }
            
            val result = JSONObject().apply {
                put("currentPath", path)
                put("parentPath", if (path.isEmpty()) null else File(path).parent ?: "")
                val groupsArray = JSONArray()
                for ((date, fileList) in grouped) {
                    val groupObj = JSONObject().apply {
                        put("date", date)
                        put("files", JSONArray(fileList))
                    }
                    groupsArray.put(groupObj)
                }
                put("groups", groupsArray)
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        }
        
        private fun serveDownload(session: IHTTPSession): Response {
            val params = session.parms
            val filePath = params["path"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing path")
            
            val file = File(rootDir, filePath)
            if (!file.exists() || file.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            
            val rangeHeader = session.headers["range"]
            val fileSize = file.length()
            val mimeType = getMimeType(file.name)
            
            // 支持 Range 请求 (断点续传)
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.removePrefix("bytes=")
                val parts = range.split("-")
                val start = parts[0].toLongOrNull() ?: 0
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) 
                    parts[1].toLong() else fileSize - 1
                
                val length = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)
                
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mimeType, fis, length)
                response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                return response
            }
            
            // 完整文件下载
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileSize)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            return response
        }
        
        private fun serveThumb(session: IHTTPSession): Response {
            val params = session.parms
            val filePath = params["path"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Missing path")
            
            val file = File(rootDir, filePath)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }
            
            // 简单返回原图前 500KB 作为预览
            val maxSize = 500 * 1024L
            val size = minOf(file.length(), maxSize)
            val fis = FileInputStream(file)
            val bytes = ByteArray(size.toInt())
            fis.read(bytes)
            fis.close()
            
            return newFixedLengthResponse(Response.Status.OK, getMimeType(file.name), 
                bytes.inputStream(), size)
        }
        
        private fun handleUpload(session: IHTTPSession): Response {
            val headers = session.headers
            val uploadLength = headers["upload-length"]?.toLongOrNull() ?: 0
            val fileName = headers["upload-metadata"]?.let { 
                decodeMetadata(it)["filename"] 
            } ?: "upload_${System.currentTimeMillis()}"
            
            // 创建上传目录
            val uploadDir = File(rootDir, "FileShuttle_Uploads")
            if (!uploadDir.exists()) uploadDir.mkdirs()
            
            val targetFile = File(uploadDir, fileName)
            val uploadId = UUID.randomUUID().toString()
            
            // 保存上传信息
            val infoFile = File(uploadDir, "$uploadId.info")
            infoFile.writeText("$fileName|$uploadLength|0")
            
            val response = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
            response.addHeader("Location", "/api/upload/$uploadId")
            response.addHeader("Upload-Offset", "0")
            return response
        }
        
        private fun handleUploadChunk(session: IHTTPSession): Response {
            val uri = session.uri
            val uploadId = uri.removePrefix("/api/upload/")
            
            val uploadDir = File(rootDir, "FileShuttle_Uploads")
            val infoFile = File(uploadDir, "$uploadId.info")
            
            if (!infoFile.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Upload not found")
            }
            
            val info = infoFile.readText().split("|")
            val fileName = info[0]
            val totalSize = info[1].toLong()
            var currentOffset = info[2].toLong()
            
            val targetFile = File(uploadDir, fileName)
            
            // 读取上传的数据
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            
            if (contentLength > 0) {
                val inputStream = session.inputStream
                val buffer = ByteArray(contentLength)
                inputStream.read(buffer)
                
                targetFile.appendBytes(buffer)
                currentOffset += contentLength
                
                // 更新进度
                infoFile.writeText("$fileName|$totalSize|$currentOffset")
            }
            
            val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            response.addHeader("Upload-Offset", currentOffset.toString())
            
            // 上传完成，删除 info 文件
            if (currentOffset >= totalSize) {
                infoFile.delete()
            }
            
            return response
        }
        
        private fun decodeMetadata(metadata: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            metadata.split(",").forEach { pair ->
                val parts = pair.trim().split(" ")
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT))
                    result[key] = value
                }
            }
            return result
        }
        
        private fun getMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }
        
        private fun serveHtml(): Response {
            val html = getEmbeddedHtml()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun getEmbeddedHtml(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>File Shuttle</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f5f5f5; color: #333; min-height: 100vh; }
        .header { background: #fff; padding: 15px 20px; position: sticky; top: 0; z-index: 100; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .header h1 { font-size: 1.2rem; color: #6366f1; }
        .path-bar { background: #fff; padding: 10px 20px; font-size: 0.9rem; color: #666; border-bottom: 1px solid #eee; }
        .path-bar a { color: #6366f1; text-decoration: none; }
        .file-group { margin: 10px 0; }
        .group-header { background: #fff; padding: 8px 20px; font-size: 0.85rem; color: #888; position: sticky; top: 60px; border-bottom: 1px solid #eee; }
        .file-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; padding: 10px 20px; }
        .file-item { background: #fff; border-radius: 8px; padding: 10px; text-align: center; cursor: pointer; transition: transform 0.2s; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .file-item:hover { transform: scale(1.05); background: #f0f0ff; }
        .file-item.selected { border: 2px solid #6366f1; }
        .file-icon { font-size: 2.5rem; margin-bottom: 5px; }
        .file-name { font-size: 0.75rem; word-break: break-all; color: #333; max-height: 2.4em; overflow: hidden; }
        .file-size { font-size: 0.65rem; color: #888; margin-top: 3px; }
        .thumb { width: 60px; height: 60px; object-fit: cover; border-radius: 4px; }
        .toolbar { position: fixed; bottom: 0; left: 0; right: 0; background: #fff; padding: 15px 20px; display: flex; gap: 10px; justify-content: center; box-shadow: 0 -2px 4px rgba(0,0,0,0.1); }
        .btn { background: #6366f1; color: #fff; border: none; padding: 12px 24px; border-radius: 25px; font-size: 1rem; cursor: pointer; }
        .btn:disabled { background: #ddd; color: #999; }
        .btn-secondary { background: #e5e7eb; color: #333; }
        .progress-bar { position: fixed; top: 0; left: 0; height: 3px; background: #6366f1; transition: width 0.3s; z-index: 200; }
        .loading { text-align: center; padding: 50px; color: #888; }
        @media (min-width: 768px) { .file-list { grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); } }
    </style>
</head>
<body>
    <div class="progress-bar" id="progressBar" style="width: 0%"></div>
    <div class="header">
        <h1>📁 File Shuttle</h1>
        <button class="btn btn-secondary" onclick="selectAll()">全选</button>
    </div>
    <div class="path-bar" id="pathBar"></div>
    <div id="content"></div>
    <div class="toolbar">
        <button class="btn" id="downloadBtn" onclick="downloadSelected()" disabled>下载选中 (0)</button>
    </div>
    
    <script>
        let currentPath = '';
        let allFiles = [];
        let selectedFiles = new Set();
        
        async function loadDirectory(path = '') {
            currentPath = path;
            document.getElementById('content').innerHTML = '<div class="loading">加载中...</div>';
            
            try {
                const res = await fetch('/api/grouped?path=' + encodeURIComponent(path));
                const data = await res.json();
                allFiles = [];
                
                // 更新路径栏
                let pathHtml = '<a href="#" onclick="loadDirectory(\\'\\')">根目录</a>';
                if (path) {
                    const parts = path.split('/').filter(p => p);
                    let accumulated = '';
                    parts.forEach(part => {
                        accumulated += '/' + part;
                        pathHtml += ' / <a href="#" onclick="loadDirectory(\\'' + accumulated + '\\')">' + part + '</a>';
                    });
                }
                document.getElementById('pathBar').innerHTML = pathHtml;
                
                // 渲染文件
                let html = '';
                data.groups.forEach(group => {
                    html += '<div class="file-group"><div class="group-header">' + group.date + '</div><div class="file-list">';
                    group.files.forEach(file => {
                        allFiles.push(file);
                        const icon = file.isDir ? '📁' : getFileIcon(file.ext);
                        const isImage = ['jpg','jpeg','png','gif','webp'].includes(file.ext);
                        const thumb = isImage ? '<img class="thumb" src="/api/thumb?path=' + encodeURIComponent(file.path) + '" onerror="this.style.display=\\'none\\'">' : '<div class="file-icon">' + icon + '</div>';
                        
                        html += '<div class="file-item" data-path="' + file.path + '" data-isdir="' + file.isDir + '" onclick="handleClick(this, event)">';
                        html += thumb;
                        html += '<div class="file-name">' + file.name + '</div>';
                        if (!file.isDir) html += '<div class="file-size">' + formatSize(file.size) + '</div>';
                        html += '</div>';
                    });
                    html += '</div></div>';
                });
                
                document.getElementById('content').innerHTML = html || '<div class="loading">空文件夹</div>';
            } catch (e) {
                document.getElementById('content').innerHTML = '<div class="loading">加载失败: ' + e.message + '</div>';
            }
        }
        
        function handleClick(el, e) {
            const path = el.dataset.path;
            const isDir = el.dataset.isdir === 'true';
            
            if (isDir) {
                loadDirectory(path);
            } else {
                el.classList.toggle('selected');
                if (el.classList.contains('selected')) {
                    selectedFiles.add(path);
                } else {
                    selectedFiles.delete(path);
                }
                updateToolbar();
            }
        }
        
        function selectAll() {
            const items = document.querySelectorAll('.file-item[data-isdir="false"]');
            const allSelected = selectedFiles.size === allFiles.filter(f => !f.isDir).length;
            
            if (allSelected) {
                selectedFiles.clear();
                items.forEach(el => el.classList.remove('selected'));
            } else {
                allFiles.filter(f => !f.isDir).forEach(f => selectedFiles.add(f.path));
                items.forEach(el => el.classList.add('selected'));
            }
            updateToolbar();
        }
        
        function updateToolbar() {
            const btn = document.getElementById('downloadBtn');
            btn.disabled = selectedFiles.size === 0;
            btn.textContent = '下载选中 (' + selectedFiles.size + ')';
        }
        
        async function downloadSelected() {
            const files = Array.from(selectedFiles);
            const progressBar = document.getElementById('progressBar');
            
            for (let i = 0; i < files.length; i++) {
                progressBar.style.width = ((i + 1) / files.length * 100) + '%';
                const path = files[i];
                const name = path.split('/').pop();
                
                try {
                    const res = await fetch('/api/download?path=' + encodeURIComponent(path));
                    const blob = await res.blob();
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url;
                    a.download = name;
                    a.click();
                    URL.revokeObjectURL(url);
                } catch (e) {
                    console.error('Download failed:', path, e);
                }
                
                await new Promise(r => setTimeout(r, 500));
            }
            
            setTimeout(() => { progressBar.style.width = '0%'; }, 1000);
        }
        
        function getFileIcon(ext) {
            const icons = {
                'mp4': '🎬', 'mkv': '🎬', 'avi': '🎬', 'mov': '🎬', 'webm': '🎬',
                'mp3': '🎵', 'wav': '🎵', 'flac': '🎵', 'aac': '🎵',
                'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️', 'webp': '🖼️',
                'pdf': '📄', 'doc': '📄', 'docx': '📄', 'txt': '📄',
                'zip': '📦', 'rar': '📦', '7z': '📦', 'tar': '📦',
                'apk': '📱', 'exe': '💿', 'dmg': '💿'
            };
            return icons[ext] || '📄';
        }
        
        function formatSize(bytes) {
            if (bytes < 1024) return bytes + ' B';
            if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
            if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
            return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
        }
        
        loadDirectory();
    </script>
</body>
</html>
        """.trimIndent()
    }
}
