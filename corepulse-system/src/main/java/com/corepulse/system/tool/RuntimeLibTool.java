package com.corepulse.system.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行库检测与修复工具（Visual C++ Redistributable）
 * <p>
 * 面向"程序提示缺少 xxx.dll 无法启动"这类高频故障：
 * <ul>
 *   <li>scanVcRedist：扫描已安装的 VC++ 运行库版本，并检查常见运行库 DLL 文件是否缺失</li>
 *   <li>repairVcRedist：使用 tool/运行库 下的离线安装包静默安装/修复（需用户确认 + UAC 提权）</li>
 * </ul>
 * 修复采用"确认 + 后台异步执行"模式：安装包以 /install /quiet /norestart 静默运行，
 * 安装完成后 LLM 再次调用 scanVcRedist 验证修复结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeLibTool implements SystemTool {

    /** 输出最大长度（避免撑爆 LLM 上下文；实测完整扫描结果约 5KB，预留余量防止末尾 dllCheck 被截断） */
    private static final int MAX_OUTPUT_LENGTH = 8000;

    /**
     * 扫描脚本：查询注册表中已安装的 VC++ 运行库 + 检查常见运行库 DLL 是否存在。
     * 输出统一为 JSON，便于 LLM 解读。
     */
    private static final String SCAN_SCRIPT = String.join("\n",
            "[Console]::OutputEncoding=[Text.Encoding]::UTF8;",
            "$installed=@();",
            "$paths='HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*';",
            "Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -like 'Microsoft Visual C++*' } | ForEach-Object { $installed += [pscustomobject]@{ name=$_.DisplayName; version=$_.DisplayVersion } };",
            "$runtimes=@{};",
            "foreach($arch in 'x64','x86'){ $p=\"HKLM:\\SOFTWARE\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\$arch\"; if(Test-Path $p){ $runtimes[$arch]=(Get-ItemProperty $p).Version } };",
            "$dlls=@();",
            "$sys32=[Environment]::GetFolderPath('System');",
            "$wow64=\"$env:windir\\SysWOW64\";",
            "foreach($d in 'msvcp140.dll','vcruntime140.dll','vcruntime140_1.dll','msvcp120.dll','msvcr120.dll','msvcp110.dll','msvcr110.dll','msvcp100.dll','msvcr100.dll'){",
            "  $dlls += [pscustomobject]@{ dll=$d; system32=(Test-Path \"$sys32\\$d\"); syswow64=(Test-Path \"$wow64\\$d\") }",
            "};",
            "[pscustomobject]@{ installedVcRedist=$installed; vc2015to2022Runtime=$runtimes; dllCheck=$dlls } | ConvertTo-Json -Depth 4");

    private final ToolPathConfig toolPath;

    @Override
    public String toolName() {
        return "runtime_lib_check_repair";
    }

    @Override
    public String toolDescription() {
        return "检测并修复 Visual C++ 运行库（VC++ Redistributable）：扫描已安装版本、检查 msvcp140.dll / vcruntime140.dll 等常见 DLL 是否缺失，并用本地离线安装包修复";
    }

    /**
     * 扫描已安装的 VC++ 运行库，并检查常见运行库 DLL 是否缺失
     *
     * @return JSON：installedVcRedist（已安装列表）、vc2015to2022Runtime（新版运行库注册表信息）、dllCheck（关键 DLL 存在性）
     */
    @Tool(name = "scanVcRedist", description = "检测电脑上的 Visual C++ 运行库状态：列出已安装的 VC++ Redistributable 版本，并检查 msvcp140.dll、vcruntime140.dll 等常见运行库 DLL 文件是否缺失。用户反馈\"缺少 xxx.dll 无法打开程序\"时先调用本工具。返回JSON。")
    public String scanVcRedist() {
        log.info("调用工具: scanVcRedist，开始扫描 VC++ 运行库与 DLL 状态");
        String output = runPowerShell(SCAN_SCRIPT);
        log.info("调用工具: scanVcRedist 完成，输出长度={}", output.length());
        return truncate(output, MAX_OUTPUT_LENGTH);
    }

    /**
     * 修复 VC++ 运行库（离线安装包静默安装）
     * <p>
     * 未确认时返回确认请求，LLM 需向用户解释后再带 confirmed=true 重新调用。
     * 安装过程后台异步执行（约 1-2 分钟），完成后应再次调用 scanVcRedist 验证。
     *
     * @param arch        要修复的架构：x64 / x86 / both（64 位系统建议 both，兼容 32 位程序）
     * @param confirmed   是否已获得用户确认
     * @param toolContext 工具上下文（含 sessionId）
     * @return 确认请求或安装启动结果
     */
    @Tool(name = "repairVcRedist", description = "使用本地离线安装包修复 Visual C++ 运行库（解决 msvcp140.dll、vcruntime140.dll 等缺失问题）。arch 取 x64 / x86 / both（64位系统一般选 both，可同时修复32位程序依赖）。首次调用会返回确认请求，向用户解释影响并获得同意后，将 confirmed 设为 true 重新调用。安装为后台静默执行，完成后应调用 scanVcRedist 验证修复结果。")
    public String repairVcRedist(
            @ToolParam(description = "要修复的架构：x64、x86 或 both") String arch,
            @ToolParam(description = "是否已获得用户确认，默认 false") Boolean confirmed,
            ToolContext toolContext) {

        Long sessionId = toolContext != null
                ? (Long) toolContext.getContext().get("sessionId")
                : null;
        String target = arch == null ? "" : arch.trim().toLowerCase();
        if (!target.equals("x64") && !target.equals("x86") && !target.equals("both")) {
            return "参数错误：arch 只能取 x64、x86 或 both。";
        }

        // 1. 收集要安装的目标（both = 先 x86 后 x64）
        Map<String, String> targets = new LinkedHashMap<>();
        if (target.equals("x86") || target.equals("both")) {
            targets.put("x86", toolPath.getVcRedistX86Path());
        }
        if (target.equals("x64") || target.equals("both")) {
            targets.put("x64", toolPath.getVcRedistX64Path());
        }

        // 2. 校验安装包存在
        for (Map.Entry<String, String> e : targets.entrySet()) {
            File installer = new File(e.getValue());
            if (!installer.exists()) {
                log.error("VC++ 安装包不存在: arch={}, path={}", e.getKey(), installer.getAbsolutePath());
                return "修复失败：未找到 " + e.getKey() + " 离线安装包，路径=" + installer.getAbsolutePath()
                        + "。请确认安装包已放置到 tool/运行库 目录。";
            }
        }

        // 3. 未确认：返回确认请求，由 LLM 转达用户
        if (!Boolean.TRUE.equals(confirmed)) {
            log.info("repairVcRedist 需要用户确认: arch={}, sessionId={}", target, sessionId);
            return "【需要用户确认】即将使用本地离线安装包修复 VC++ 运行库（架构：" + String.join("、", targets.keySet())
                    + "）。安装过程会弹出 UAC 管理员授权窗口，点击\"是\"后后台静默安装，约需 1-2 分钟，"
                    + "只安装/修复微软官方 VC++ 运行库组件，不会影响用户文件和其他软件。"
                    + "请向用户说明以上内容，用户同意后将 confirmed 设为 true 重新调用本工具。";
        }

        // 4. 已确认：UAC 提权静默安装（后台异步执行，立即返回）
        log.info("执行 repairVcRedist: arch={}, sessionId={}", target, sessionId);
        List<String> results = new ArrayList<>();
        for (Map.Entry<String, String> e : targets.entrySet()) {
            String r = ToolProcessRunner.startExeElevated(
                    e.getValue(), new String[]{"/install", "/quiet", "/norestart"});
            results.add(e.getKey() + ": " + r);
        }

        return "已发起 VC++ 运行库静默安装（" + String.join("；", results) + "）。"
                + "请提醒用户在 UAC 弹窗中点击\"是\"授权，安装约需 1-2 分钟；"
                + "待用户确认安装完成后，调用 scanVcRedist 验证修复结果，再向用户汇报。";
    }

    /** 执行 PowerShell 脚本并返回输出 */
    private String runPowerShell(String script) {
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
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
            return output.toString().trim();
        } catch (Exception e) {
            log.error("PowerShell 执行失败", e);
            return "检测失败：" + e.getMessage();
        }
    }

    /** 截断长文本 */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n...(输出过长已截断)";
    }
}
