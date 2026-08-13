package com.corepulse.system.tool;

/**
 * 系统工具接口
 * <p>
 * 所有可供 LLM 函数调用（Function Calling）使用的工具统一实现此接口。
 * 工具实现负责启动/操作本机的可执行程序（exe / 脚本）。
 */
public interface SystemTool {

    /**
     * 工具名称（LLM 函数调用时使用的函数名）
     */
    String toolName();

    /**
     * 工具描述（供 LLM 理解工具用途）
     */
    String toolDescription();
}
