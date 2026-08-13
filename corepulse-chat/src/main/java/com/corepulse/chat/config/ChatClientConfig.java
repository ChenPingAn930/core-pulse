package com.corepulse.chat.config;

import com.corepulse.chat.Enum.PromptEnum;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 配置
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(PromptEnum.CHAT_CLIENT_DEFAULT.getContent())
                .build();
    }
}
