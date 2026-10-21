package com.corepulse.system.controller;

import com.corepulse.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 诊断工具库列表接口（GET /api/tools）
 * <p>
 * 前端工具库页面只负责展示与"点击运行"：点击后把 command 注入聊天输入框，
 * 由 LLM 决定调用哪个工具，所以这里只提供元数据，不做任何真实执行。
 * <p>
 * id 与前端图标表（t1..t6）一一对应：前端优先使用本地图标，取不到才用 icon 字段，
 * 因此这里 icon 返回空字符串即可，避免把 SVG 冗余塞进接口响应。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolCatalogController {

    /** 工具库条目，字段与前端 ToolItem 保持一致 */
    public record ToolItem(String id, String icon, String name, String desc, String command) {
    }

    private static final List<ToolItem> CATALOG = List.of(
            new ToolItem("t1", "", "显卡压力测试",
                    "FurMark 满载运行，观察温度、帧率与稳定性，判断显卡是否存在散热或供电问题",
                    "帮我做一次显卡压力测试"),
            new ToolItem("t2", "", "CPU 压力测试",
                    "多线程满载运行，检测高负载下的频率、温度与系统稳定性",
                    "帮我做一次 CPU 压力测试"),
            new ToolItem("t3", "", "内存压力测试",
                    "独立进程持续占用并校验内存，排查蓝屏、卡顿等内存故障",
                    "帮我做一次内存压力测试"),
            new ToolItem("t4", "", "磁盘健康检测",
                    "读取 SMART 信息，检测坏道、通电时间与剩余寿命",
                    "帮我检测一下磁盘健康状态"),
            new ToolItem("t5", "", "磁盘空间分析",
                    "扫描各目录占用，定位膨胀的缓存、日志与临时文件",
                    "帮我分析一下磁盘空间占用"),
            new ToolItem("t6", "", "运行库检测",
                    "检测 VC++ 运行库安装情况，缺失时提供修复方案",
                    "帮我检测一下 VC++ 运行库")
    );

    @GetMapping
    public ApiResponse<List<ToolItem>> list() {
        return ApiResponse.ok(CATALOG);
    }
}
