package com.corepulse.chat.rag.impl;

import com.corepulse.chat.rag.KnowledgeService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 个人知识库（RAG）检索服务实现
 * <p>
 * 启动时读取 resources/knowledge 下的 MD 文档，按「二级标题(### x.y)」切块并向量化入库；
 * 运行时根据用户问题做相似度检索，返回命中片段文本。
 * <p>
 * 切块策略：只有形如 {@code ### 2.1 按开机键完全没反应} 的标题才开启新块，
 * 其余（如 3.3 章节错乱用 {@code ###} 开头的列表行）并入当前块，保证语义完整。
 * <p>
 * 向量数据持久化到本地 JSON 文件：启动时先加载已有数据，为空则重新向量化并保存，
 * 避免每次启动重复调用 Embedding API。
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    /** 匹配「### 数字.数字 标题」格式的真实小节标题，例如 `### 3.2 系统运行卡顿/缓慢` */
    private static final Pattern SECTION_HEADING = Pattern.compile("^###\\s+\\d+\\.\\d+\\s+.*$");
    /** 匹配「## 第X章」章节标题，用于跳过 */
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^##\\s+第.*$");
    /** 默认检索条数 */
    private static final int DEFAULT_TOP_K = 3;
    /** 相似度阈值，过滤掉明显无关的片段 */
    private static final double SIMILARITY_THRESHOLD = 0.35;

    private final SimpleVectorStore vectorStore;

    /** 知识库 MD 资源路径（classpath） */
    @Value("${corepulse.rag.knowledge.path:classpath:knowledge/电脑维修指南_v3.md}")
    private String knowledgePath;

    /** 向量数据持久化文件 */
    @Value("${corepulse.rag.vector-store.file:${user.home}/.corepulse/vector-store.json}")
    private String storeFilePath;

    public KnowledgeServiceImpl(SimpleVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 初始化：加载已持久化向量数据，若无或文档已变更则从 MD 文档重新切块并向量化入库。
     * <p>
     * 通过对比 MD 内容指纹（MD5）判断文档是否更新过：若文档变更则重建向量库，
     * 避免修改知识库后仍使用旧向量数据。
     */
    @PostConstruct
    public void initialize() {
        File storeFile = Paths.get(storeFilePath).toFile();
        // 指纹文件：记录上次向量化时的 MD 内容哈希
        File versionFile = new File(storeFile.getParentFile(), "knowledge-version.txt");
        String currentFingerprint = currentMdFingerprint();

        if (storeFile.exists() && storeFile.length() > 0
                && currentFingerprint != null
                && currentFingerprint.equals(readFingerprint(versionFile))) {
            try {
                vectorStore.load(storeFile);
                log.info("RAG 知识库已从本地文件加载(文档未变更，无需重新向量化): {}", storeFile);
                return;
            } catch (Exception e) {
                log.warn("RAG 知识库文件加载失败，将重新向量化: {}", e.getMessage());
                storeFile.delete();
            }
        } else if (storeFile.exists()) {
            log.info("RAG 知识库文档已变更或指纹缺失，重新向量化...");
            storeFile.delete();
        } else {
            log.info("RAG 知识库无本地缓存，开始从 MD 文档向量化...");
        }
        buildFromMarkdown(storeFile);
        writeFingerprint(versionFile, currentFingerprint);
    }

    @Override
    public List<String> search(String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int k = topK > 0 ? topK : DEFAULT_TOP_K;
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(k)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .build());
        List<String> results = docs.stream()
                .map(this::formatResult)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        // 观察日志：方便确认每次对话知识库命中情况
        if (results.isEmpty()) {
            log.debug("RAG 检索未命中: query={}", query);
        } else {
            log.info("RAG 检索命中 {} 条, query={}, sections={}",
                    results.size(), query,
                    docs.stream().map(d -> d.getMetadata().get("section")).collect(Collectors.toList()));
        }
        return results;
    }

    @Override
    public void init() {
        // 幂等加载：已有片段则跳过
        if (!vectorStore.similaritySearch(
                SearchRequest.builder().query("初始化检查").topK(1).build()).isEmpty()) {
            log.info("知识库已有数据，跳过加载");
            return;
        }
        buildFromMarkdown(Paths.get(storeFilePath).toFile());
    }

    /**
     * 从 MD 文档切块、向量化并持久化
     */
    private void buildFromMarkdown(File storeFile) {
        try {
            List<Document> chunks = loadChunks();
            if (chunks.isEmpty()) {
                log.warn("知识库文档为空或解析失败, path={}", knowledgePath);
                return;
            }
            vectorStore.add(chunks);
            // 持久化到本地文件
            File dir = storeFile.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            vectorStore.save(storeFile);
            log.info("知识库已加载 {} 个片段并持久化到 {}", chunks.size(), storeFile);
        } catch (Exception e) {
            log.error("知识库初始化失败, path={}", knowledgePath, e);
        }
    }

    /**
     * 计算当前 MD 文档内容的指纹（MD5 前 16 位）
     *
     * @return 指纹字符串；文档缺失或读取失败返回 null
     */
    private String currentMdFingerprint() {
        try {
            ClassPathResource resource = new ClassPathResource(knowledgePath.replace("classpath:", ""));
            if (!resource.exists()) {
                return null;
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.length() > 16 ? sb.substring(0, 16) : sb.toString();
        } catch (Exception e) {
            log.warn("计算知识库文档指纹失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取已记录的指纹文件内容（trim 后）
     */
    private String readFingerprint(File versionFile) {
        try {
            if (versionFile.exists()) {
                return Files.readString(versionFile.toPath()).trim();
            }
        } catch (IOException e) {
            log.warn("读取知识库版本指纹失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将指纹写入文件
     */
    private void writeFingerprint(File versionFile, String fingerprint) {
        if (fingerprint == null) {
            return;
        }
        try {
            File dir = versionFile.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            Files.writeString(versionFile.toPath(), fingerprint, StandardCharsets.UTF_8);
            log.info("已记录知识库版本指纹: {}", fingerprint);
        } catch (IOException e) {
            log.warn("写入知识库版本指纹失败: {}", e.getMessage());
        }
    }

    /**
     * 读取 MD 文件并按小节标题切块
     */
    private List<Document> loadChunks() throws IOException {
        ClassPathResource resource = new ClassPathResource(knowledgePath.replace("classpath:", ""));
        if (!resource.exists()) {
            log.error("知识库文档不存在: {}", resource);
            return List.of();
        }
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 1. 去掉 YAML front-matter（--- ... ---）
        content = stripFrontMatter(content);

        // 2. 按小节标题切块
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String currentKey = null;
        StringBuilder current = new StringBuilder();

        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (CHAPTER_HEADING.matcher(trimmed).matches()) {
                continue; // 章节标题跳过，不单独成块
            }
            if (SECTION_HEADING.matcher(trimmed).matches()) {
                // 开启新块
                if (currentKey != null && current.length() > 0) {
                    sections.put(currentKey, current);
                }
                currentKey = trimmed;
                current = new StringBuilder();
                current.append(trimmed).append("\n");
                continue;
            }
            if (currentKey != null) {
                current.append(line).append("\n");
            }
        }
        if (currentKey != null && current.length() > 0) {
            sections.put(currentKey, current);
        }

        // 3. 转换为 Document，附带标题 metadata
        //    风险警告前置：把块内的「⚠️ 风险警告」段落提取并置于块首，确保 LLM 在给出任何
        //    硬件操作步骤前，必然先看到并转述这些安全警告，避免步骤讲完却遗漏块尾的风险提示。
        List<Document> docs = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : sections.entrySet()) {
            String heading = entry.getKey().replace("### ", "").trim();
            String raw = entry.getValue().toString().trim();
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String text = prependRiskWarnings(raw);
            docs.add(new Document(text, Map.of("source", "电脑维修指南_v3", "section", heading)));
        }
        return docs;
    }

    /**
     * 将文本中的「⚠️ 风险警告」段落提取出来，前置到文本开头（去重后）。
     * 若文本中没有风险警告，则原样返回。
     */
    private String prependRiskWarnings(String text) {
        if (text == null || !text.contains("风险警告")) {
            return text;
        }
        List<String> warnings = new ArrayList<>();
        List<String> others = new ArrayList<>();
        // 逐段拆分，按「⚠️ 风险警告」或「风险警告」开头识别警告段
        for (String para : text.split("\\r?\\n")) {
            String t = para.trim();
            if (t.startsWith("⚠️ 风险警告") || t.startsWith("⚠️风险警告") || t.startsWith("风险警告")) {
                if (!warnings.contains(t)) {
                    warnings.add(t);
                }
            } else {
                others.add(para);
            }
        }
        if (warnings.isEmpty()) {
            return text;
        }
        // 前置一句引导，再把所有风险警告放最前
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 以下是本故障场景必须遵守的安全风险警告，回答用户时必须先完整转述：\n");
        for (String w : warnings) {
            sb.append(w).append("\n");
        }
        sb.append("------\n");
        for (String p : others) {
            sb.append(p).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 去掉 MD 开头的 YAML front-matter（--- 包裹的元数据段）
     */
    private String stripFrontMatter(String content) {
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                return content.substring(end + 4);
            }
        }
        return content;
    }

    /**
     * 将命中的 Document 组装为可注入的文本（带来源标题）
     */
    private String formatResult(Document doc) {
        Object section = doc.getMetadata().get("section");
        String head = section != null ? "【" + section + "】" : "【知识库】";
        return head + "\n" + doc.getText();
    }
}
