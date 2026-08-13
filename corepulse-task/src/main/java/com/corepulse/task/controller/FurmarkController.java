package com.corepulse.task.controller;

import com.corepulse.common.result.ApiResponse;
import com.corepulse.domain.dto.FurmarkStartDTO;
import com.corepulse.domain.dto.FurmarkStopDTO;
import com.corepulse.domain.entity.ToolTask;
import com.corepulse.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tools/furmark")
@RequiredArgsConstructor
public class FurmarkController {

    private final TaskService taskService;

    @PostMapping("/start")
    public ApiResponse<Map<String, Object>> start(@RequestBody @Valid FurmarkStartDTO dto) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("durationMin", dto.getDurationMin());
        ToolTask task = taskService.startTask("furmark", params, dto.getSessionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        return ApiResponse.ok(data);
    }

    @PostMapping("/stop")
    public ApiResponse<Map<String, Object>> stop(@RequestBody @Valid FurmarkStopDTO dto) {
        taskService.stopTask(dto.getTaskId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", dto.getTaskId());
        data.put("status", "CANCELLED");
        return ApiResponse.ok(data);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(@RequestParam Long taskId) {
        return ApiResponse.ok(taskService.getFurmarkStatus(taskId));
    }
}
