package com.corepulse.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象
 * <p>
 * 用于向前端返回会话列表信息。
 */
@Data
public class SessionVO {

    /** 会话 ID */
    private Long id;

    /** 会话标题 */
    private String title;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
