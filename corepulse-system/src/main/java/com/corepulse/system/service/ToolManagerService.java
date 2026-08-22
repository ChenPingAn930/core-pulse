package com.corepulse.system.service;

import com.corepulse.system.tool.ToolPathConfig;
import com.corepulse.system.tool.ToolProcessRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 统一管理本地第三方工具的状态和来源信息。
 */
@Service
@RequiredArgsConstructor
public class ToolManagerService {

    private final ToolPathConfig toolPathConfig;

    private static final List<ToolDefinition> TOOL_DEFINITIONS = List.of(
            new ToolDefinition("furmark", "FurMark", "显卡压力测试", "tool/烤鸡工具/FurMark/FurMark.exe", "https://www.geeks3d.com/dl/get/830", "闭源软件，遵循官方 EULA", "27AB2E723E2E65DF720BCAFEA681D2104744EDA4A1E0A0374D7E61EAA820E63B", "FurMark 2.10.2", true, 8_000_000L),
            new ToolDefinition("cpuz", "CPU-Z", "CPU 和主板信息", "tool/处理器工具/CPUZ/cpuz_x64.exe", "https://download.cpuid.com/cpu-z/cpu-z_3.01-en.zip", "闭源免费软件，遵循官方 EULA", "8AE3B45D43D97E6CE19535C045CD1E5394C7A3A5334DC01F63EDD760ACE40827", "CPU-Z 3.01", true, 300_000L),
            new ToolDefinition("core-temp", "Core Temp", "CPU 温度监控", "tool/处理器工具/CoreTemp/Core Temp x64.exe", "https://www.alcpu.com/CoreTemp/CoreTemp64.zip", "闭源软件，遵循官方 EULA", "3B959529E975140DB47133AB34099522C66B12BF8E56DD5C55DEB8EF0632C7A", "Core Temp 1.20.1", true, 300_000L),
            new ToolDefinition("gpuz", "GPU-Z", "显卡信息", "tool/显卡工具/GPUZ/GPU-Z.exe", "https://www.techpowerup.com/download/techpowerup-gpu-z/", "闭源免费软件，遵循官方 EULA", "6CB0EF29682452DE81A9576808881685161411A1FAD00938BA04131159979C29", "GPU-Z 2.70.0", false, 300_000L),
            new ToolDefinition("smartmontools", "smartmontools", "磁盘 S.M.A.R.T. 检测", "tool/硬盘工具/smartmontools/bin/smartctl.exe", "https://github.com/smartmontools/smartmontools/releases/download/RELEASE_7_5/smartmontools-7.5.win32-setup.exe", "开源软件，遵循 GPL 许可", "", "smartmontools 7.5", false, 1_000_000L),
            new ToolDefinition("crystaldiskmark", "CrystalDiskMark", "磁盘性能测试", "tool/硬盘工具/CrystalDiskMark/DiskMark64S.exe", "https://sourceforge.net/projects/crystaldiskmark/files/9.0.3/CrystalDiskMark9_0_3.zip/download", "遵循官方许可条款", "", "CrystalDiskMark 9.0.3", true, 2_000_000L),
            new ToolDefinition("vc-redist-x64", "VC++ Redistributable x64", "64 位运行库修复", "tool/运行库/vc_redist.x64.exe", "https://aka.ms/vs/17/release/vc_redist.x64.exe", "微软软件，遵循微软许可条款", "CC0FF0EB1DC3F5188AE6300FAEF32BF5BEEBA4BDD6E8E445A9184072096B713B", "VC++ 14.x x64", false, 1_000_000L),
            new ToolDefinition("vc-redist-x86", "VC++ Redistributable x86", "32 位运行库修复", "tool/运行库/vc_redist.x86.exe", "https://aka.ms/vs/17/release/vc_redist.x86.exe", "微软软件，遵循微软许可条款", "F0BAB33A302B3CDB2E11113760D016F54FD3D2632C65BA7834FAC4F0ABD7F1A3", "VC++ 14.x x86", false, 1_000_000L)
    );

    public List<ToolStatus> getStatuses() {
        return TOOL_DEFINITIONS.stream()
                .map(this::checkStatus)
                .toList();
    }

