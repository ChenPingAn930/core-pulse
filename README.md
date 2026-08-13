# CorePulse · AI 电脑智能维修助手

LLM 决策层(大脑)+ Agent 执行层(工具臂)架构的智能维修系统后端。

## 模块结构(微服务演进友好)

| 模块 | 职责 | 将来可拆为 |
|---|---|---|
| corepulse-common | 统一响应/异常/常量 | 公共 jar |
| corepulse-domain | entity / vo / dto(与 database_schema.sql 对齐) | 共享 jar |
| corepulse-llm | LlmClient 接口 + DeepSeek 实现 + 函数注册表 | LLM 网关服务 |
| corepulse-chat | 会话/消息/AI 对话编排(function calling) | chat-service |
| corepulse-task | 任务状态机/MQ(死信延迟队列)/执行器 | task-service |
| corepulse-system | 硬件信息/磁盘空间等简单工具 | system-service |
| corepulse-web | 启动类/WebSocket/配置聚合 | 网关 |

## 启动前依赖

1. **MySQL 8.0+**: 执行 `../设计文档/database_schema.sql` 建库建表
2. **Redis**: 默认 localhost:6379
3. **RabbitMQ**: 默认 localhost:5672(guest/guest)
   - Docker: `docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management`
   - 或本机安装 Erlang + RabbitMQ
4. **DeepSeek API Key**: 配置在 `corepulse-web/src/main/resources/application.yml` 的 `corepulse.llm.api-key`(或环境变量 `DEEPSEEK_API_KEY`)

## 启动

```
# IDEA: 打开根目录 CorePulse, 运行 com.corepulse.CorePulseApplication
# 或命令行:
mvn -pl corepulse-web -am spring-boot:run
```

启动后: http://localhost:8080
接口文档: `../设计文档/api_openapi.json`(导入 Apifox)

## M1 现状说明

- 烤机执行器为**模拟器**(随机温度/帧率, M2 接入真实 FurMark)
- 系统信息为**模拟数据**(M2 接入真实采集)
- 同步工具已注册: `get_system_info` / `get_disk_space`(对话中 LLM 会自动调用)
- 异步工具已注册: `run_furmark`(对话中触发或直接调接口)

## 快速体验

```
POST /api/chat/send
{"content": "帮我看看电脑配置"}

POST /api/chat/send
{"content": "帮我查一下C盘空间"}

POST /api/tools/furmark/start
{"durationMin": 1, "sessionId": 1}

GET /api/tools/furmark/status?taskId=1
```

烤机进度: `ws://localhost:8080/ws/chat?sessionId=1` 接收 `tool_progress` 事件。
