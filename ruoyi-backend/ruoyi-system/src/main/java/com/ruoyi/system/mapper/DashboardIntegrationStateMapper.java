package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 接入执行的共享租约、加密快照、审计和站内告警。 */
public interface DashboardIntegrationStateMapper
{
    int claimLease(@Param("key") String key, @Param("owner") String owner, @Param("seconds") int seconds);
    String lockLease(@Param("key") String key);
    int releaseLease(@Param("key") String key, @Param("owner") String owner);
    Map<String,Object> snapshot(@Param("key") String key);
    int saveSnapshot(Map<String,Object> value);
    int audit(Map<String,Object> value);
    int alert(@Param("key") String key, @Param("resource") String resource, @Param("code") String code);
    int categorizedAlert(@Param("key") String key, @Param("category") String category,
            @Param("resource") String resource, @Param("code") String code);
    int acknowledge(@Param("id") Long id, @Param("actor") String actor);
    List<Map<String,Object>> audits(@Param("category") String category);
    List<Map<String,Object>> alerts();
    Map<String,Object> metrics();
    List<Map<String,Object>> auditsByScope(@Param("category") String category, @Param("resourceCode") String resourceCode);
    List<Map<String,Object>> alertsByScope(@Param("category") String category, @Param("resourceCode") String resourceCode);
    Map<String,Object> metricsByScope(@Param("category") String category, @Param("resourceCode") String resourceCode);
    List<Map<String,Object>> resources();
    String mediaResource(@Param("hash") String hash);
    String mediaResourceByReference(@Param("mediaRef") String mediaRef);
    int signal(@Param("id") Long id);
    Long version(@Param("id") Long id);
    int cleanupSnapshots();
    int cleanupAudits(@Param("days") int days);
    int cleanupLeases();
}
