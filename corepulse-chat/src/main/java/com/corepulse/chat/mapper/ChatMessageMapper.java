package com.corepulse.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.corepulse.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
