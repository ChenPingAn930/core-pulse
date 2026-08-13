package com.corepulse.task.controller;

import com.corepulse.common.result.ApiResponse;
import com.corepulse.domain.dto.TaskConfirmDTO;
import com.corepulse.domain.vo.TaskVO;
import com.corepulse.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{taskId}")
    public ApiResponse<TaskVO> get(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.getTaskVO(taskId));
    }

    @PostMapping("/{taskId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long taskId,
                                                    @RequestBody @Valid TaskConfirmDTO dto) {
        taskService.confirm(taskId, dto.getApproved());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("status", dto.getApproved() ? "RUNNING" : "CANCELLED");
        return ApiResponse.ok(data);
    }
}
