package com.corepulse.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.corepulse.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息 Mapper
 * <p>
 * 从 corepulse-chat 迁到 corepulse-domain,
 * 让 corepulse-system 等底层模块也能直接查询 t_chat_message
 * (典型场景: ShellCommandTool 根据 message.confirmed 字段判断命令是否已确认)。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
