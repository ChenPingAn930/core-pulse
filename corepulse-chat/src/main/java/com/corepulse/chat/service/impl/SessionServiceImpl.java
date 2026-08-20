package com.corepulse.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.corepulse.chat.mapper.ChatMessageMapper;
import com.corepulse.chat.mapper.ChatSessionMapper;
import com.corepulse.chat.model.vo.MessageVO;
import com.corepulse.chat.model.vo.SessionVO;
import com.corepulse.chat.service.SessionService;
import com.corepulse.domain.entity.ChatMessage;
import com.corepulse.domain.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 会话服务实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionServiceImpl implements SessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    /**
     * 创建新会话
     * <p>
     * 默认归属用户 ID 为 1（当前无登录体系，暂写死），标题为空时使用"新会话"。
     *
     * @param title 会话标题，可传 null 或空串，此时使用默认标题"新会话"
     * @return 新创建的会话视图（包含自增生成的 ID 和标题）
     */
    @Override
    public SessionVO create(String title) {
        ChatSession session = new ChatSession();
        session.setUserId(1L);
        session.setTitle(title == null || title.isBlank() ? "新会话" : title);
        session.setStatus(0);
        sessionMapper.insert(session);
        return SessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .build();
    }

    /**
     * 分页查询会话列表
     * <p>
     * 按最后更新时间倒序排列，返回指定页的数据和总数。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return Map，包含两个键：list（当前页会话列表）、total（总记录数）
     */
    @Override
    public Map<String, Object> list(int page, int size) {
        Page<ChatSession> p = sessionMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, 1L)
                        .orderByDesc(ChatSession::getUpdatedAt));

        List<SessionVO> list = p.getRecords().stream().map(s ->
                SessionVO.builder()
                        .id(s.getId())
                        .title(s.getTitle())
                        .summary(s.getSummary())
                        .updatedAt(s.getUpdatedAt())
                        .build()
        ).toList();
        log.info("查询到的会话列表:{}", list);
        return Map.of("list", list, "total", p.getTotal());
    }

    /**
     * 分页查询会话消息历史
     * <p>
     * 按消息 ID 正序排列，返回指定页的数据和总数。
     *
     * @param sessionId 会话 ID
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @return Map，包含两个键：list（当前页消息列表）、total（总记录数）
     */
    @Override
    public Map<String, Object> getMessages(Long sessionId, int page, int size) {
        Page<ChatMessage> p = messageMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));

        List<MessageVO> list = p.getRecords().stream().map(m ->
                MessageVO.builder()
                        .id(m.getId())
                        .role(m.getRole())
                        .content(m.getContent())
                        .toolName(m.getToolName())
                        .taskId(m.getTaskId())
                        .extra(m.getExtra())
                        .createdAt(m.getCreatedAt())
                        .build()
        ).toList();
        log.info("查询到的消息列表:{}", list);
        return Map.of("list", list, "total", p.getTotal());
    }

    /**
     * 获取或创建会话
     * <p>
     * 若传入的会话 ID 存在则直接返回，否则自动创建一个默认会话。
     * 用于保证对话流程始终有一个可用的会话对象。
     *
     * @param sessionId 会话 ID，可为 null（表示新建）
     * @return 已存在或新建的会话实体
     */
    @Override
    public ChatSession getOrCreate(Long sessionId) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session != null) {
                return session;
            }
        }
        ChatSession session = new ChatSession();
        session.setUserId(1L);
        session.setTitle("新会话");
        session.setStatus(0);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 更新会话信息
     * <p>
     * 按主键更新会话，通常用于修改会话标题。
     *
     * @param session 待更新的会话实体（需包含主键 ID）
     */
    @Override
    public void update(ChatSession session) {
        sessionMapper.updateById(session);
    }
}
