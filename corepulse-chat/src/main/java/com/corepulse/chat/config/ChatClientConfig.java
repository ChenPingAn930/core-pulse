package com.corepulse.chat.config;

import com.corepulse.chat.Enum.PromptEnum;
import com.corepulse.system.tool.FurmarkTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 配置
 * <p>
 * 注册可供 LLM 函数调用（Function Calling）的工具。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, FurmarkTool furmarkTool) {
        return builder
                .defaultSystem(PromptEnum.CHAT_CLIENT_DEFAULT.getContent())
                // 注册烤机工具，LLM 可通过函数调用启动显卡压力测试
                .defaultTools(furmarkTool)
                .build();
    }
}
