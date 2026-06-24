"""
Hermes Android Voice Assistant - Backend Service

轻量级AI代理服务，支持:
1. REST API - 发送消息获取回复
2. WebSocket - 实时推送思考过程和步骤
3. 流式输出 - 让用户看到"我在想什么"

API端点:
  POST /api/chat       - 发送消息,获取回复
  WS   /ws/chat        - WebSocket实时通信
  GET  /api/health     - 健康检查
  GET  /api/sessions   - 列出会话
"""

import asyncio
import json
import uuid
import time
from datetime import datetime
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


# ==================== 数据模型 ====================

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=4000)
    session_id: Optional[str] = None
    context: Optional[str] = None


class ChatResponse(BaseModel):
    session_id: str
    message: str
    thinking_steps: list[dict] = []
    timestamp: str


class ThinkingStep(BaseModel):
    step_id: str
    phase: str          # "understand", "plan", "execute", "verify"
    title: str
    content: str
    status: str = "running"
    timestamp: str
    duration_ms: Optional[int] = None


class WebSocketMessage(BaseModel):
    type: str           # "thinking_step", "partial_response", "complete", "error"
    data: dict


# ==================== 会话管理 ====================

class SessionManager:
    def __init__(self):
        self.active_connections: dict[str, list[WebSocket]] = {}
        self.sessions: dict[str, list[dict]] = {}

    async def connect(self, session_id: str, websocket: WebSocket):
        await websocket.accept()
        if session_id not in self.active_connections:
            self.active_connections[session_id] = []
        self.active_connections[session_id].append(websocket)
        if session_id not in self.sessions:
            self.sessions[session_id] = []

    def disconnect(self, session_id: str, websocket: WebSocket):
        if session_id in self.active_connections:
            if websocket in self.active_connections[session_id]:
                self.active_connections[session_id].remove(websocket)
            if not self.active_connections[session_id]:
                del self.active_connections[session_id]


# ==================== AI Agent 核心 ====================

class HermesAgent:
    """
    AI Agent核心 - 模拟思考过程
    
    思考流程:
    1. understand - 理解用户意图
    2. plan - 制定执行计划
    3. execute - 执行操作
    4. verify - 验证结果
    """

    def __init__(self):
        self.session_history: dict[str, list[dict]] = {}

    async def process_message(
        self,
        message: str,
        session_id: str,
        websocket: Optional[WebSocket] = None
    ) -> tuple[str, list[ThinkingStep]]:

        if session_id not in self.session_history:
            self.session_history[session_id] = []
        self.session_history[session_id].append({
            "role": "user",
            "content": message,
            "time": datetime.now().isoformat()
        })

        steps = []

        # Step 1: 理解意图
        step1 = ThinkingStep(
            step_id=str(uuid.uuid4())[:8],
            phase="understand",
            title="🔍 理解你的问题",
            content=self._analyze_intent(message),
            status="running",
            timestamp=datetime.now().isoformat()
        )
        steps.append(step1)
        await self._emit_step(step1, websocket)
        await self._simulate_delay(0.8)

        # Step 2: 制定计划
        step2 = ThinkingStep(
            step_id=str(uuid.uuid4())[:8],
            phase="plan",
            title="📋 制定解决方案",
            content=self._create_plan(message),
            status="running",
            timestamp=datetime.now().isoformat()
        )
        steps.append(step2)
        await self._emit_step(step2, websocket)
        await self._simulate_delay(0.6)

        # Step 3: 执行操作
        step3 = ThinkingStep(
            step_id=str(uuid.uuid4())[:8],
            phase="execute",
            title="⚡ 执行操作",
            content=self._execute_action(message),
            status="running",
            timestamp=datetime.now().isoformat()
        )
        steps.append(step3)
        await self._emit_step(step3, websocket)
        await self._simulate_delay(0.5)

        # Step 4: 验证结果
        step4 = ThinkingStep(
            step_id=str(uuid.uuid4())[:8],
            phase="verify",
            title="✅ 验证结果",
            content="已完成所有步骤,准备回复",
            status="running",
            timestamp=datetime.now().isoformat()
        )
        steps.append(step4)
        await self._emit_step(step4, websocket)
        await self._simulate_delay(0.3)

        # 标记完成
        for step in steps:
            step.status = "success"
            await self._emit_step(step, websocket)

        response = self._generate_response(message)

        self.session_history[session_id].append({
            "role": "assistant",
            "content": response,
            "time": datetime.now().isoformat(),
            "steps": [s.model_dump() for s in steps]
        })

        return response, steps

    def _analyze_intent(self, message: str) -> str:
        msg_lower = message.lower()
        intents = {
            ("你好", "嗨", "hello", "hi"): "问候语 - 准备友好回应",
            ("服务器", "ssh", "部署", "安装"): "运维请求 - 提供技术指导",
            ("代码", "编程", "写个", "开发"): "编程请求 - 生成代码示例",
            ("分析", "检查", "查看", "看看"): "分析请求 - 执行诊断",
        }
        for keywords, desc in intents.items():
            if any(kw in msg_lower for kw in keywords):
                return f"检测到意图: {desc}\n用户输入: \"{message}\""
        return f"通用对话请求\n用户输入: \"{message}\"\n准备提供友好帮助"

    def _create_plan(self, message: str) -> str:
        return (
            "1. 解析用户意图: ✅ 已完成\n"
            "2. 检索相关知识库\n"
            "3. 构建回复内容\n"
            "4. 格式化输出\n"
            "预计耗时: 约2秒"
        )

    def _execute_action(self, message: str) -> str:
        return "正在生成回复内容..."

    def _generate_response(self, message: str) -> str:
        msg_lower = message.lower()
        responses = {
            ("你好", "嗨", "hello", "hi"): (
                "你好!我是Hermes,你的AI助手。\n"
                "有什么可以帮助你的吗?我可以帮你管理服务器、写代码、分析问题等。"
            ),
            ("服务器", "ssh", "部署", "安装"): (
                "关于服务器管理,我可以帮助你:\n"
                "1. 远程SSH连接和管理\n"
                "2. 软件安装和部署\n"
                "3. 系统性能优化\n"
                "4. Docker容器管理\n\n请告诉我具体需要什么帮助!"
            ),
            ("代码", "编程", "写个", "开发"): (
                "我可以帮你写代码!支持的语言包括:\n"
                "• Python / Java / Kotlin\n"
                "• JavaScript / TypeScript\n"
                "• Go / Rust\n• Shell脚本\n\n请描述你需要什么功能的代码。"
            ),
            ("分析", "检查", "查看", "看看"): (
                "我可以帮你分析和检查各种内容:\n"
                "• 服务器日志分析\n"
                "• 代码审查\n"
                "• 系统配置检查\n"
                "• 性能诊断\n\n请提供具体内容或描述。"
            ),
        }
        for keywords, response in responses.items():
            if any(kw in msg_lower for kw in keywords):
                return response
        return (
            f"我理解你的问题:\"{message}\"\n\n"
            "作为一个AI助手,我正在不断学习和成长。目前我主要擅长:\n"
            "• 服务器管理和运维\n"
            "• 代码编写和调试\n"
            "• 技术问题解答\n"
            "• 数据分析\n\n请告诉我更多细节,我会尽力帮助你!"
        )

    async def _emit_step(self, step: ThinkingStep, websocket: Optional[WebSocket]):
        if websocket:
            msg = WebSocketMessage(type="thinking_step", data=step.model_dump())
            try:
                await websocket.send_text(msg.model_dump_json())
            except Exception:
                pass

    async def _simulate_delay(self, seconds: float):
        await asyncio.sleep(seconds)


