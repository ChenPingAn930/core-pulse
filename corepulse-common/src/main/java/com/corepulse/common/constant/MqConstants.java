package com.corepulse.common.constant;

/**
 * RabbitMQ 常量
 * 延迟终止采用"死信队列"方案: 消息发到 delay 队列, TTL 到期后死信转发到 execute 队列
 */
public final class MqConstants {

    private MqConstants() {
    }

    public static final String EXCHANGE = "task.exchange";
    public static final String QUEUE_EXECUTE = "task.execute";
    public static final String QUEUE_DELAY = "task.delay";
    public static final String ROUTING_EXECUTE = "task.execute";
    public static final String ROUTING_DELAY = "task.delay";

    /** 消息类型 */
    public static final String MSG_TYPE_EXECUTE = "EXECUTE";   // 立即执行
    public static final String MSG_TYPE_TIMEOUT = "TIMEOUT";   // 延时到期终止
    public static final String MSG_TYPE_STOP = "STOP";         // 手动停止
    public static final String MSG_TYPE_CONFIRM = "CONFIRM";   // 确认后继续(M3)
}
