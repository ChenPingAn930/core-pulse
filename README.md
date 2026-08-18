# CorePulse · AI 电脑智能维修助手

LLM 决策层(大脑)+ 工具执行层(工具臂)架构的智能维修系统后端。

基于真实硬件采集与真实工具执行，AI 能查配置、测性能、诊断故障、修复问题。

## 模块结构(微服务演进友好)

| 模块 | 职责 | 将来可拆为 |
|---|---|---|
| corepulse-common | 统一响应/异常/常量 | 公共 jar |
| corepulse-domain | entity / vo / dto(与 database_schema.sql 对齐) | 共享 jar |
| corepulse-llm | LlmClient 接口 + DeepSeek 实现 + 函数注册表 | LLM 网关服务 |
| corepulse-chat | 会话/消息/AI 对话编排(function calling)/RAG 知识库 | chat-service |
| corepulse-task | 任务状态机/MQ(死信延迟队列)/执行器 | task-service |
| corepulse-system | 系统工具执行层（信息采集/压测/诊断/修复） | system-service |
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

## 已注册工具能力

所有工具均通过 Function Calling 注册，LLM 会根据用户需求自动选择合适的工具调用。工具按真实可执行程序与 OSHI 采集实现，非模拟器。

### 数据采集工具（真实数据）
| 工具 | 说明 |
|---|---|
| `getSystemInfo` | 硬件配置与实时状态（OS/CPU/内存/显卡/主板/磁盘/负载/温度）— OSHI 真实采集 |
| `getDiskSpace` | 各磁盘分区总容量/剩余空间/使用率 — OSHI 真实采集 |
| `scanVcRedist` | 检测已安装的 VC++ 运行库及关键 DLL 缺失 |

### 压力测试工具（真实执行，长任务可查状态/手动停止）
| 工具 | 说明 |
|---|---|
| `startFurMark` | 显卡烤机（真实 FurMark.exe），默认 30 分钟，到时自动关闭 |
| `startCpuStress` / `getCpuStressStatus` / `stopCpuStress` | CPU 满载压测 + 状态查询 + 停止 |
| `startMemStress` / `getMemStressStatus` / `stopMemStress` | 内存压力测试（高危，需确认）+ 状态 + 停止 |
| `runMemTest` | 内存稳定性测试（MemTest64） |

### 硬件检测工具（打开真实工具窗口）
| 工具 | 说明 |
|---|---|
| `openCpuInfo` | CPU-Z：CPU 型号/主频/缓存/主板 |
| `openGpuInfo` | GPU-Z：显卡型号/显存/驱动/温度 |
| `openCpuTemp` | Core Temp：各核心实时温度与负载 |
| `openAida64` | AIDA64：综合检测 + 传感器 + 稳定性测试 |
| `benchDisk` | CrystalDiskMark：磁盘顺序/随机读写速度 |
| `scanDiskDevices` / `checkDiskHealth` | 列出磁盘设备 + 读取完整 S.M.A.R.T. 健康数据（PASSED/FAILED/温度/通电时间/重分配扇区等） |

### 修复工具（需用户确认）
| 工具 | 说明 |
|---|---|
| `repairVcRedist` | 用本地离线安装包静默修复 VC++ 运行库（解决缺少 msvcp140.dll 等） |

### 终端命令工具（通用）
`runShellCommand` 让 LLM 可直接执行 cmd 命令，用于查询系统信息、检测进程、查看目录、清理磁盘等。

**安全策略**：
- 危险命令（删除/格式化/关机/改注册表等）需二次确认，命令原文展示给用户
- 完全禁止命令（如格式化系统盘、静默删系统目录等）即使确认也不执行
- LLM 须先解释命令执行后果，再等用户确认

## 快速体验

```
POST /api/chat/send
{"content": "帮我看看电脑配置"}

POST /api/chat/send
{"content": "帮我查一下C盘空间"}

POST /api/chat/send
{"content": "C盘快满了，帮我清理一下临时文件"}

POST /api/chat/send
{"content": "我的程序报错缺少msvcp140.dll，帮我修复"}

POST /api/tools/furmark/start
{"durationMin": 1, "sessionId": 1}

GET /api/tools/furmark/status?taskId=1
```

烤机进度: `ws://localhost:8080/ws/chat?sessionId=1` 接收 `tool_progress` 事件。

## 架构亮点

- **LLM + 工具臂**：DeepSeek 决策，真实本机工具执行，实现"能查、能测、能修"
- **任务状态机 + MQ 死信延迟队列**：长任务（如烤机自动关闭、修复安装）可靠调度
- **WebSocket 实时进度**：烤机等长任务进度实时推送前端
- **RAG 个人知识库**：从本地向量库检索维修知识片段，注入对话上下文
- **双重安全确认**：危险终端命令与修复操作均需用户明确确认
