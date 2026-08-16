package com.corepulse.task.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes 命令行调用器
 * <p>
 * 暂不启用：当前测试纯 LLM 能力，无需 Hermes。如需启用恢复 @Component。
 * <p>
 * 原功能：封装 ProcessBuilder 调用 hermes chat -q，以 UTF-8 读取输出（避免 Windows 中文乱码）。
 */
@Deprecated
@Slf4j
// @Component  // 已禁用 Hermes，恢复本行即可启用
public class HermesCommandRunner {

    /** Hermes 可执行文件路径 */
    @Value("${corepulse.agent.hermes-path:hermes}")
    private String hermesPath;

    /**
     * 同步调用 Hermes，返回完整输出
     *
     * @param query 任务指令
     * @return Hermes 输出文本
     */
    public String run(String query) {
        List<String> command = new ArrayList<>();
        command.add(hermesPath);
        command.add("chat");
        command.add("-q");
        command.add(query);
        command.add("-Q");
        command.add("--yolo");
        command.add("--source");
        command.add("tool");

        log.info("调用 Hermes: {}", query);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // UTF-8 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
            String result = output.toString().trim();
            log.info("Hermes 返回: {}", truncate(result, 500));
            return result;
        } catch (Exception e) {
            log.error("调用 Hermes 失败", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /** 截断长文本用于日志 */
    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
