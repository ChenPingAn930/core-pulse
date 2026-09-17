package com.corepulse.chat.rag.impl;

import com.corepulse.chat.rag.KnowledgeService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 个人知识库（RAG）检索服务实现
 * <p>
 * 启动时读取 resources/knowledge 目录下的所有 MD 文档，按标题切块并向量化入库；
 * 运行时根据用户问题做相似度检索，返回命中片段文本。
 * <p>
 * 切块策略（兼容两种文档格式）：
 * - Markdown 格式（电脑维修指南）：{@code ### 2.1 标题} 开启新块，{@code ## 第X章} 更新当前章名
 * - 纯文本格式（软件迁移操作手册）：{@code 第X章 标题} 开启新章，{@code 1.1 标题}（行首数字.数字）开启新块；
 *   目录区（正文分隔符之前）的同名行不会被误认为章节标题
 * - 块文本前缀「章名 > 节名」上下文，提升检索相关性；裸 text 代码标记行作为噪声过滤
 * <p>
 * 向量数据持久化到本地 JSON 文件：启动时先加载已有数据，
 * 任一文档变更（按全部文档内容 MD5 指纹判断）则重建向量库。
 */
@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    /** 匹配「### 数字.数字 标题」格式的 Markdown 小节标题，例如 `### 3.2 系统运行卡顿/缓慢` */
    private static final Pattern SECTION_HEADING = Pattern.compile("^###\\s+\\d+\\.\\d+\\s+.*$");
    /** 匹配「## 第X章」或「## 中文数字、」格式的 Markdown 章节标题，
     *  例如 `## 第一章 自启动的来源`、`## 六、自启动"挡不住"的常见坑` */
    private static final Pattern CHAPTER_HEADING = Pattern.compile("^##\\s+(第[一二三四五六七八九十百\\d]+章|[一二三四五六七八九十百]+、).*$");
    /** 匹配纯文本章节标题（无 Markdown 符号），例如 `第四章 第三步：复制（robocopy 详解）` */
    private static final Pattern PLAIN_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百\\d]+章\\s+.*$");
    /** 匹配纯文本小节标题（行首 数字.数字），例如 `4.2 robocopy 标准命令` */
    private static final Pattern PLAIN_SECTION = Pattern.compile("^\\d+\\.\\d+\\s+\\S.*$");
    /** 代码块裸语言标记行（如单独一行 text），切块时作为噪声过滤 */
    private static final Pattern NOISE_LINE = Pattern.compile("^(text|txt)$");
    /** 默认检索条数 */
    private static final int DEFAULT_TOP_K = 3;
    /** 相似度阈值，过滤掉明显无关的片段 */
    private static final double SIMILARITY_THRESHOLD = 0.35;

    private final SimpleVectorStore vectorStore;

    /** 知识库文档位置（classpath 通配，默认 knowledge 目录下所有 MD） */
    @Value("${corepulse.rag.knowledge.location:classpath:knowledge/*.md}")
    private String knowledgeLocation;

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
        // 向量数据文件：存所有文档切块后的向量（JSON 格式）
        File storeFile = Paths.get(storeFilePath).toFile();
        // 指纹文件：记录"本次向量化时"所有 MD 文档内容的 MD5 哈希
        // 作用：本次启动时，用"当前指纹"对比"上次存的指纹"，
        //       一致=文档没变(直接加载缓存)，不一致=文档变了(重新向量化)
        File versionFile = new File(storeFile.getParentFile(), "knowledge-version.txt");
        // 当前指纹：现在所有知识文档内容的 MD5 哈希（内容改一个字符，哈希就完全不同）
        String currentFingerprint = currentMdFingerprint();

        // 情况1：向量文件存在 且 当前指纹 == 上次记录的指纹
        // 说明文档从上次到现在没变过 → 直接加载缓存，省时省钱（不重新调用 Embedding API）
        if (storeFile.exists() && storeFile.length() > 0
                // 指纹不为空 且 当前指纹 == 上次记录的指纹
                && currentFingerprint != null
                && currentFingerprint.equals(readFingerprint(versionFile))) {
            try {
                // 加载向量数据文件
                vectorStore.load(storeFile);
                log.info("RAG 知识库已从本地文件加载(文档未变更，无需重新向量化): {}", storeFile);
                return;
            } catch (Exception e) {
                // 缓存文件损坏/加载失败 → 删掉，走下面的重新向量化
                log.warn("RAG 知识库文件加载失败，将重新向量化: {}", e.getMessage());
                storeFile.delete();
            }
        } else if (storeFile.exists()) {
            // 情况2：向量文件存在 但 指纹不一致 → 文档变了，删掉旧缓存重建
            log.info("RAG 知识库文档已变更或指纹缺失，重新向量化...");
            storeFile.delete();
        } else {
            // 情况3：向量文件不存在 → 首次运行，直接走下面的向量化
            log.info("RAG 知识库无本地缓存，开始从 MD 文档向量化...");
        }
        // 走到这里说明需要重新向量化：切块 + 调 Embedding API + 存向量
        buildFromKnowledge(storeFile);
        // 向量化完成后，把当前指纹写进指纹文件，作为"下次对比"的依据
        writeFingerprint(versionFile, currentFingerprint);
    }
    // 检索
    @Override
    public List<String> search(String query, int topK) {
        // 空查询直接返回空列表
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        // topk大于0则使用topk，否则使用默认值
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
        buildFromKnowledge(Paths.get(storeFilePath).toFile());
    }

    /** 解析知识库目录下所有 MD 资源（按文件名排序，保证指纹与切块顺序稳定） */
    private List<Resource> resolveKnowledgeResources() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(knowledgeLocation);
        List<Resource> list = Arrays.stream(resources)
                .filter(Resource::isReadable)
                .sorted(Comparator.comparing(r -> String.valueOf(r.getFilename())))
                .collect(Collectors.toList());
        return list;
    }

    /**
     * 从所有知识库文档切块、向量化并持久化。
     * 这是 RAG 的"离线准备"阶段：把文档变成可检索的向量，存到本地文件。
     * 只有文档变更（指纹不一致）或首次运行时才会走到这里。
     */
    private void buildFromKnowledge(File storeFile) {
        try {
            // 1. 切块：遍历所有文档，把每篇文档切成若干小片段（chunk）
            //    片段是检索的最小单位，后续按片段做相似度匹配
            List<Document> chunks = new ArrayList<>();
            for (Resource resource : resolveKnowledgeResources()) {
                chunks.addAll(loadChunks(resource));
            }

            List<String> C = new ArrayList<>();
            C.add("初始化检查");//这两行没有什么作用，就是我想学ArrayList的底层，所以加了这两行

            // 2. 空保护：一个片段都没有就说明文档为空/解析失败，直接返回
            if (chunks.isEmpty()) {
                log.warn("知识库文档为空或解析失败, location={}", knowledgeLocation);
                return;
            }
            // 3. 向量化：把每个片段用 Embedding 模型转成向量，加入向量库
            //    向量是"文字的数字表示"，后续检索靠向量相似度
            vectorStore.add(chunks);
            // 4. 持久化：把向量数据存到本地 JSON 文件
            //    这样下次启动能直接加载，不用重新向量化（省时间、省 API 费用）
            File dir = storeFile.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();  // 目录不存在则先创建
            }
            vectorStore.save(storeFile);
            log.info("知识库已加载 {} 个片段并持久化到 {}", chunks.size(), storeFile);
        } catch (Exception e) {
            log.error("知识库初始化失败, location={}", knowledgeLocation, e);
        }
    }

    /**
     * 计算全部知识库文档的组合指纹（各文件 MD5 拼接后再取前 16 位）
     * <p>
     * 任一文档新增/修改/删除都会改变指纹，触发向量库重建。
     *
     * @return 指纹字符串；无文档或读取失败返回 null
     */
    private String currentMdFingerprint() {
        try {
            // 拿到所有知识文档（classpath:knowledge/*.md）
            List<Resource> resources = resolveKnowledgeResources();
            // 如果没有文档则返回 null
            if (resources.isEmpty()) {
                return null;
            }
            // MD5 是"内容哈希"：内容改一个字符，结果就完全不同
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (Resource resource : resources) {
                // 关键：把"文件名"也喂进哈希，这样新增/删除/重命名文件都会改变指纹
                md.update(resource.getFilename().getBytes(StandardCharsets.UTF_8));
                // 再把文件内容喂进去
                md.update(resource.getInputStream().readAllBytes());
            }
            // 把 MD5 的字节数组转成十六进制字符串（如 "a3f2..."）
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            // 只取前 16 位，够用且更短（16 位十六进制 = 64 bit，碰撞概率极低）
            return sb.length() > 16 ? sb.substring(0, 16) : sb.toString();
        } catch (Exception e) {
            log.warn("计算知识库文档指纹失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取指纹文件里记录的"上次向量化时的指纹"。
     * 作用：在 initialize() 里和当前指纹对比，判断文档是否变过。
     * 返回 null 表示：文件不存在 或 读取失败（此时 initialize() 会走"重新向量化"分支）。
     */
    private String readFingerprint(File versionFile) {
        try {
            if (versionFile.exists()) {
                // trim() 去掉首尾空白，防止写入时残留的换行符导致对比失败
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
     * 读取单个知识库文档并按标题切块
     * <p>
     * 兼容两种格式：
     * - Markdown（### X.Y 小节 / ## 第X章 章名）
     * - 纯文本（行首 第X章 章名 / 行首 X.Y 小节，如软件迁移操作手册）
     * 文档开头「目录」区的章节列表行会被识别并跳过，不会误切成垃圾块。
     *
     * @param resource 知识库文档资源
     * @return 切块后的 Document 列表
     */
    private List<Document> loadChunks(Resource resource) throws IOException {
        // 源文件名（去掉 .md 后缀），作为 metadata 里的 source 字段
        String sourceName = resource.getFilename() != null
                ? resource.getFilename().replaceFirst("\\.md$", "")
                : "知识库";
        // 把整个文档读成字符串（UTF-8 编码）
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 1. 去掉 YAML front-matter（--- ... ---）
        content = stripFrontMatter(content);

        // 2. 按标题切块：核心思路是"逐行扫描，遇到标题就开新块"
        //    sections 存切好的块：key=块标题，value=块内容
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String currentChapter = null;   // 当前章名，作为块的上下文前缀（让块知道自己在哪一章）
        String currentKey = null;       // 当前块的标题
        StringBuilder current = new StringBuilder();  // 正在累积的当前块内容
        boolean tocMode = false;        // 是否处于文档开头「目录」区（目录行不切块）
        boolean plainFormat = false;    // 是否纯文本格式文档（无 Markdown 符号的章标题出现后置 true）
        java.util.Set<String> tocChapters = new java.util.HashSet<>();  // 记录目录里出现过的章标题

        // 逐行扫描文档，核心逻辑：判断每行是"标题"还是"正文"，标题就开新块，正文就追加到当前块
        for (String line : content.split("\\r?\\n")) {
            String trimmed = line.trim();

            // 「目录」区识别与跳过：文档开头的目录行不产生块，避免切成一堆只有标题的垃圾块
            if ("目录".equals(trimmed)) {
                tocMode = true;   // 遇到"目录"二字，进入目录模式
                continue;
            }
            if (tocMode) {
                if (trimmed.isEmpty()) {
                    continue;   // 目录区里的空行，跳过
                }
                if (PLAIN_CHAPTER.matcher(trimmed).matches()) {
                    // 目录区里出现章标题（如"第一章 xxx"）
                    if (tocChapters.contains(trimmed)) {
                        tocMode = false; // 章标题第二次出现 = 已到正文，退出目录模式
                    } else {
                        tocChapters.add(trimmed);  // 第一次出现 = 目录条目，记录并跳过
                        continue;
                    }
                } else if (!SECTION_HEADING.matcher(trimmed).matches()) {
                    tocMode = false; // 非章节非小节的实内容行，说明正文开始了，退出目录模式
                }
            }

            if (NOISE_LINE.matcher(trimmed).matches()) {
                continue; // 裸 text 代码标记行，噪声过滤
            }
            if (CHAPTER_HEADING.matcher(trimmed).matches()
                    || PLAIN_CHAPTER.matcher(trimmed).matches()) {
                // ===== 遇到"章节标题"（## 第X章 / ## 中文数字、 / 纯文本第X章）=====
                if (PLAIN_CHAPTER.matcher(trimmed).matches()) {
                    plainFormat = true;  // 纯文本格式，后续小节也要用纯文本规则识别
                }
                // 先把当前累积的块存进 sections（冲刷），再开新块
                if (currentKey != null && current.length() > 0) {
                    sections.put(currentKey, current);
                }
                // 章名去掉 "## " 前缀，作为新块的标题，也作为章内小节的上下文前缀
                currentChapter = trimmed.replaceFirst("^##\\s+", "");
                currentKey = currentChapter;
                current = new StringBuilder();
                current.append(currentChapter).append("\n");  // 块首放章名，让 LLM 知道上下文
                continue;
            }
            if (SECTION_HEADING.matcher(trimmed).matches()
                    || (plainFormat && PLAIN_SECTION.matcher(trimmed).matches())) {
                // ===== 遇到"小节标题"（### X.Y / 纯文本 X.Y）=====
                if (currentKey != null && current.length() > 0) {
                    sections.put(currentKey, current);  // 冲刷当前块
                }
                currentKey = trimmed.replaceFirst("^###\\s+", "");  // 小节标题去掉 "### " 前缀
                current = new StringBuilder();
                if (currentChapter != null) {
                    current.append(currentChapter).append("\n");  // 块首放章名作上下文
                }
                current.append(currentKey).append("\n");  // 再放小节标题
                continue;
            }
            // ===== 普通内容行 =====
            if (currentKey != null) {
                current.append(line).append("\n");  // 追加到当前块
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
            String heading = entry.getKey().replace("### ", "").trim();  // 块标题（去掉 ### 前缀）
            String raw = entry.getValue().toString().trim();             // 块内容
            if (!StringUtils.hasText(raw)) {
                continue;  // 空块跳过（只有标题没有内容的块）
            }
            // 把风险警告前置到块首，再包装成 Document
            String text = prependRiskWarnings(raw);
            // Document = 文本 + metadata（来源文件名 + 章节标题），供检索时定位
            docs.add(new Document(text, Map.of("source", sourceName, "section", heading)));
        }
        log.info("知识库文档切块完成: {}, 共 {} 个片段", sourceName, docs.size());
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
     * 将命中的 Document 组装为可注入的文本（带来源与标题）
     */
    private String formatResult(Document doc) {
        Object section = doc.getMetadata().get("section");
        Object source = doc.getMetadata().get("source");
        String head = section != null ? "【" + section + "】" : "【知识库】";
        String from = source != null ? "（来源：" + source + "）" : "";
        return head + from + "\n" + doc.getText();
    }
}
