package com.corepulse.chat.config;

import com.corepulse.system.tool.SystemTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI ChatClient 配置
 * <p>
 * 注册可供 LLM 函数调用（Function Calling）的工具。
 * 所有实现 SystemTool 接口的工具都会被自动注册。
 * <p>
 * 系统提示词由 ChatServiceImpl 注入（PromptEnum.SYSTEM_DEFAULT），
 * 这里不设置 defaultSystem，避免重复提示词互相干扰。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, List<SystemTool> systemTools) {
        return builder
                // 注册所有系统工具，LLM 可通过函数调用打开/操作本机工具
                .defaultTools(systemTools.toArray())
                .build();
    }
}
