package com.corepulse.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.corepulse.domain.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
