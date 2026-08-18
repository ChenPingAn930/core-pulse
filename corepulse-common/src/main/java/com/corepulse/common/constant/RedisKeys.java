package com.corepulse.common.constant;

/**
 * Redis key 设计
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 任务实时指标: task:metric:{taskId} -> JSON {progress,gpuTemp,gpuFps,elapsedSec} */
    public static String taskMetric(Long taskId) {
        return "task:metric:" + taskId;
    }

    /** 会话级只读命令免确认授权: chat:readonly-auth:{sessionId} -> "1"（用户授权后只读类命令不再逐条询问） */
    public static String readonlyAuth(Long sessionId) {
        return "chat:readonly-auth:" + sessionId;
    }
}
