package com.ruoyi.system.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 保留期维护仅接受服务端选出的主键，所有清理均限定接入方和批量大小。 */
public interface DashboardIntegrationRetentionMapper
{
    List<Map<String, Object>> selectPolicies(@Param("afterId") long afterId, @Param("limit") int limit);
    List<Long> selectExpiredRawBatchIds(@Param("integrationId") Long id, @Param("now") Timestamp now, @Param("limit") int limit);
    List<Long> selectExpiredRawMessageIds(@Param("integrationId") Long id, @Param("now") Timestamp now, @Param("limit") int limit);
    List<Long> selectExpiredSuccessMessageIds(@Param("integrationId") Long id, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
    List<Map<String, Object>> selectExpiredSuccessBatches(@Param("integrationId") Long id, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
    List<Long> selectExpiredRecordIds(@Param("integrationId") Long id, @Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
    int clearRawBatches(@Param("integrationId") Long id, @Param("ids") List<Long> ids);
    int clearRawMessages(@Param("integrationId") Long id, @Param("ids") List<Long> ids);
    int clearSuccessMessages(@Param("integrationId") Long id, @Param("ids") List<Long> ids);
    int deleteEmptySuccessBatch(@Param("integrationId") Long id, @Param("batchId") Long batchId);
    int compactSuccessBatch(@Param("integrationId") Long id, @Param("batchId") Long batchId,
            @Param("receipt") String receipt, @Param("now") Timestamp now);
    int deleteExpiredRecords(@Param("integrationId") Long id, @Param("ids") List<Long> ids);
}
