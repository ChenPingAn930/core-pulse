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
}
