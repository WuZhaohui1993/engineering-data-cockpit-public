package com.ruoyi.system.mapper;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface DashboardIntegrationPolicyMapper
{
    Map<String, Object> selectPolicy();
    int savePolicy(@Param("configJson") String configJson, @Param("actor") String actor);
    int deleteExpiredConfirmedAlerts(@Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
    int deleteExpiredTestLogs(@Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
}
