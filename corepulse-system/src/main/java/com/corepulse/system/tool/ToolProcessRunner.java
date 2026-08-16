package com.corepulse.system.tool;

import com.corepulse.common.process.ProcessManager;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;

/**
 * 工具进程启动辅助类
 * <p>
 * 封装启动外部 exe 的通用逻辑：校验可执行文件存在、启动进程、可选注册到进程管理器。
 */
@Slf4j
public final class ToolProcessRunner {

    private ToolProcessRunner() {
    }

    /**
     * 启动一个外部 exe 程序
     *
     * @param exePath    可执行文件路径
     * @param args       启动参数（可为空）
     * @param taskId     关联任务 ID（需要进程管理时传入，否则传 null）
     * @return 启动结果描述
     */
    public static String startExe(String exePath, String[] args, Long taskId) {
        log.info("调用工具: 启动外部程序，exe={}", exePath);
        File exe = new File(exePath);
        if (!exe.exists()) {
            log.error("可执行文件不存在: {}", exePath);
            return "工具启动失败：未找到 " + exe.getName() + "，路径=" + exePath;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand(exePath, args));
            pb.directory(exe.getParentFile());
            Process process = pb.start();
            if (taskId != null) {
                ProcessManager.register(taskId, process);
            }
            log.info("调用工具: {} 已启动，pid={}", exe.getName(), process.pid());
            return "已启动 " + exe.getName() + "。";
        } catch (IOException e) {
            log.error("调用工具: 启动 {} 失败", exePath, e);
            return "工具启动失败：" + e.getMessage();
        }
    }

    private static String[] buildCommand(String exePath, String[] args) {
        if (args == null || args.length == 0) {
            return new String[]{exePath};
        }
        String[] command = new String[args.length + 1];
        command[0] = exePath;
        System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }

    /**
     * 以管理员权限启动一个外部 exe 程序
     * <p>
     * 部分工具（如 CrystalDiskMark 写入测速）需要管理员权限，直接用 ProcessBuilder
     * 启动会报 CreateProcess error=740（请求的操作需要提升）。本方法通过 PowerShell 的
     * Start-Process -Verb RunAs 触发 UAC 提权，用户在弹出的窗口中点击"是"即可授权运行。
     * <p>
     * 注意：提权启动会弹出一次 UAC 确认框，且进程无法被 ProcessManager 直接管理（因为
     * 它是通过 ShellExecute 独立启动的），所以仅用于不需要任务管理的一次性 GUI 工具。
     *
     * @param exePath 可执行文件路径
     * @param args    启动参数（可为空）
     * @return 启动结果描述
     */
    public static String startExeElevated(String exePath, String[] args) {
        log.info("调用工具: 以管理员权限启动外部程序，exe={}", exePath);
        File exe = new File(exePath);
        if (!exe.exists()) {
            log.error("可执行文件不存在: {}", exePath);
            return "工具启动失败：未找到 " + exe.getName() + "，路径=" + exePath;
        }
        try {
            // 拼装参数（含引号处理）
            String argStr = "";
            if (args != null && args.length > 0) {
                argStr = " " + String.join(" ", args);
            }
            // 用 PowerShell Start-Process -Verb RunAs 提权启动，目录切换到 exe 所在目录
            String psCommand = String.format(
                    "Start-Process -FilePath '%s' -WorkingDirectory '%s'%s",
                    exe.getAbsolutePath(), exe.getParentFile().getAbsolutePath(), argStr);
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", psCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            log.info("调用工具: {} 已触发 UAC 提权启动（等待用户在弹窗中授权）", exe.getName());
            return "已请求以管理员身份启动 " + exe.getName()
                    + "。请在系统弹出的 UAC 窗口中点击\u201c是\u201d以授权运行。";
        } catch (IOException e) {
            log.error("调用工具: 提权启动 {} 失败", exePath, e);
            return "工具启动失败（提权）:" + e.getMessage();
        }
    }
}
