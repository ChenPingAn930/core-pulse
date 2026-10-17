package com.corepulse.chat.service;

/**
 * 危险命令确认
 */
public interface Command_ConfirmedService {

    /**
     * 确认一条待执行命令，并触发新一轮对话让模型真正执行它
     *
     * @param messageid 待确认命令的消息 ID（t_chat_message.id）
     * @return 给前端展示的结果文本
     */
    String Command_Confirmed(String messageid);
}
