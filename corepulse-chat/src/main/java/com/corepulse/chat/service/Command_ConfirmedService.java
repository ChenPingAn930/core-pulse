package com.corepulse.chat.service;

import com.corepulse.chat.model.vo.CommandStatusVO;

/**
 * 危险命令确认
 */
public interface Command_ConfirmedService {

    /**
     * 确认一条待执行命令。
     * <p>
     * 用户点确认后由后端抢占执行权并异步执行，不再经由模型二次调用工具：
     * 用户确认过的动作必须确定性执行，不能依赖 LLM 是否愿意再调一次工具。
     *
     * @param messageid 待确认命令的消息 ID（t_chat_message.id）
     * @return 给前端展示的结果文本（执行结果通过 WebSocket / 状态查询接口回传）
     */
    String Command_Confirmed(String messageid);

    /**
     * 查询命令记录的当前状态
     * <p>
     * 供前端在 WebSocket 断线、超时或页面重新聚焦时回查真实状态，作为事件通道的兜底。
     *
     * @param messageid 命令记录的消息 ID
     * @return 状态信息；记录不存在时返回 null
     */
    CommandStatusVO queryStatus(String messageid);
}
