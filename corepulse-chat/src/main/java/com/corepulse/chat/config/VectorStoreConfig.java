package com.corepulse.chat.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地向量存储配置
 * <p>
 * 使用 Spring AI {@link SimpleVectorStore}（内存向量库 + 本地 JSON 持久化），
 * 零外部依赖即可满足单文档知识库场景。
 * <p>
 * 数据持久化与加载由 {@code KnowledgeServiceImpl} 统一管理
 * （启动时先加载已有文件，为空则重新向量化并保存）。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
