package com.corepulse.chat.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionVO {

    /** 会话 ID */
    private Long id;

    /** 会话标题 */
    private String title;

    /** 会话摘要 */
    private String summary;

    /** 最后更新时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
