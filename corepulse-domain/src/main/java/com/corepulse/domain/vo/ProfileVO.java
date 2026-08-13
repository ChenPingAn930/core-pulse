package com.corepulse.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图对象
 * <p>
 * 用于向前端返回当前用户的基本信息。
 */
@Data
public class ProfileVO {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 邮箱 */
    private String email;

    /** API Key（已脱敏显示，如 sk-abcd****wxyz） */
    private String apiKeyMasked;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
