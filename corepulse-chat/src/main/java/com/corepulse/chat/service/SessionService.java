package com.corepulse.chat.service;

import com.corepulse.chat.model.vo.SessionVO;
import com.corepulse.domain.entity.ChatSession;

import java.util.Map;

/**
 * 会话服务接口
 */
public interface SessionService {

    /**
     * 创建会话
     */
    SessionVO create(String title);

    /**
     * 会话列表（分页）
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return {list, total}
     */
    Map<String, Object> list(int page, int size);

    /**
     * 会话消息历史（分页）
     *
     * @param sessionId 会话 ID
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @return {list, total}
     */
    Map<String, Object> getMessages(Long sessionId, int page, int size);

    /**
     * 获取或创建会话
     */
    ChatSession getOrCreate(Long sessionId);

    /**
     * 更新会话
     */
    void update(ChatSession session);
}
