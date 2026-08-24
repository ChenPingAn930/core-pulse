package com.corepulse.chat.Enum;

import lombok.Getter;

/**
 * 专项功能引导枚举
 * <p>
 * 集中管理各「特定场景」的引导文案，供 LLM 在用户命中对应意图时按需注入，
 * 避免常驻 SYSTEM_DEFAULT 撑爆上下文。与 {@link ReinstallGuideEnum} 同模式。
 * <p>
 * 每个常量对应一个专项功能，文案只描述该场景下的操作规范：
 * - UNINSTALL：卸载残留校验（先快照 → 后卸载 → 再校验）
 * - DISK_CLEAN：磁盘清理四步流程
 * - VCREDIST：运行库 / DLL 缺失修复
 * - STRESS：CPU / 内存 / 显卡压力测试
 */
@Getter
public enum FeatureGuideEnum {

    /**
     * 卸载残留校验引导
     */
    UNINSTALL("""
            【卸载残留校验专用引导】用户当前有卸载/删除软件的需求。请严格按以下三步执行，
            禁止直接凭目录名猜测就宣布卸载干净：
            1. 卸载执行前：先调用 snapshotAppState 记录卸载前基线（AppData 顶层目录、注册表卸载项/配置键、
               服务、启动项的存在性），传入 appName（软件名）与已知的 installDirs/extraDataDirs。
            2. 执行卸载：用 runShellCommand 执行卸载命令（写操作需用户确认）。
            3. 卸载执行后：必须调用 verifyUninstallApp 重建快照并与基线 diff，得到结构化 JSON。
               只有当返回 JSON 的 clean 为 true 时，才能向用户说"卸载干净"；
               若 clean 为 false，必须把 items 中 residue=true 的项和路径完整列给用户，询问是否清理。
            注意：残留目录名往往和软件名无关（如沙漏=com.libxcc.ShaLou、FlClash=org.ikuuu、扣子=coze-updater），
            禁止仅凭"按软件名 dir 没搜到目录"就判定无残留，必须依赖 verifyUninstallApp 的 diff 结果。
            verifyUninstallApp 只读不删除；清理残留需用 runShellCommand 且必须经用户确认。
            """),

    /**
     * 磁盘清理引导
     */
    DISK_CLEAN("""
            【磁盘清理专用引导】用户当前有清理磁盘空间的需求。请按四步执行：
            1. 侦察：先调用 getDiskSpace 看各分区整体占用，不要直接全盘扫描。
            2. 定点扫描：用 scanDiskUsage 逐层下钻定位空间大户（分区根目录 → Users → 用户目录 →
               AppData/Desktop 等），每次只扫一级子目录。【禁止】用 dir /s 等全盘递归扫描命令。
            3. 分类：占用大户分三类——安全可删（临时文件/系统缓存/回收站/崩溃转储）用 cleanDiskSpace 清理；
               需用户选择（下载大文件/休眠文件）列清单告知用户，大文件建议移动而非删除；
               绝不触碰 WinSxS、Windows\\Installer、pagefile.sys、用户文档/桌面/聊天记录等个人数据。
            4. 同意→清理→汇报：cleanDiskSpace 首次调用返回确认请求，先展示清单和影响，用户同意后
               confirmed=true 执行；完成后汇报"清理前 X GB 可用 → 清理后 Y GB 可用"。
            浏览器缓存不自动清理，指导用户在浏览器设置里手动清除。用户说"帮我清理"时要主动动手执行，
            而不是给手动教程。
            """),

    /**
     * 运行库修复引导
     */
    VCREDIST("""
            【运行库修复专用引导】用户当前有运行库/DLL 缺失问题。请按以下流程执行：
            1. 先调用 scanVcRedist 检测运行库安装情况与 DLL 缺失情况，再决定是否修复。
            2. 确认缺失后调用 repairVcRedist 修复（64位系统一般传 arch=both）；首次调用返回
               "【需要用户确认】"，必须先向用户解释影响并获得同意，再以 confirmed=true 重新调用。
            3. 安装为后台静默执行，待用户确认安装完成后，再次调用 scanVcRedist 验证修复结果。
            禁止推荐用户从第三方网站下载"DLL 修复工具"，一律使用本工具的官方离线安装包。
            """),

    /**
     * 压力测试引导
     */
    STRESS("""
            【压力测试专用引导】用户当前有压力测试需求。请遵守以下规则：
            1. 内存压力测试统一使用 startMemStress，底层由 Java 进程内部执行，不依赖 Testlimit 或 MemTest64。
            2. startMemStress 会占用约70%物理内存，属高危操作；必须先向用户说明可能卡顿的风险，
               获得明确同意后再传入 confirmed=true。
            3. 启动压测后，用户要求"停止/关闭/结束/取消压测"时，必须调用对应的停止工具
               （stopCpuStress / stopMemStress 等），才能真正终止后台压测进程。
            4. 绝不能只回复"已停止"等文字而不调用停止工具，否则压测进程会继续在后台运行，
               导致 CPU/内存持续满载，用户误以为已停止。
            5. 停止工具的 taskId 从对话历史中获得（启动工具返回的 taskId）；无法确定时调用查询状态工具
               （getCpuStressStatus 等）先获取。
            """);

    /** 引导文案 */
    private final String content;

    FeatureGuideEnum(String content) {
        this.content = content;
    }
}