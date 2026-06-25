package com.angasky.hermes

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesAI"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_NOTIFICATION = 1002
        private const val SERVER_HOST = "43.242.33.173"
        private const val SERVER_PORT = 8000
        private const val WS_URL = "ws://$SERVER_HOST:$SERVER_PORT/ws/chat"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var rvMessages: RecyclerView
    private var isListening = false
    private var ws: WebSocket? = null
    private var currentSessionId: String? = null
    
    private val messageList = mutableListOf<MessageData>()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var btnMic: FloatingActionButton
    private lateinit var btnSend: FloatingActionButton
    private lateinit var btnSettings: View
    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var vStatusDot: View
    private lateinit var llThinking: LinearLayout
    private lateinit var tvThinking: TextView
    private lateinit var pbLoading: ProgressBar

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSpeech()
        initTTS()
        checkPermissionsAndConnect()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        vStatusDot = findViewById(R.id.vStatusDot)
        llThinking = findViewById(R.id.llThinking)
        tvThinking = findViewById(R.id.tvThinking)
        pbLoading = findViewById(R.id.pbLoading)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        btnSettings = findViewById(R.id.btnSettings)
        etInput = findViewById(R.id.etInput)
        rvMessages = findViewById(R.id.rvMessages)

        rvMessages.layoutManager = LinearLayoutManager(this)
        messageAdapter = MessageAdapter(messageList)
        rvMessages.adapter = messageAdapter

        addWelcomeMessage()

        btnMic.setOnClickListener { toggleListening() }
        btnSend.setOnClickListener { sendMessageFromInput() }

        etInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP) {
                sendMessageFromInput()
                true
            } else {
                false
            }
        }

        btnSettings.setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            showSettingsDialog()
        }

        connectWebSocket(null)
    }

    private fun addWelcomeMessage() {
        messageList.add(MessageData("👋 你好！我是 Hermes AI\n\n点击麦克风开始语音对话，或直接输入文字。", false, System.currentTimeMillis()))
        messageAdapter.notifyDataSetChanged()
        rvMessages.scrollToPosition(messageList.size - 1)
    }

    private fun initSpeech() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "语音识别不可用")
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(getRecognitionListener())
            Log.d(TAG, "语音识别器初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "语音识别器初始化失败", e)
        }
    }

    private fun getRecognitionListener(): android.speech.RecognitionListener {
        return object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    tvStatus.text = "🎤 正在聆听..."
                    vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                }
                Log.d(TAG, "准备聆听")
            }

            override fun onBeginningOfSpeech() { Log.d(TAG, "开始说话") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "说话结束")
                runOnUiThread {
                    vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
                }
            }

            override fun onError(error: Int) {
                Log.e(TAG, "语音识别错误: $error")
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少权限"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到匹配"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有输入语音"
                    else -> "未知错误 ($error)"
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "语音识别出错: $errorMsg", Toast.LENGTH_SHORT).show()
                    tvStatus.text = "● 未连接"
                    vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                    isListening = false
                    btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.d(TAG, "识别结果: $text")
                    runOnUiThread {
                        etInput.setText(text)
                        sendMessageFromInput()
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "部分识别: ${matches[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun toggleListening() {
        if (isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
            }
            speechRecognizer?.startListening(intent)
            isListening = true
            btnMic.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            btnMic.backgroundTintList = android.graphics.ColorStateList.valueOf(0xFF0000.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "开始语音识别失败", e)
            Toast.makeText(this, "语音识别启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            isListening = false
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening = false
            btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
            btnMic.backgroundTintList = android.graphics.ColorStateList.valueOf(0xFFDC26.toInt())
            tvStatus.text = "● 已停止"
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别失败", e)
        }
    }

    private fun initTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.language = Locale.US
                    Log.w(TAG, "中文TTS不可用，使用英文")
                }
                Log.d(TAG, "TTS初始化成功")
            } else {
                Log.e(TAG, "TTS初始化失败: $status")
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION
                )
            }
        }

        connectWebSocket(null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "录音权限已授予")
                    if (!isListening) {
                        startListening()
                    }
                } else {
                    Log.w(TAG, "录音权限被拒绝")
                    Toast.makeText(this, "需要录音权限才能使用语音功能", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_NOTIFICATION -> {
                Log.d(TAG, "通知权限结果: ${grantResults.contentToString()}")
            }
        }
    }

    private fun connectWebSocket(message: String?) {
        try {
            ws?.close(1000, "Reconnect")
            
            val request = Request.Builder()
                .url(WS_URL)
                .header("Origin", "http://$SERVER_HOST")
                .build()

            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    this@MainActivity.ws = webSocket
                    runOnUiThread {
                        tvStatus.text = "🟢 已连接"
                        vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                    }
                    Log.d(TAG, "WebSocket已连接")
                    message?.let { sendWsMessage(it) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type", "")
                        
                        when (type) {
                            "connected" -> {
                                currentSessionId = json.optJSONObject("data")?.optString("session_id")
                                Log.d(TAG, "Session: $currentSessionId")
                            }
                            "started" -> {
                                Log.d(TAG, "消息已开始处理")
                            }
                            "thinking_step" -> {
                                val data = json.optJSONObject("data")
                                val title = data?.optString("title", "")
                                val content = data?.optString("content", "")
                                runOnUiThread {
                                    tvThinking.text = "${tvThinking.text}\n$title: $content"
                                }
                            }
                            "complete" -> {
                                val data = json.optJSONObject("data")
                                val response = data?.optString("response", "")
                                runOnUiThread {
                                    messageList.add(MessageData(response ?: "", false, System.currentTimeMillis()))
                                    messageAdapter.notifyItemInserted(messageList.size - 1)
                                    rvMessages.scrollToPosition(messageList.size - 1)
                                    
                                    llThinking.visibility = View.GONE
                                    tvStatus.text = "🟢 已连接"
                                    
                                    if (::textToSpeech.isInitialized) {
                                        textToSpeech.speak(response ?: "", TextToSpeech.QUEUE_FLUSH, null)
                                    }
                                }
                                Log.d(TAG, "回答完成")
                            }
                            "error" -> {
                                val errorMsg = json.optJSONObject("data")?.optString("error", "未知错误")
                                runOnUiThread {
                                    tvStatus.text = "❌ 错误"
                                    vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                                    llThinking.visibility = View.GONE
                                    Toast.makeText(this@MainActivity, "错误: $errorMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析WebSocket消息失败", e)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    runOnUiThread {
                        tvStatus.text = "⚪ 已断开"
                        vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
                    }
                    Log.w(TAG, "WebSocket关闭: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    Log.e(TAG, "WebSocket连接失败", t)
                    runOnUiThread {
                        tvStatus.text = "❌ 连接失败"
                        vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                        llThinking.visibility = View.GONE
                        rvMessages.postDelayed({ connectWebSocket(null) }, 3000)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "创建WebSocket失败", e)
            runOnUiThread {
                tvStatus.text = "❌ 创建连接失败"
                Toast.makeText(this, "连接创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendWsMessage(message: String) {
        try {
            val json = JSONObject().apply {
                put("type", "message")
                put("data", message)
            }
            ws?.send(json.toString())
            Log.d(TAG, "发送消息: $message")
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
            Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessageFromInput() {
        val message = etInput.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show()
            return
        }

        messageList.add(MessageData(message, true, System.currentTimeMillis()))
        messageAdapter.notifyItemInserted(messageList.size - 1)
        rvMessages.scrollToPosition(messageList.size - 1)
        etInput.text.clear()

        runOnUiThread {
            tvStatus.text = "🟡 发送中..."
            vStatusDot.setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
            llThinking.visibility = View.VISIBLE
            tvThinking.text = "💭 正在思考..."
            pbLoading.visibility = View.VISIBLE
        }

        try {
            sendWsMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "发送消息异常", e)
            runOnUiThread {
                messageList.add(MessageData("❌ 发送失败: ${e.message}", false, System.currentTimeMillis()))
                messageAdapter.notifyDataSetChanged()
                llThinking.visibility = View.GONE
            }
        }
    }

    private fun showSettingsDialog() {
        Log.d(TAG, "显示设置对话框")
        AlertDialog.Builder(this)
            .setTitle("⚙️ 设置")
            .setMessage("""
                Hermes AI 设置
                
                • 服务器地址: $SERVER_HOST
                • 端口: $SERVER_PORT
                • 版本: 0.2.0
                
                功能开关:
                [ ] 语音播报回复
                [ ] 显示思考过程
                [ ] 自动连接服务器
            """.trimIndent())
            .setPositiveButton("确定") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("关于") { _, _ ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("关于 Hermes AI")
                    .setMessage("""
                        Hermes AI v0.2.0
                        
                        智能AI助手应用
                        支持文字和语音对话
                        
                        © 2026 Angasky
                    """.trimIndent())
                    .setPositiveButton("关闭") { d, _ -> d.dismiss() }
                    .create().show()
            }
            .setCancelable(true)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ws?.close(1000, "Activity destroyed") } catch (e: Exception) { Log.e(TAG, "关闭WebSocket失败", e) }
        try { speechRecognizer?.destroy() } catch (e: Exception) { Log.e(TAG, "销毁语音识别器失败", e) }
        try { textToSpeech.shutdown() } catch (e: Exception) { Log.e(TAG, "关闭TTS失败", e) }
    }
}

data class MessageData(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

class MessageAdapter(private val messages: List<MessageData>) : 
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userContainer: LinearLayout = view.findViewById(R.id.msgContainer)
        val tvUserMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvUserTime: TextView = view.findViewById(R.id.tvTime)
        val aiContainer: LinearLayout = view.findViewById(R.id.llAiMessage)
        val tvAiMessage: TextView = view.findViewById(R.id.tvAiMessage)
        val tvAiTime: TextView = view.findViewById(R.id.tvAiTime)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) 0 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = timeFormat.format(Date(message.timestamp))

        if (message.isUser) {
            holder.userContainer.visibility = View.VISIBLE
            holder.aiContainer.visibility = View.GONE
            holder.tvUserMessage.text = message.text
            holder.tvUserTime.text = timeStr
            holder.userContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.END }
        } else {
            holder.userContainer.visibility = View.GONE
            holder.aiContainer.visibility = View.VISIBLE
            holder.tvAiMessage.text = message.text
            holder.tvAiTime.text = timeStr
            holder.aiContainer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.START }
        }
    }

    override fun getItemCount(): Int = messages.size
}
