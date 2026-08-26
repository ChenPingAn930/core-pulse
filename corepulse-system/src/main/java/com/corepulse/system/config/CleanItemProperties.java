package com.corepulse.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 磁盘清理项配置属性
 * <p>
 * 绑定 clean-items.yml 中 corepulse.clean-items 列表，替代 DiskCleanTool 中硬编码的清理项。
 * 新增清理项只需在 yml 中追加一条，无需改 Java 代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "corepulse.clean-items")
public class CleanItemProperties {

    /** 清理项列表（顺序即展示顺序） */
    private List<Item> items = new ArrayList<>();

    /**
     * 单个清理项定义
     */
    @Data
    public static class Item {
        /** 清理项 key（LLM 函数调用时传入） */
        private String key;
        /** 清理项名称 */
        private String name;
        /** 影响说明（展示给用户） */
        private String impact;
        /** 执行命令（cmd /c 后接的命令） */
        private String command;
        /** 是否自动执行（false 时仅给出建议，不执行命令） */
        private boolean autoExecute;
        /** 不自动执行时的建议文案 */
        private String advice;
    }
}