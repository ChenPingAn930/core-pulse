package com.corepulse.system.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 磁盘清理工具（C盘清理重点功能）
 * <p>
 * 按「侦察 → 分类 → 同意 → 清理 → 汇报」四步方法论设计：
 * - scanDiskUsage：定点限时扫描目录占用（绝不全盘递归扫），支持逐层下钻定位空间大户
 * - cleanDiskSpace：按硬编码白名单清理项执行安全清理，清理前后对比汇报释放量
 * <p>
 * 安全边界：
 * - 清理项路径全部由本类硬编码，LLM 只能传清理项 key，无法注入任意路径
 * - WinSxS、Windows\Installer、pagefile.sys 等系统关键目录绝不提供清理项
 * - 浏览器缓存/微信聊天记录/下载文件夹等用户数据不做自动删除
 */
@Slf4j
@Component
public class DiskCleanTool implements SystemTool {

    /** 单目录扫描超时（秒）：超时跳过该目录，避免全盘扫描卡死 */
    private static final int SCAN_TIMEOUT_SECONDS = 60;

    /** 单清理项执行超时（秒） */
    private static final int CLEAN_TIMEOUT_SECONDS = 120;

    /** 扫描时跳过的目录名前缀/名称（系统保留、无统计意义） */
    private static final List<String> SKIP_DIR_NAMES = List.of(
            "system volume information", "$recycle.bin", "$windows.~bt", "$windows.~ws"
    );

    /**
     * 清理项注册表：key -> 说明与执行命令。
     * <p>
     * 惯犯目录参考（供扫描分析时重点关注）：
     * %TEMP%、C:\Windows\Temp、C:\Windows\SoftwareDistribution\Download、
     * Downloads、AppData\Local\Temp、AppData\Local 下各软件缓存、C:\Windows\MEMORY.DMP
     */
    private static final Map<String, CleanItem> CLEAN_ITEMS = buildCleanItems();

    @Override
    public String toolName() {
        return "disk_clean";
    }

    @Override
    public String toolDescription() {
        return "磁盘空间分析与安全清理：定点限时扫描目录占用（可逐层下钻），按白名单清理项执行安全清理并汇报释放量";
    }

    /**
     * 定点扫描目录占用（侦察/下钻）
     * <p>
     * path 为空时扫各分区根目录的一级子目录；非空时扫指定目录的一级子目录。
     * 每个子目录独立统计、限时 60 秒，超时跳过——绝不全盘递归扫描。
     *
     * @param path 要扫描的目录（为空则扫所有分区根目录）
     * @return 按占用降序的目录占用清单文本
     */
    @Tool(name = "scanDiskUsage", description = "扫描目录下一级子目录的空间占用（限时扫描，超时自动跳过），按占用从大到小返回。"
            + "path 为空时扫各分区根目录；也可逐层下钻，如先扫 C:\\，再扫 C:\\Users，再扫 C:\\Users\\xxx。"
            + "分析C盘/磁盘空间占用时请用本工具，禁止用 dir /s 等全盘递归扫描命令。")
    public String scanDiskUsage(
            @ToolParam(description = "要扫描的目录路径（如 C:\\ 或 C:\\Users），为空则扫所有分区根目录", required = false) String path) {

        List<Path> roots = new ArrayList<>();
        if (path == null || path.isBlank()) {
            for (File root : File.listRoots()) {
                roots.add(root.toPath());
            }
            log.info("调用工具: scanDiskUsage，扫描所有分区根目录");
        } else {
            Path p = Path.of(path.trim());
            if (!Files.isDirectory(p)) {
                return "目录不存在或不是文件夹: " + path;
            }
            roots.add(p);
            log.info("调用工具: scanDiskUsage，扫描目录: {}", p);
        }

        StringBuilder result = new StringBuilder();
        for (Path root : roots) {
            result.append(scanOneLevel(root));
        }
        return result.toString();
    }

