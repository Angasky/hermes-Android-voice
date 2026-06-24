# Hermes Android Voice Assistant

🤖 一个基于语音交互的Android AI助手应用，内置真实AI Agent能力。

## 项目架构

```
hermes-Android-voice/
├── backend/              # AI代理服务（FastAPI + WebSocket）
│   ├── main.py          # 主服务入口
│   └── requirements.txt
├── android-app/          # Android原生应用（Kotlin）
├── docs/
│   └── ARCHITECTURE.md   # 架构文档
└── README.md
```

## 功能特性

- 🎙️ **语音输入** — 实时语音转文字
- 💭 **思考过程可视化** — 实时显示AI的思考链和决策过程
- ⚡ **操作步骤展示** — 看到AI执行的每一步操作
- 🔊 **语音回复** — TTS合成语音反馈
- 🌐 **WebSocket实时通信** — 低延迟双向通信

## 快速开始

### 1. 启动后端服务

```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. 连接Android App

在App中配置后端地址：
```
ws://YOUR_SERVER_IP:8000/ws/chat
```

## API文档

启动服务后访问:
- Swagger UI: http://YOUR_SERVER_IP:8000/docs
- ReDoc: http://YOUR_SERVER_IP:8000/redoc

## 技术栈

| 组件 | 技术 |
|------|------|
| 后端 | FastAPI, WebSockets, Python 3.12 |
| 前端 | Kotlin, Jetpack Compose |
| 通信 | WebSocket (实时流式) |
| AI | 对接外部AI API |

## License

MIT
