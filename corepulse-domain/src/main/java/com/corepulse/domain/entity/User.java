package com.corepulse.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 * <p>
 * 对应表：t_user，保存用户账号信息。
 */
@Data
@TableName("t_user")
public class User {

    /** 用户主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码哈希值（不保存明文密码） */
    private String passwordHash;

    /** 邮箱 */
    private String email;

    /** 用户绑定的 LLM API Key */
    private String apiKey;

    /** 头像 URL */
    private String avatar;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