    /**
     * 执行磁盘清理（危险操作，需用户确认）
     * <p>
     * items 只能传白名单内的清理项 key；confirmed=false 时返回确认请求，
     * confirmed=true 时才真正执行，并在清理前后对比系统盘可用空间，汇报释放量。
     *
     * @param items     要清理的项目 key 列表（userTemp/sysTemp/wuCache/recycleBin/crashDump/hibernate）
     * @param confirmed 是否已获得用户确认
     * @param toolContext 工具上下文
     * @return 确认请求或清理结果（含释放量对比）
     */
    @Tool(name = "cleanDiskSpace", description = "执行磁盘空间清理（危险操作，必须先获得用户确认）。"
            + "items 为要清理的项目 key 列表，可选值: userTemp(用户临时文件)、sysTemp(系统临时文件)、"
            + "wuCache(Windows更新缓存)、recycleBin(回收站)、crashDump(崩溃转储文件)、hibernate(休眠文件，仅给出关闭建议不自动执行)。"
            + "首次调用 confirmed=false 会返回确认请求，必须向用户展示将清理的内容和影响，"
            + "用户同意后将 confirmed 设为 true 重新调用才真正执行。"
            + "禁止清理 WinSxS、Windows\\Installer、pagefile.sys、用户文档/桌面/聊天记录等数据。")
    public String cleanDiskSpace(
            @ToolParam(description = "要清理的项目 key 列表，如 [\"userTemp\",\"sysTemp\",\"recycleBin\"]") List<String> items,
            @ToolParam(description = "是否已获得用户确认（需为 true 才真正执行），默认 false") Boolean confirmed,
            ToolContext toolContext) {

        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;

        // 1. 校验清理项
        if (items == null || items.isEmpty()) {
            return "请指定要清理的项目，可选 key: " + String.join(", ", CLEAN_ITEMS.keySet());
        }
        List<CleanItem> targets = new ArrayList<>();
        for (String key : items) {
            CleanItem item = CLEAN_ITEMS.get(key);
            if (item == null) {
                return "未知的清理项: " + key + "。可选 key: " + String.join(", ", CLEAN_ITEMS.keySet());
            }
            targets.add(item);
        }

        // 2. 未确认：返回确认请求
        if (!Boolean.TRUE.equals(confirmed)) {
            log.info("cleanDiskSpace 需要用户确认: items={}, sessionId={}", items, sessionId);
            StringBuilder sb = new StringBuilder();
            sb.append("【需要用户确认】即将执行以下清理操作：\n");
            for (CleanItem item : targets) {
                sb.append("- ").append(item.key).append("（").append(item.name).append("）：")
                        .append(item.impact).append("\n");
            }
            sb.append("以上操作").append(targets.stream().anyMatch(i -> !i.autoExecute)
                            ? "中部分项目仅给出建议不会自动执行，" : "")
                    .append("均为系统缓存/临时文件类安全清理，不影响用户文档、桌面文件等个人数据。")
                    .append("请向用户展示该清单并说明影响，用户同意后将 confirmed 设为 true 重新调用本工具执行。");
            return sb.toString();
        }

        // 3. 已确认：执行清理并汇报释放量
        log.info("调用工具: cleanDiskSpace，用户已确认，执行清理: items={}, sessionId={}", items, sessionId);
        long freeBefore = systemDriveFreeBytes();
        StringBuilder sb = new StringBuilder();
        for (CleanItem item : targets) {
            sb.append(item.key).append("（").append(item.name).append("）：")
                    .append(executeCleanItem(item)).append("\n");
        }
        long freeAfter = systemDriveFreeBytes();
        double freedGb = (freeAfter - freeBefore) / (1024.0 * 1024 * 1024);
        sb.append("\n清理完成。系统盘可用空间：清理前 ")
                .append(String.format("%.1f", freeBefore / (1024.0 * 1024 * 1024))).append(" GB → 清理后 ")
                .append(String.format("%.1f", freeAfter / (1024.0 * 1024 * 1024))).append(" GB");
        if (freedGb > 0.05) {
            sb.append("，共释放约 ").append(String.format("%.1f", freedGb)).append(" GB。");
        } else {
            sb.append("，本次释放空间不明显（可能这些缓存本来就很少，或文件正被占用无法删除）。");
        }
        String result = sb.toString();
        log.info("cleanDiskSpace 执行完成: items={}, 释放={}GB, sessionId={}", items, String.format("%.2f", freedGb), sessionId);
        return result;
    }

