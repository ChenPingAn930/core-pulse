package com.corepulse.domain.enums;

/**
 * 命令确认状态（对应 t_chat_message.status，仅命令类工具记录使用）
 * <p>
 * 状态流转：PENDING（待用户确认）-> CONFIRMED（用户已确认，等待执行）-> EXECUTED（已执行）。
 * <p>
 * 为什么需要独立状态而不是只用 confirmed 布尔值：
 * - confirmed 只能表达"用户是否点过确认"，表达不了"这条命令有没有被执行过"；
 * - 确认和执行是两次独立的请求（中间还夹着一轮 LLM 调用），必须能区分
 *   "已确认但还没执行"和"已经执行完"，否则并发或重试会导致同一条命令被执行多次。
 */
public enum CommandStatus {

    /** 待用户确认（命令已落库，尚未执行） */
    PENDING("PENDING"),

    /** 用户已确认，等待工具执行 */
    CONFIRMED("CONFIRMED"),

    /** 已执行（防重复执行的终态） */
    EXECUTED("EXECUTED");

    private final String value;

    CommandStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
