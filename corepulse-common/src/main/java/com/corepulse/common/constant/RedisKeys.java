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
}
