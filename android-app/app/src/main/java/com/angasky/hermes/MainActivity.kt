package com.angasky.hermes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesAI"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val SERVER_HOST = "43.242.33.173"
        private const val SERVER_PORT = 8000
        private const val WS_URL = "ws://${SERVER_HOST}:8000/ws/chat"
        private const val API_URL = "http://${SERVER_HOST}:8000/api/chat"
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech
    private var isListening = false
    private var ws: WebSocket? = null
    
    private val messageList = mutableListOf<MessageData>()
    private lateinit var messageAdapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSpeech()
        initTTS()
        checkPermissions()
    }

    private fun initViews() {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvThinking = findViewById<TextView>(R.id.tvThinking)
        val btnMic = findViewById<FloatingActionButton>(R.id.btnMic)
        val btnSend = findViewById<FloatingActionButton>(R.id.btnSend)
        val etInput = findViewById<EditText>(R.id.etInput)
        val llThinking = findViewById<LinearLayout>(R.id.llThinking)
        val pbLoading = findViewById<ProgressBar>(R.id.pbLoading)
        val rvMessages = findViewById<RecyclerView>(R.id.rvMessages)

        // 设置RecyclerView
        rvMessages.layoutManager = LinearLayoutManager(this)
        messageAdapter = MessageAdapter(messageList)
        rvMessages.adapter = messageAdapter

        // 添加欢迎消息
        messageList.add(MessageData("欢迎使用 Hermes AI！\n点击麦克风或输入文字开始对话...", false, System.currentTimeMillis()))
        messageAdapter.notifyDataSetChanged()

        // 按钮事件
        btnMic.setOnClickListener { toggleListening(tvStatus, btnMic) }
        btnSend.setOnClickListener { 
            val message = etInput.text.toString().trim()
            if (message.isNotEmpty()) {
                sendMessage(message, tvStatus, llThinking, tvThinking, pbLoading)
                etInput.text.clear()
            }
        }
    }

    private fun initSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "此设备不支持语音识别", Toast.LENGTH_LONG).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "准备聆听")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "开始说话")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "说话结束")
            }

            override fun onError(error: Int) {
                Log.e(TAG, "语音识别错误: $error")
                Toast.makeText(this@MainActivity, "识别错误，请重试", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    // 自动发送识别结果
                    val tvStatus = findViewById<TextView>(R.id.tvStatus)
                    val llThinking = findViewById<LinearLayout>(R.id.llThinking)
                    val tvThinking = findViewById<TextView>(R.id.tvThinking)
                    val pbLoading = findViewById<ProgressBar>(R.id.pbLoading)
                    sendMessage(text, tvStatus, llThinking, tvThinking, pbLoading)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "部分识别: ${matches[0]}")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun initTTS() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.language = Locale.US
                }
            } else {
                Log.e(TAG, "TTS初始化失败")
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "麦克风权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要麦克风权限才能使用语音功能", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleListening(tvStatus: TextView, btnMic: FloatingActionButton) {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            tvStatus.text = "⚪ 未连接"
            btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
            }
            speechRecognizer.startListening(intent)
            isListening = true
            tvStatus.text = "🎤 正在聆听..."
            btnMic.setImageResource(android.R.drawable.ic_delete)
        }
    }

    private fun sendMessage(message: String, tvStatus: TextView, llThinking: LinearLayout, 
                          tvThinking: TextView, pbLoading: ProgressBar) {
        // 添加用户消息
        messageList.add(MessageData(message, true, System.currentTimeMillis()))
        messageAdapter.notifyItemInserted(messageList.size - 1)
        rvMessages.scrollToPosition(messageList.size - 1)

        // 显示加载状态
        tvStatus.text = "🟡 发送中..."
        llThinking.visibility = View.VISIBLE
        pbLoading.visibility = View.VISIBLE
        tvThinking.text = "💭 正在思考..."

        // 连接到WebSocket
        connectWebSocket(message, tvStatus, llThinking, tvThinking, pbLoading)
    }

    private fun connectWebSocket(message: String, tvStatus: TextView, llThinking: LinearLayout,
                                 tvThinking: TextView, pbLoading: ProgressBar) {
        val request = Request.Builder()
            .url(WS_URL)
            .header("Origin", "http://${SERVER_HOST}")
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                this@MainActivity.ws = webSocket
                tvStatus.text = "🟢 已连接"
                
                val json = JSONObject().apply {
                    put("type", "message")
                    put("data", message)
                }
                webSocket.send(json.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.getString("type")
                    val data = json.getJSONObject("data")

                    when (type) {
                        "thinking_step" -> {
                            val step = data.getString("step")
                            val content = data.getString("content")
                            tvThinking.text += "\n$step: $content"
                        }
                        "partial_response" -> {
                            val text = data.getString("text")
                            tvThinking.text = "💭 正在回答: $text"
                        }
                        "complete" -> {
                            val response = data.getString("response")
                            messageList.add(MessageData(response, false, System.currentTimeMillis()))
                            messageAdapter.notifyItemInserted(messageList.size - 1)
                            rvMessages.scrollToPosition(messageList.size - 1)
                            
                            tvThinking.text = "✅ 回答完成"
                            pbLoading.visibility = View.GONE
                            llThinking.visibility = View.GONE
                            
                            // 播放语音回复
                            textToSpeech.speak(response, TextToSpeech.QUEUE_FLUSH, null)
                            
                            webSocket.close(1000, "Done")
                        }
                        "error" -> {
                            val errorMsg = data.getString("error")
                            tvStatus.text = "❌ 错误"
                            pbLoading.visibility = View.GONE
                            llThinking.visibility = View.GONE
                            tvThinking.text = "❌ $errorMsg"
                            webSocket.close(1000, "Error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析WebSocket消息失败", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                tvStatus.text = "⚪ 未连接"
                llThinking.visibility = View.GONE
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                Log.e(TAG, "WebSocket连接失败", t)
                tvStatus.text = "❌ 连接失败"
                llThinking.visibility = View.GONE
                pbLoading.visibility = View.GONE
                tvThinking.text = "❌ 无法连接到服务器"
                Toast.makeText(this@MainActivity, "连接失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, "Activity destroyed")
        speechRecognizer.destroy()
        textToSpeech.shutdown()
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
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.tvMessage.text = message.text
        
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        holder.tvTime.text = timeFormat.format(java.util.Date(message.timestamp))

        // 设置样式
        if (message.isUser) {
            holder.container.background = 
                holder.container.context.getDrawable(R.drawable.bubble_user)
            holder.container.layoutParams = 
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.END }
        } else {
            holder.container.background = 
                holder.container.context.getDrawable(R.drawable.bubble_ai)
            holder.container.layoutParams = 
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.START }
        }
    }

    override fun getItemCount() = messages.size
}
