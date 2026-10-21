package com.corepulse.chat.config;

import com.corepulse.system.tool.SystemTool;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Spring AI ChatClient 配置类。
 *
 * <p>这里手动组装两套完整的调用链：
 * <pre>
 * 主配置   -> OpenAiApi         -> OpenAiChatModel         -> primaryChatClient
 * 备用配置 -> OpenAiApi         -> OpenAiChatModel         -> backupChatClient
 * </pre>
 *
 * <p>DeepSeek 和通义千问都提供 OpenAI 兼容接口，因此可以使用
 * Spring AI 的 {@link OpenAiApi} 和 {@link OpenAiChatModel}。
 *
 * <p>本类只负责创建和组装对象，什么时候切换备用模型由业务层决定。
 */
@Configuration
public class ChatClientConfig {

    // ==================== 主模型配置 ====================

    /** 主模型请求地址，例如 DeepSeek 的 API 地址。 */
    @Value("${corepulse.llm.primary.base-url}")
    private String primaryBaseUrl;

    /** 主模型 API Key，从环境变量 DEEPSEEK_API_KEY 中读取。 */
    @Value("${corepulse.llm.primary.api-key}")
    private String primaryApiKey;

    /** 主模型名称，例如 deepseek-chat。 */
    @Value("${corepulse.llm.primary.model}")
    private String primaryModel;

    /** 主模型的随机性参数，数值越高，回答越有随机性。 */
    @Value("${corepulse.llm.primary.temperature}")
    private Double primaryTemperature;

    // ==================== 备用模型配置 ====================

    /** 备用模型请求地址，例如阿里百炼 OpenAI 兼容接口地址。 */
    @Value("${corepulse.llm.backup.base-url}")
    private String backupBaseUrl;

    /** 备用模型 API Key，从环境变量 DASHSCOPE_API_KEY 中读取。 */
    @Value("${corepulse.llm.backup.api-key}")
    private String backupApiKey;

    /** 备用模型名称，例如 qwen-plus。 */
    @Value("${corepulse.llm.backup.model}")
    private String backupModel;

    /** 备用模型的随机性参数。 */
    @Value("${corepulse.llm.backup.temperature}")
    private Double backupTemperature;

    /**
     * 创建主模型。
     *
     * <p>OpenAiApi 保存请求地址和 API Key；
     * OpenAiChatOptions 保存模型名和 temperature；
     * OpenAiChatModel 将二者组装成真正可以调用 LLM 的 ChatModel。
     */
    @Bean
    public ChatModel primaryChatModel(RecordingToolCallingManager toolCallingManager) {
        // 必须显式传 restClientBuilder：不传时 Spring AI 使用默认 RestClient，
        // 底层请求没有连接/读取超时，上游不响应就会永久阻塞线程
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(primaryBaseUrl)
                .apiKey(primaryApiKey)
                .restClientBuilder(timedRestClientBuilder())
                .build();

        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(primaryModel);
        options.setTemperature(primaryTemperature);

        return new OpenAiChatModel(
                api,
                options,
                toolCallingManager,
                singleAttemptRetry(),
                ObservationRegistry.create()
        );
    }

    /**
     * 创建备用模型。
     *
     * <p>备用模型与主模型使用相同的创建流程，但请求地址、API Key
     * 和模型名称来自 backup 配置。主模型连续失败时，业务层会调用它。
     */
    @Bean
    public ChatModel backupChatModel(RecordingToolCallingManager toolCallingManager) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(backupBaseUrl)
                .apiKey(backupApiKey)
                .restClientBuilder(timedRestClientBuilder())
                .build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(backupModel);
        options.setTemperature(backupTemperature);

        return new OpenAiChatModel(
                api,
                options,
                toolCallingManager,
                singleAttemptRetry(),
                ObservationRegistry.create()
        );
    }

    /**
     * 带超时的 RestClient：连接 5s、读取 60s。
     * <p>
     * 为什么必须设：Spring AI 默认 REST 客户端不设超时，LLM 服务一旦不响应，
     * 请求线程会无限挂起，接口永远不返回（前端看到的就是"一直执行中"）。
     * 60 秒读取上限的取舍：足够生成较长的回复，又不至于让用户等上好几分钟。
     */
    private RestClient.Builder timedRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * 单次尝试的 RetryTemplate（不做内部重试）
     * <p>
     * 外层 ChatServiceImpl 已经有 3 次指数退避重试 + 备用模型降级，
     * 若这里再用默认的 3 次，最坏耗时按乘法增长（3 × 3 × 超时时间），
     * 用户会等到以为卡死。失败快速上抛，由外层统一决策。
     */
    private RetryTemplate singleAttemptRetry() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }

    /**
     * 创建主模型对应的 ChatClient。
     *
     * <p>ChatClient 是业务层使用的调用入口；
     * {@code @Qualifier} 用于从两个 ChatModel Bean 中准确选择主模型。
     * {@code defaultTools} 将系统工具注册给主模型，允许模型执行电脑维修相关操作。
     */
    @Bean
    public ChatClient primaryChatClient(
            @Qualifier("primaryChatModel") ChatModel chatModel,
            List<SystemTool> systemTools) {
        return ChatClient.builder(chatModel)
                .defaultTools(systemTools.toArray())
                .build();
    }

    /**
     * 创建备用模型对应的 ChatClient。
     *
     * <p>备用 ChatClient 注册与主模型相同的系统工具，
     * 这样切换到备用厂商后，仍然可以执行电脑检测和维修工具。
     */
    @Bean
    public ChatClient backupChatClient(
            @Qualifier("backupChatModel") ChatModel chatModel,
            List<SystemTool> systemTools) {
        return ChatClient.builder(chatModel)
                .defaultTools(systemTools.toArray())
                .build();
    }
}