    public PrepareResult prepare(boolean confirmed, List<String> requestedIds) {
        List<ToolStatus> statuses = getStatuses().stream()
                .filter(status -> requestedIds == null || requestedIds.isEmpty() || requestedIds.contains(status.id()))
                .toList();
        List<String> knownIds = TOOL_DEFINITIONS.stream()
                .map(ToolDefinition::id)
                .toList();
        List<String> unknownIds = requestedIds == null ? List.of() : requestedIds.stream()
                .filter(id -> !knownIds.contains(id))
                .toList();
        List<ToolStatus> missing = statuses.stream()
                .filter(status -> !status.ready())
                .toList();

        if (!confirmed) {
            return new PrepareResult(false, false, "准备第三方工具会从官方来源获取并写入本机文件，需要用户确认。", statuses, unknownIds);
        }
        if (!unknownIds.isEmpty()) {
            return new PrepareResult(true, false, "请求中包含未知工具，未执行准备操作。", statuses, unknownIds);
        }
        if (missing.isEmpty()) {
            return new PrepareResult(true, true, "所选工具均已准备完成。", statuses, List.of());
        }

        List<ToolStatus> prepared = missing.stream()
                .map(status -> prepareOne(status))
                .toList();
        List<ToolStatus> resultStatuses = statuses.stream()
                .map(status -> prepared.stream()
                        .filter(result -> result.id().equals(status.id()))
                        .findFirst()
                        .orElse(status))
                .toList();
        boolean completed = resultStatuses.stream().allMatch(ToolStatus::ready);
        return new PrepareResult(true, completed,
                completed ? "所选工具已全部准备完成。" : "部分工具准备失败或需要单独安装，请查看各工具 reason。",
                resultStatuses, List.of());
    }

    public InstallResult install(String arch, boolean confirmed) {
        String target = arch == null ? "" : arch.trim().toLowerCase();
        if (!target.equals("x64") && !target.equals("x86") && !target.equals("both")) {
            return new InstallResult(false, "参数错误：arch 只能取 x64、x86 或 both。", List.of());
        }
        List<String> architectures = target.equals("both") ? List.of("x86", "x64") : List.of(target);
        List<ToolStatus> statuses = architectures.stream()
                .map(item -> verifyInstaller(checkStatus(definition("vc-redist-" + item))))
                .toList();
        List<ToolStatus> missing = statuses.stream().filter(status -> !status.ready()).toList();
        if (!missing.isEmpty()) {
            return new InstallResult(false, "安装包不存在或尚未通过准备校验，请先执行工具准备。", statuses);
        }
        if (!confirmed) {
            return new InstallResult(false,
                    "需要用户确认：即将以管理员权限静默安装 VC++ Redistributable，可能弹出 UAC 授权窗口。", statuses);
        }

        List<ToolStatus> results = statuses.stream()
                .map(this::installVcRedist)
                .toList();
        boolean started = results.stream().allMatch(ToolStatus::ready);
        return new InstallResult(started,
                started ? "已发起 VC++ Redistributable 安装，请在 UAC 窗口中确认。"
                        : "部分 VC++ Redistributable 安装启动失败。", results);
    }

    private ToolStatus verifyInstaller(ToolStatus status) {
        if (!status.ready()) {
            return status;
        }
        try {
            ToolDefinition definition = definition(status.id());
            if (!sha256(Paths.get(status.absolutePath())).equalsIgnoreCase(definition.sha256())) {
                return withReason(status, "安装前 SHA-256 校验失败");
            }
            return status;
        } catch (Exception exception) {
            return withReason(status, "安装前校验失败: " + exception.getMessage());
        }
    }

    private ToolStatus installVcRedist(ToolStatus status) {
        if (!status.ready()) {
            return status;
        }
        String result = ToolProcessRunner.startExeElevated(status.absolutePath(),
                new String[]{"/install", "/quiet", "/norestart"});
        if (result.startsWith("已请求") || result.startsWith("已启动")) {
            return status;
        }
        return withReason(status, result);
    }

    private ToolStatus prepareOne(ToolStatus status) {
        if (status.id().equals("smartmontools")) {
            return prepareSmartmontools(status);
        }
        return isDownloadable(status.id())
                ? download(status)
                : withReason(status, unavailableReason(status.id()));
    }

