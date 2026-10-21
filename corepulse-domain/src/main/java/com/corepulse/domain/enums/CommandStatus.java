package com.corepulse.domain.enums;

/**
 * 命令确认状态（对应 t_chat_message.status，仅命令类工具记录使用）
 * <p>
 * 状态流转：PENDING（待用户确认）-> CONFIRMED（用户已确认，等待执行）
 * -> EXECUTING（工具已抢占，正在执行）-> EXECUTED（成功）/ FAILED（失败）。
 * <p>
 * 为什么需要独立状态而不是只用 confirmed 布尔值：
 * - confirmed 只能表达"用户是否点过确认"，表达不了"这条命令有没有被执行过"；
 * - 确认和执行是两次独立的请求（中间还夹着一轮 LLM 调用），必须能区分
 *   "已确认但还没执行"和"已经执行完"，否则并发或重试会导致同一条命令被执行多次。
 * <p>
 * 为什么还要单独的 EXECUTING 和 FAILED：
 * - 执行本身耗时数十秒，前端必须能显示"正在执行"，否则只能停在"执行中"的假状态；
 * - 抢占成功但执行失败时若仍记为 EXECUTED，前端会把失败展示成成功，
 *   模型也会把失败命令当成已完成。
 */
public enum CommandStatus {

    /** 待用户确认（命令已落库，尚未执行） */
    PENDING("PENDING"),

    /** 用户已确认，等待工具执行 */
    CONFIRMED("CONFIRMED"),

    /** 工具已抢占，命令正在执行（尚无最终结果） */
    EXECUTING("EXECUTING"),

    /** 已执行且退出码为 0（防重复执行的终态） */
    EXECUTED("EXECUTED"),

    /** 执行失败：启动异常或退出码非 0（终态，不自动重试） */
    FAILED("FAILED");

    private final String value;

    CommandStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
