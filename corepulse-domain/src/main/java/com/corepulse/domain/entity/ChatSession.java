package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
// 会话实体
@Data
@TableName("t_chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    // 会话 ID
    private Long id;
    // 用户 ID
    private Long userId;
    // 会话标题
    private String title;
    // 会话摘要
    private String summary;
    // 最新摘要消息ID
    private Long lastSummarizedMessageId;
    // 会话状态
    private Integer status;
    // 创建时间
    private LocalDateTime createdAt;
    // 更新时间
    private LocalDateTime updatedAt;
}