    private boolean isDownloadable(String id) {
        return id.equals("cpuz") || id.equals("core-temp") || id.equals("gpuz") || id.equals("crystaldiskmark")
                || id.equals("furmark") || id.equals("vc-redist-x64") || id.equals("vc-redist-x86");
    }

    private String unavailableReason(String id) {
        return switch (id) {
            case "smartmontools" -> "官方发行包是安装器，需要用户确认 UAC；安装完成后重新检查状态。";
            case "gpuz" -> "官方动态下载未返回通过校验的可执行文件，请稍后重试或从官方页面获取。";
            default -> "暂无可验证的固定官方下载包。";
        };
    }

    private ToolDefinition definition(String id) {
        return TOOL_DEFINITIONS.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private ToolStatus prepareSmartmontools(ToolStatus status) {
        Path installer = resolvePath("tool/硬盘工具/smartmontools/.smartmontools-7.5-setup.exe");
        try {
            downloadFile(definition("smartmontools"), installer);
            String result = ToolProcessRunner.startExeElevated(installer.toString(),
                    new String[]{"/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART"});
            if (result.startsWith("已请求") || result.startsWith("已启动")) {
                return withReason(status, "smartmontools 安装器已启动，请完成 UAC 授权后重新检查工具状态。");
            }
            return withReason(status, result);
        } catch (Exception exception) {
            return withReason(status, "smartmontools 安装器准备失败: " + exception.getMessage());
        } finally {
            try {
                Files.deleteIfExists(installer);
            } catch (IOException ignored) {
            }
        }
    }

    private ToolStatus download(ToolStatus status) {
        ToolDefinition definition = definition(status.id());
        Path target = resolvePath(status.relativePath());
        Path archive = target.resolveSibling(target.getFileName() + ".download");
        Path extraction = target.getParent().resolve("." + target.getFileName() + ".extract");
        try {
            Files.createDirectories(target.getParent());
            downloadFile(definition, archive);
            if (definition.zip()) {
                if (!isZip(archive)) {
                    return withReason(status, "官方下载地址返回的不是 ZIP 文件");
                }
                deleteTree(extraction);
                Files.createDirectories(extraction);
                unzipSafely(archive, extraction);
                Path extractedEntry = findFile(extraction, target.getFileName().toString());
                if (extractedEntry == null || !Files.isRegularFile(extractedEntry)) {
                    return withReason(status, "压缩包内缺少入口文件: " + target.getFileName());
                }
                publishDirectory(publishRoot(extraction), target.getParent());
            } else {
                Files.move(archive, target, StandardCopyOption.REPLACE_EXISTING);
            }
            ToolStatus result = checkStatus(definition);
            return result.ready() ? result : withReason(result, "下载完成但配套文件检查失败: " + result.reason());
        } catch (Exception exception) {
            return withReason(status, "下载失败: " + exception.getMessage());
        } finally {
            try {
                Files.deleteIfExists(archive);
                deleteTree(extraction);
            } catch (IOException ignored) {
            }
        }
    }

    private void downloadFile(ToolDefinition definition, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(definition.officialUrl()))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "CorePulse-ToolManager/1.0");
        HttpRequest request;
        if (definition.id().equals("gpuz")) {
            request = requestBuilder
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("id=3180", StandardCharsets.UTF_8))
                    .build();
        } else {
            request = requestBuilder.GET().build();
        }
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("官方服务器返回 HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (definition.id().equals("gpuz") && !contentType.contains("application/octet-stream")
                && !contentType.contains("application/x-msdownload")) {
            throw new IOException("GPU-Z 官方页面要求浏览器完成下载校验，未返回可执行文件");
        }
        try (InputStream input = response.body()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(target) < definition.minimumBytes()) {
            throw new IOException("下载文件过小，疑似不是有效发行包");
        }
        if (!definition.zip() && !isPeFile(target)) {
            throw new IOException("官方下载内容不是有效的 Windows 可执行文件");
        }
        if (!definition.sha256().isBlank() && !sha256(target).equalsIgnoreCase(definition.sha256())) {
            throw new IOException("SHA-256 校验失败");
        }
    }

    private boolean isZip(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        }
    }

    private boolean isPeFile(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return input.read() == 'M' && input.read() == 'Z';
        }
    }

    private void unzipSafely(Path archive, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("压缩包包含非法路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private Path findFile(Path root, String fileName) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElse(null);
        }
    }

    private Path publishRoot(Path extraction) throws IOException {
        try (Stream<Path> paths = Files.list(extraction)) {
            List<Path> entries = paths.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
            return extraction;
        }
    }

    private void publishDirectory(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private ToolStatus withReason(ToolStatus status, String reason) {
        return new ToolStatus(status.id(), status.name(), status.purpose(), status.relativePath(),
                status.absolutePath(), status.officialUrl(), status.license(), "ERROR", false, reason);
    }

    private ToolStatus checkStatus(ToolDefinition definition) {
        String configuredPath = configuredPath(definition.id());
        Path path = resolvePath(configuredPath);
        if (definition.id().equals("smartmontools") && !Files.isRegularFile(path)) {
            path = findInstalledSmartctl(path);
            configuredPath = path.toString();
        }
        boolean executableReady = Files.isRegularFile(path) && Files.isExecutable(path);
        String dependencyReason = dependencyReason(definition.id(), path);
        boolean ready = executableReady && dependencyReason == null;
        String reason = ready ? null : !executableReady ? "目标文件不存在或不可执行" : dependencyReason;
        return new ToolStatus(
                definition.id(),
                definition.name(),
                definition.purpose(),
                configuredPath,
                path.toAbsolutePath().normalize().toString(),
                definition.officialUrl(),
                definition.license(),
                ready ? "READY" : "MISSING",
                ready,
                reason
        );
    }

    private Path findInstalledSmartctl(Path configured) {
        List<Path> candidates = List.of(
                configured,
                Paths.get(System.getenv("ProgramFiles"), "smartmontools", "bin", "smartctl.exe"),
                Paths.get(System.getenv("ProgramFiles(x86)"), "smartmontools", "bin", "smartctl.exe")
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(configured);
    }

    private String dependencyReason(String id, Path executable) {
        Path directory = executable.getParent();
        List<Path> required = switch (id) {
            case "furmark" -> List.of(directory.resolve("gpushark.exe"), directory.resolve("cpuburner.exe"));
            case "smartmontools" -> List.of(directory.resolve("drivedb.h"));
            case "crystaldiskmark" -> List.of(directory.resolve("CdmResource"),
                    directory.resolve("CdmResource").resolve("DiskSpd").resolve("DiskSpd64.exe"));
            default -> List.of();
        };
        List<String> missing = required.stream()
                .filter(path -> !Files.exists(path))
                .map(path -> path.getFileName().toString())
                .toList();
        return missing.isEmpty() ? null : "缺少配套文件: " + String.join(", ", missing);
    }

    private Path resolvePath(String relativePath) {
        return Paths.get(relativePath).toAbsolutePath().normalize();
    }

    private String configuredPath(String id) {
        return switch (id) {
            case "furmark" -> toolPathConfig.getFurmarkPath();
            case "cpuz" -> toolPathConfig.getCpuzPath();
            case "core-temp" -> toolPathConfig.getCoreTempPath();
            case "gpuz" -> toolPathConfig.getGpuzPath();
            case "smartmontools" -> toolPathConfig.getSmartctlPath();
            case "crystaldiskmark" -> toolPathConfig.getCrystalDiskMarkPath();
            case "vc-redist-x64" -> toolPathConfig.getVcRedistX64Path();
            case "vc-redist-x86" -> toolPathConfig.getVcRedistX86Path();
            default -> throw new IllegalArgumentException("未知工具: " + id);
        };
    }

    private record ToolDefinition(String id, String name, String purpose, String relativePath,
                                  String officialUrl, String license, String sha256,
                                  String version, boolean zip, long minimumBytes) {
    }

    public record ToolStatus(String id, String name, String purpose, String relativePath,
                             String absolutePath, String officialUrl, String license,
                             String status, boolean ready, String reason) {
    }

    public record PrepareResult(boolean confirmed, boolean completed, String message,
                                List<ToolStatus> tools, List<String> unknownIds) {
    }

    public record InstallResult(boolean started, String message, List<ToolStatus> tools) {
    }
}
