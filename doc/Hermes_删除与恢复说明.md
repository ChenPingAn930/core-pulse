# Hermes Agent 禁用与恢复说明

> 本文档说明如何**禁用（删除）** Hermes Agent 委派能力，以及日后如何**恢复**。
> 当前阶段已禁用 Hermes，项目依靠**纯 LLM**（数据采集工具 + 终端命令工具 + GUI 工具）工作。

---

## 一、背景

Hermes 是外部的 Agent（Python 程序），本项目曾通过 `delegateToAgent` 工具将复杂任务（清理C盘、卸载软件等）委派给它执行。

**决策**：当前希望先测试"纯 LLM 能力是否满足需求"，因此**暂时禁用了 Hermes 委派**。代码仍保留，未删除，可随时恢复。

---

## 二、禁用 Hermes 涉及的文件

禁用是通过注释 Spring 的 `@Component` 注解实现的，共 3 个组件：

| 文件路径 | 组件 | 作用 |
|---------|------|------|
| `corepulse-system/.../tool/AgentTool.java` | `AgentTool` | LLM 的 `delegateToAgent` 工具，负责委派任务给 Hermes |
| `corepulse-task/.../executor/HermesTaskExecutor.java` | `HermesTaskExecutor` | Agent 任务执行器，同步调用 Hermes 并处理确认环节 |
| `corepulse-task/.../executor/HermesCommandRunner.java` | `HermesCommandRunner` | 封装 `ProcessBuilder` 调用 `hermes chat -q` 命令 |

---

## 三、如何删除（禁用）Hermes

**已经完成**，当前就是禁用状态。做法如下（供参考）：

### 1. 禁用 `AgentTool`（LLM 调不到 delegateToAgent）
文件：`corepulse-system/src/main/java/com/corepulse/system/tool/AgentTool.java`

将类上的 `@Component` 注释掉：

```java
@Deprecated
@Slf4j
@RequiredArgsConstructor
// @Component  // 已禁用 Agent 委派，恢复本行即可启用
public class AgentTool implements SystemTool {
```

### 2. 禁用 `HermesTaskExecutor`
文件：`corepulse-task/src/main/java/com/corepulse/task/executor/HermesTaskExecutor.java`

```java
@Deprecated
@Slf4j
@RequiredArgsConstructor
// @Component  // 已禁用 Hermes 执行器，恢复本行即可启用
public class HermesTaskExecutor implements ToolTaskExecutor {
```

### 3. 禁用 `HermesCommandRunner`
文件：`corepulse-task/src/main/java/com/corepulse/task/executor/HermesCommandRunner.java`

```java
@Deprecated
@Slf4j
// @Component  // 已禁用 Hermes，恢复本行即可启用
public class HermesCommandRunner {
```

### 4. 更新系统提示词（可选但建议）
文件：`corepulse-chat/src/main/java/com/corepulse/chat/Enum/PromptEnum.java`

移除/注释"Agent 委派（delegateToAgent）"相关的工具使用策略段落，避免 LLM 试图调用已不存在的工具。

---

## 四、如何恢复 Hermes

若要恢复 Hermes 委派能力，按以下步骤反向操作：

### 1. 恢复 3 个组件的 `@Component`
把上述 3 个文件中注释掉的 `@Component` 取消注释即可。

### 2. 恢复系统提示词
在 `PromptEnum.java` 的"工具使用策略"中，重新加入 Agent 委派段落（可参考 git 历史或本仓库 doc/架构设计.md 中的说明）。

### 3. 确认 Hermes 可用
- 确认 Hermes 已安装（命令 `hermes` 在 PATH 中）
- 确认 Hermes 已配置 API Key（`D:/hermes/.env` 中的 `DEEPSEEK_API_KEY`，或 `config.yaml` 的 `model.api_key`）
- 可用 `hermes chat -q "测试" -Q` 验证命令可执行

### 4. 重新编译
```bash
mvn clean compile
```

---

## 五、禁用后当前能力（纯 LLM）

| 能力 | 工具 | 说明 |
|------|------|------|
| 硬件信息查询 | `getSystemInfo` | OSHI 读取 CPU/内存/显卡/主板等 |
| 磁盘空间查询 | `getDiskSpace` | OSHI 读取磁盘分区 |
| 终端命令 | `runShellCommand` | cmd 执行，危险命令需用户确认 |
| 显卡烤机 | `startFurMark` | 启动 FurMark + 延时关闭 |
| 其他 GUI 工具 | `runMemTest` 等 | 打开内存/CPU/硬盘等检测软件 |

> **注意**：禁用 Hermes 后，"清理C盘"等需要多步骤、灵活判断的复杂任务，目前由 LLM 通过 `runShellCommand` + 确认环节来完成。如果测试发现纯 LLM 能力不足，可恢复 Hermes。

---

## 六、常见问题

**Q1：禁用 Hermes 后，之前的 agent_task 任务记录怎么办？**
不影响。`agent_task` 类型只是不再有对应的执行器，历史记录保留在数据库，不会被误处理。

**Q2：恢复 Hermes 需要重新配置吗？**
不需要。只要 Hermes 仍安装在原位置且 Key 有效，恢复 `@Component` 重新编译即可。

**Q3：如何彻底删除 Hermes 相关代码（不只是禁用）？**
如果确定不再需要 Hermes，可删除：
- `AgentTool.java`
- `HermesTaskExecutor.java`
- `HermesCommandRunner.java`
并清理系统提示词中的 Agent 段落。但建议保留代码（禁用而非删除），便于日后恢复。
