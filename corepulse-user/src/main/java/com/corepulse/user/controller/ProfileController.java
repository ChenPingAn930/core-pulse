package com.corepulse.user.controller;

import com.corepulse.common.exception.BizException;
import com.corepulse.common.result.ApiResponse;
import com.corepulse.common.result.ResultCode;
import com.corepulse.domain.entity.User;
import com.corepulse.domain.vo.ProfileVO;
import com.corepulse.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserMapper userMapper;

    @GetMapping
    public ApiResponse<ProfileVO> get() {
        User user = userMapper.selectById(1L);
        if (user == null) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        ProfileVO vo = new ProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setApiKeyMasked(mask(user.getApiKey()));
        vo.setCreatedAt(user.getCreatedAt());
        return ApiResponse.ok(vo);
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "未配置";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