# ==================== FastAPI应用 ====================

app = FastAPI(
    title="Hermes AI Assistant API",
    description="Android Voice Assistant后端服务",
    version="0.1.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

session_manager = SessionManager()
agent = HermesAgent()


@app.get("/api/health")
async def health_check():
    return {
        "status": "ok",
        "service": "hermes-backend",
        "version": "0.1.0",
        "sessions_active": len(session_manager.active_connections)
    }


@app.post("/api/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    session_id = request.session_id or str(uuid.uuid4())[:12]
    response, steps = await agent.process_message(request.message, session_id)
    return ChatResponse(
        session_id=session_id,
        message=response,
        thinking_steps=[s.model_dump() for s in steps],
        timestamp=datetime.now().isoformat()
    )


@app.websocket("/ws/chat")
async def websocket_chat(websocket: WebSocket):
    session_id = str(uuid.uuid4())[:12]
    await session_manager.connect(session_id, websocket)

    await websocket.send_text(json.dumps({
        "type": "connected",
        "data": {"session_id": session_id}
    }))

    try:
        while True:
            data = await websocket.receive_text()
            msg = json.loads(data)
            message = msg.get("data", "") or msg.get("message", "")
            if not message:
                continue

            await websocket.send_text(json.dumps({
                "type": "started",
                "data": {"message": message}
            }))

            response, steps = await agent.process_message(
                message, session_id, websocket
            )

            await websocket.send_text(json.dumps({
                "type": "complete",
                "data": {
                    "response": response,
                    "steps": [s.model_dump() for s in steps],
                    "session_id": session_id
                }
            }))

    except WebSocketDisconnect:
        session_manager.disconnect(session_id, websocket)
    except Exception as e:
        try:
            await websocket.send_text(json.dumps({
                "type": "error",
                "data": {"error": str(e)}
            }))
        except Exception:
            pass


@app.get("/api/sessions/{session_id}/history")
async def get_session_history(session_id: str):
    if session_id not in agent.session_history:
        raise HTTPException(status_code=404, detail="Session not found")
    return {
        "session_id": session_id,
        "messages": agent.session_history[session_id]
    }


@app.get("/api/sessions")
async def list_sessions():
    return {
        "sessions": list(agent.session_history.keys()),
        "total": len(agent.session_history)
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
