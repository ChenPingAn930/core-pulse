package com.corepulse.chat.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 命令执行状态查询结果
 * <p>
 * 用途：WebSocket 断线、浏览器重连或前端等待超时后，用它回查数据库里的真实状态，
 * 避免界面永远停在"执行中"。
 * <p>
 * 为什么不返回命令输出全文：输出已经作为工具记录消息落库，页面刷新能看到；
 * 这里只回状态，避免轮询反复传输大段文本。summary / costMs 目前只在实时事件里下发，
 * 不做持久化，因此查询结果里可能为 null。
 */
@Data
@Builder
public class CommandStatusVO {

    /** 状态值：PENDING / CONFIRMED / EXECUTING / EXECUTED / FAILED / EXPIRED（超时未执行） */
    private String status;

    /** 命令原文：前端可用它校正被 WS 事件覆盖前的卡片内容 */
    private String command;

    /** 结果摘要（实时事件里才有，查询接口一般为 null） */
    private String summary;

    /** 执行耗时（毫秒），实时事件里才有 */
    private Long costMs;
}
