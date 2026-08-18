package com.corepulse.chat.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding（向量化）配置
 * <p>
 * 使用阿里百炼（DashScope）的 qwen3.7-text-embedding 模型，通过 OpenAI 兼容协议接入。
 * 注意：该项目 Chat 模型使用 DeepSeek（走 OpenAI 协议），而 Embedding 走百炼，
 * 二者 base-url 与 api-key 均不同，因此这里单独构建一个独立的 {@link OpenAiEmbeddingModel}，
 * 避免与 spring.ai.openai.* 的 Chat 自动配置冲突。
 */
@Configuration
public class EmbeddingConfig {

    /**
     * 百炼 OpenAI 兼容 base-url。
     * 注意：不能带 /v1 —— Spring AI 会自动拼接默认的 embeddingsPath(/v1/embeddings)，
     * 若 base-url 带 /v1 会得到 .../compatible-mode/v1/v1/embeddings 而 404。
     */
    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";

    /** 向量维度：qwen3.7-text-embedding 支持 256~2560，默认 1024，此处显式指定保持一致 */
    private static final int EMBEDDING_DIMENSIONS = 1024;

    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${corepulse.rag.embedding.api-key:}") String apiKey,
            @Value("${corepulse.rag.embedding.model:qwen3.7-text-embedding}") String model) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                // embeddingsPath 使用默认 /v1/embeddings，与 base-url 拼接为 compatible-mode/v1/embeddings
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(EMBEDDING_DIMENSIONS)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options);
    }
}
