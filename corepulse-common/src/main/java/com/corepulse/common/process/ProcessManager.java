package com.corepulse.common.process;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程管理器
 * <p>
 * 管理通过工具启动的外部进程（taskId -> Process）。
 * 放在公共模块，供各业务模块（system 工具层 / task 执行器）共享，
 * 用于在任务超时或手动停止时，依据 taskId 终止对应的外部进程。
 */
@Slf4j
public final class ProcessManager {

    private ProcessManager() {
    }

    /** taskId -> 外部进程 */
    private static final Map<Long, Process> PROCESS_MAP = new ConcurrentHashMap<>();

    /**
     * 注册一个已启动的进程
     *
     * @param taskId  任务 ID
     * @param process 外部进程
     */
    public static void register(Long taskId, Process process) {
        if (taskId != null && process != null) {
            PROCESS_MAP.put(taskId, process);
            log.info("进程已注册: taskId={}, pid={}", taskId, process.pid());
        }
    }

    /**
     * 依据 taskId 终止进程
     *
     * @param taskId 任务 ID
     */
    public static void kill(Long taskId) {
        Process process = PROCESS_MAP.remove(taskId);
        if (process == null) {
            log.info("未找到进程可终止: taskId={}", taskId);
            return;
        }
        try {
            // 先尝试优雅销毁，再强杀
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            log.info("进程已终止: taskId={}, pid={}", taskId, process.pid());
        } catch (Exception e) {
            log.warn("终止进程失败: taskId={}", taskId, e);
        }
    }

    /**
     * 判断指定 taskId 的进程是否仍存活
     *
     * @param taskId 任务 ID
     * @return true 存活
     */
    public static boolean isAlive(Long taskId) {
        Process process = PROCESS_MAP.get(taskId);
        return process != null && process.isAlive();
    }

    /**
     * 判断该任务类型是否支持进程存活检测
     * <p>
     * 仅"后台持续进程"类任务（如 furmark 烤机）支持，因为此类任务启动后会持续运行，
     * 可通过进程存活状态判断任务是否真的在进行。
     * agent_task 等同步执行的任务不支持（进程生命周期不同）。
     *
     * @param taskType 任务类型
     * @return true 支持进程检测
     */
    public static boolean isSupported(String taskType) {
        return "furmark".equals(taskType) || "cpustress".equals(taskType) || "memstress".equals(taskType);
    }

    /**
     * 判断指定任务是否真的还在运行
     * <p>
     * 通过系统命令 tasklist 检测进程是否存在，比 Java 的 Process.isAlive() 更可靠
     * （用户手动结束进程后，Java 持有的 Process 对象可能仍误判为存活）。
     *
     * @param taskId 任务 ID
     * @return true 进程仍在运行
     */
    public static boolean isTaskRunning(Long taskId) {
        Process process = PROCESS_MAP.get(taskId);
        if (process == null) {
            return false;
        }
        // 先尝试 Java 判断，若明确已死则直接返回
        if (!process.isAlive()) {
            return false;
        }
        // 用系统命令复核（避免 Java 对象状态滞后）
        return isProcessRunningByTasklist(process);
    }

    /**
     * 判断指定的 Java 进程句柄对应的真实进程是否存活
     * <p>
     * 通过 tasklist 按 PID 检测。
     */
    private static boolean isProcessRunningByTasklist(Process process) {
        try {
            long pid = process.pid();
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/fi", "pid eq " + pid);
            pb.redirectErrorStream(true);
            Process cmd = pb.start();
            String output = new String(cmd.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            cmd.waitFor();
            // 输出中包含 PID 行则说明进程存在
            return output.contains(String.valueOf(pid)) && !output.contains("没有运行的任务");
        } catch (Exception e) {
            log.warn("tasklist 检测进程失败, 回退到 Java 判断", e);
            return process.isAlive();
        }
    }
}
