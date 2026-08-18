package com.corepulse.chat.rag;

import java.util.List;

/**
 * 个人知识库（RAG）检索服务
 * <p>
 * 负责从本地向量库中按用户问题检索最相关的知识片段，供对话编排注入上下文。
 */
public interface KnowledgeService {

    /**
     * 根据查询内容检索最相关的知识片段
     *
     * @param query 用户问题
     * @param topK  返回的最大片段数
     * @return 命中的知识片段文本列表（按相关度降序）
     */
    List<String> search(String query, int topK);

    /**
     * 初始化向量库（幂等）：已存在数据则跳过，否则读取 MD 切块并向量化入库
     */
    void init();
}
