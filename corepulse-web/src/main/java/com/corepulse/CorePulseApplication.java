package com.corepulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CorePulse 启动入口(包放在 com.corepulse 根, 自动扫描所有业务模块)。
 * Mapper 通过接口上的 @Mapper 注解自动注册, 不使用 @MapperScan 全包扫描,
 * 避免把 llm.function.ToolExecutor 等普通接口误注册为 MyBatis Mapper。
 */
@SpringBootApplication
public class CorePulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorePulseApplication.class, args);
        System.out.println("项目启动成功，port:0927");
    }
}
