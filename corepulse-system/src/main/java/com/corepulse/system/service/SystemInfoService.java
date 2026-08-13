package com.corepulse.system.service;

import com.corepulse.domain.vo.SystemInfoVO;

/**
 * 系统信息服务接口
 */
public interface SystemInfoService {

    /**
     * 获取硬件信息与实时状态
     *
     * @return 系统硬件信息视图
     */
    SystemInfoVO getSystemInfo();
}
