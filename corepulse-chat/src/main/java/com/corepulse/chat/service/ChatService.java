package com.corepulse.chat.service;

import com.corepulse.chat.model.dto.ChatSendRequest;
import com.corepulse.chat.model.vo.ChatSendVO;

/**
 * 聊天服务接口
 */
public interface ChatService {

    /**
     * 发送消息 - AI 对话
     *
     * @param request 发送消息请求
     * @return AI 回复
     */
    ChatSendVO send(ChatSendRequest request);
}
