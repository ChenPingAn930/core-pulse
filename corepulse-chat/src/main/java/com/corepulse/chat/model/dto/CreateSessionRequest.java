package com.corepulse.chat.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新建会话请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

    /** 会话标题，可选，默认"新会话" */
    private String title;
}
