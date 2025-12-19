package com.fileshuttle

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {
    
    private var serverIntent: Intent? = null
    private var isServerRunning = false
    
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrPlaceholder: TextView
    private lateinit var urlText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var copyBtn: ImageButton
    
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startServer()
        } else {
            Toast.makeText(this, "需要存储权限才能访问文件", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 设置状态栏
        window.statusBarColor = 0xFFF2F2F7.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        
        initViews()
        setupSteps()
        setupListeners()
    }
    
    private fun initViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusHint = findViewById(R.id.statusHint)
        qrImage = findViewById(R.id.qrImage)
        qrPlaceholder = findViewById(R.id.qrPlaceholder)
        urlText = findViewById(R.id.urlText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        copyBtn = findViewById(R.id.copyBtn)
    }
    
    private fun setupSteps() {
        val steps = listOf(
            Triple("1", "启动服务", "点击下方按钮启动"),
            Triple("2", "电脑访问", "扫码或输入地址打开网页"),
            Triple("3", "传输文件", "在网页上浏览、下载、上传")
        )
        
        val stepViews = listOf(
            findViewById<View>(R.id.step1),
            findViewById<View>(R.id.step2),
            findViewById<View>(R.id.step3)
        )
        
        steps.forEachIndexed { index, (num, title, desc) ->
            stepViews[index]?.let { view ->
                view.findViewById<TextView>(R.id.stepNumber)?.text = num
                view.findViewById<TextView>(R.id.stepTitle)?.text = title
                view.findViewById<TextView>(R.id.stepDesc)?.text = desc
            }
        }
    }
    
    private fun setupListeners() {
        startBtn.setOnClickListener { checkPermissionsAndStart() }
        stopBtn.setOnClickListener { stopServer() }
        copyBtn.setOnClickListener { copyUrl() }
    }
    
    private fun checkPermissionsAndStart() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (notGranted.isEmpty()) {
            startServer()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startServer() {
        val ip = getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(this, "请先连接 WiFi", Toast.LENGTH_LONG).show()
            return
        }
        
        serverIntent = Intent(this, FileServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serverIntent)
        } else {
            startService(serverIntent)
        }
        
        isServerRunning = true
        
        val url = "http://$ip:8080"
        updateUI(true, url)
        generateQRCode(url)
    }
    
    private fun stopServer() {
        serverIntent?.let { stopService(it) }
        isServerRunning = false
        updateUI(false, null)
    }
    
    private fun updateUI(running: Boolean, url: String?) {
        if (running) {
            statusDot.setBackgroundResource(R.drawable.dot_online)
            statusText.text = "服务运行中"
            statusHint.text = "电脑可以访问了"
            urlText.text = url ?: "--"
            qrPlaceholder.visibility = View.GONE
            qrImage.visibility = View.VISIBLE
            startBtn.visibility = View.GONE
            stopBtn.visibility = View.VISIBLE
        } else {
            statusDot.setBackgroundResource(R.drawable.dot_offline)
            statusText.text = "点击启动服务"
            statusHint.text = "服务未运行"
            urlText.text = "--"
            qrPlaceholder.visibility = View.VISIBLE
            qrImage.visibility = View.GONE
            startBtn.visibility = View.VISIBLE
            stopBtn.visibility = View.GONE
        }
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            if (ip == 0) return null
            return String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun generateQRCode(text: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            qrImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun copyUrl() {
        val url = urlText.text.toString()
        if (url == "--") {
            Toast.makeText(this, "请先启动服务", Toast.LENGTH_SHORT).show()
            return
        }
        
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("File Shuttle URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServerRunning) {
            stopServer()
        }
    }
}