    // ==================== 扫描实现 ====================

    /** 扫描某目录的一级子目录占用（每个限时），返回降序清单文本 */
    private String scanOneLevel(Path dir) {
        File[] children = dir.toFile().listFiles();
        if (children == null || children.length == 0) {
            return "目录 " + dir + " 为空或无权限读取。\n";
        }

        List<DirSize> sizes = new ArrayList<>();
        for (File child : children) {
            Path p = child.toPath();
            String nameLower = child.getName().toLowerCase();
            // 跳过系统保留目录、$ 开头目录、链接/junction（避免重复统计与死循环）
            if (nameLower.startsWith("$") || SKIP_DIR_NAMES.contains(nameLower)
                    || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            DirSize ds = measureDir(p);
            sizes.add(ds);
        }

        // 超时项放最后，其余按大小降序
        sizes.sort((a, b) -> {
            if (a.timeout != b.timeout) {
                return a.timeout ? 1 : -1;
            }
            return Double.compare(b.bytes, a.bytes);
        });

        StringBuilder sb = new StringBuilder();
        sb.append(dir).append(" 下各子目录占用（每项限时 ").append(SCAN_TIMEOUT_SECONDS).append(" 秒）：\n");
        sb.append("| 目录 | 占用 |\n|------|------|\n");
        for (DirSize ds : sizes) {
            sb.append("| ").append(ds.name).append(" | ")
                    .append(ds.timeout ? "扫描超时已跳过（占用可能很大）" : formatGb(ds.bytes))
                    .append(" |\n");
        }
        return sb + "\n";
    }

    /** 统计单个目录总大小（独立 PowerShell 进程，限时） */
    private DirSize measureDir(Path dir) {
        String script = String.format(
                "$s = (Get-ChildItem -LiteralPath '%s' -Recurse -File -Force -ErrorAction SilentlyContinue"
                        + " | Measure-Object -Property Length -Sum).Sum; if ($s) { $s } else { 0 }",
                dir.toString().replace("'", "''"));
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }
            boolean finished = process.waitFor(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("目录扫描超时已跳过: {}", dir);
                return new DirSize(dir.getFileName().toString(), 0, true);
            }
            String text = output.toString().trim();
            // 输出可能带小数或千分位，取数字部分解析
            String digits = text.replaceAll("[^0-9]", "");
            long bytes = digits.isEmpty() ? 0 : Long.parseLong(digits);
            return new DirSize(dir.getFileName().toString(), bytes, false);
        } catch (Exception e) {
            log.warn("目录扫描失败: {}", dir, e);
            return new DirSize(dir.getFileName().toString(), 0, true);
        }
    }

    // ==================== 清理实现 ====================

    /** 执行单个清理项，返回结果描述 */
    private String executeCleanItem(CleanItem item) {
        if (!item.autoExecute) {
            return item.advice;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", item.command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 消费输出避免缓冲区写满阻塞
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // 清理命令输出无意义，全部丢弃（错误已 2>nul 抑制）
                }
            }
            boolean finished = process.waitFor(CLEAN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "执行超时已中止（占用文件较多），可稍后重试";
            }
            return "已清理";
        } catch (Exception e) {
            log.error("清理项执行失败: {}", item.key, e);
            return "执行失败: " + e.getMessage();
        }
    }

    /** 系统盘当前可用空间（字节） */
    private long systemDriveFreeBytes() {
        String systemDrive = System.getenv("SystemDrive");
        File drive = new File(systemDrive != null ? systemDrive + "\\" : "C:\\");
        return drive.getUsableSpace();
    }

    /** 字节数格式化为 GB 文本 */
    private String formatGb(long bytes) {
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 构建清理项注册表（路径全部硬编码，杜绝路径注入） */
    private static Map<String, CleanItem> buildCleanItems() {
        Map<String, CleanItem> map = new LinkedHashMap<>();
        map.put("userTemp", new CleanItem("userTemp", "用户临时文件（%TEMP%）",
                "删除当前用户临时文件夹中的文件，正在被占用的文件会自动跳过，不影响任何软件正常使用",
                "cmd /c del /f /s /q \"%TEMP%\\*\" 2>nul & for /d %i in (\"%TEMP%\\*\") do @rd /s /q \"%i\" 2>nul",
                true, null));
        map.put("sysTemp", new CleanItem("sysTemp", "系统临时文件（C:\\Windows\\Temp）",
                "删除系统临时文件夹中的文件，正在被占用的文件会自动跳过，需要一定系统权限，部分文件可能删不掉属正常现象",
                "cmd /c del /f /s /q \"C:\\Windows\\Temp\\*\" 2>nul & for /d %i in (\"C:\\Windows\\Temp\\*\") do @rd /s /q \"%i\" 2>nul",
                true, null));
        map.put("wuCache", new CleanItem("wuCache", "Windows 更新缓存（SoftwareDistribution\\Download）",
                "停止 Windows Update 服务后删除已下载的更新安装包缓存，再重新启动服务。已安装成功的更新不受影响",
                "cmd /c net stop wuauserv 2>nul & del /f /s /q \"C:\\Windows\\SoftwareDistribution\\Download\\*\" 2>nul & net start wuauserv 2>nul",
                true, null));
        map.put("recycleBin", new CleanItem("recycleBin", "回收站",
                "清空回收站，注意：回收站里的文件清空后将无法恢复，执行前请确认回收站内没有误删的重要文件",
                "cmd /c powershell -NoProfile -Command \"Clear-RecycleBin -Force -ErrorAction SilentlyContinue\"",
                true, null));
        map.put("crashDump", new CleanItem("crashDump", "崩溃转储文件（MEMORY.DMP / Minidump）",
                "删除系统蓝屏/崩溃产生的内存转储文件。如果近期有蓝屏且还需要分析原因，建议先不要清理",
                "cmd /c del /f /q \"C:\\Windows\\MEMORY.DMP\" 2>nul & del /f /s /q \"C:\\Windows\\Minidump\\*\" 2>nul & del /f /s /q \"C:\\Windows\\LiveKernelReports\\*\" 2>nul",
                true, null));
        map.put("hibernate", new CleanItem("hibernate", "休眠文件（hiberfil.sys）",
                "关闭休眠功能可释放与内存大小相当的C盘空间，但关闭后将无法使用休眠（快速启动也可能受影响）",
                null, false,
                "休眠文件需要管理员权限关闭，本工具不自动执行。如用户确认不再使用休眠，"
                        + "请指导用户以管理员身份运行命令提示符执行 powercfg /h off，"
                        + "或由用户在同意后通过终端命令工具执行（需确认）。"));
        return map;
    }

    /** 目录大小扫描结果 */
    private record DirSize(String name, long bytes, boolean timeout) {
    }

    /** 清理项定义 */
    private static class CleanItem {
        final String key;
        final String name;
        final String impact;
        final String command;
        final boolean autoExecute;
        final String advice;

        CleanItem(String key, String name, String impact, String command, boolean autoExecute, String advice) {
            this.key = key;
            this.name = name;
            this.impact = impact;
            this.command = command;
            this.autoExecute = autoExecute;
            this.advice = advice;
        }
    }
}
