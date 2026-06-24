# Hermes Android Voice Assistant - Backend

轻量级AI代理服务，支持WebSocket实时通信。

## 快速启动

```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/health` | GET | 健康检查 |
| `/api/chat` | POST | 发送消息 |
| `/ws/chat` | WS | WebSocket实时通信 |
| `/api/sessions` | GET | 列出会话 |
| `/api/sessions/{id}/history` | GET | 会话历史 |

## WebSocket消息格式

发送:
```json
{"message": "你好"}
```

接收:
```json
{
  "type": "thinking_step",
  "data": {
    "phase": "understand",
    "title": "🔍 理解你的问题",
    "content": "...",
    "status": "running"
  }
}
```

```json
{
  "type": "complete",
  "data": {
    "response": "你好！我是Hermes...",
    "steps": [...]
  }
}
```
