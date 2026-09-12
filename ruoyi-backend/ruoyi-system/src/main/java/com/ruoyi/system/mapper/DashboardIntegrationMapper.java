package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 第三方数据接入专用持久化接口。
 *
 * 出站 endpoint、入站接入方、接收台账和标准化记录与页面数据集分开维护，
 * 避免把外部身份或原始报文混入页面配置。
 */
public interface DashboardIntegrationMapper
{
    List<Map<String, Object>> selectEndpointList(@Param("sourceCode") String sourceCode);

    List<Map<String, Object>> selectEndpointPage(@Param("sourceCode") String sourceCode,
            @Param("keyword") String keyword, @Param("status") String status);

    Map<String, Object> selectEndpointById(@Param("endpointId") Long endpointId);

    Map<String, Object> selectEndpointByCode(@Param("sourceCode") String sourceCode,
            @Param("endpointCode") String endpointCode);

    int insertEndpoint(Map<String, Object> endpoint);

    int updateEndpoint(Map<String, Object> endpoint);

    int deleteEndpoint(@Param("endpointId") Long endpointId);

    int countEndpointReferences(@Param("sourceCode") String sourceCode,
            @Param("endpointCode") String endpointCode);

    int updateEndpointTest(@Param("endpointId") Long endpointId,
            @Param("status") String status);

    int updateEndpointHealth(Map<String, Object> health);

    List<Map<String, Object>> selectIntegrationList(@Param("keyword") String keyword,
            @Param("environment") String environment, @Param("status") String status);

    List<Map<String, Object>> selectIntegrationListByFolders(@Param("keyword") String keyword,
            @Param("environment") String environment, @Param("status") String status,
            @Param("folderIds") List<Long> folderIds);

    Map<String, Object> selectIntegrationById(@Param("integrationId") Long integrationId);

    Map<String, Object> selectIntegrationByCode(@Param("integrationCode") String integrationCode);

    int insertIntegration(Map<String, Object> integration);

    int updateIntegration(Map<String, Object> integration);

    int deleteIntegration(@Param("integrationId") Long integrationId);

    int updateIntegrationStatus(@Param("integrationId") Long integrationId,
            @Param("status") String status, @Param("updateBy") String updateBy);

    List<Map<String, Object>> selectIntegrationKeys(@Param("integrationId") Long integrationId);

    Map<String, Object> selectIntegrationKeyById(@Param("integrationKeyId") Long integrationKeyId);

    Map<String, Object> selectActiveIntegrationKey(@Param("integrationId") Long integrationId,
            @Param("keyId") String keyId);

    int insertIntegrationKey(Map<String, Object> key);

    int revokeIntegrationKey(@Param("integrationKeyId") Long integrationKeyId,
            @Param("revokeBy") String revokeBy);

    int touchIntegrationKey(@Param("integrationKeyId") Long integrationKeyId);

    Map<String, Object> selectBatchByIdempotency(@Param("integrationId") Long integrationId,
            @Param("endpointCode") String endpointCode,
            @Param("idempotencyKey") String idempotencyKey);

    // 唯一键冲突后使用当前读，不能复用 REPEATABLE READ 的旧快照。
    Map<String, Object> selectBatchByIdempotencyCurrent(@Param("integrationId") Long integrationId,
            @Param("endpointCode") String endpointCode, @Param("idempotencyKey") String idempotencyKey);
    Map<String, Object> selectMessageByBusinessKeyCurrent(@Param("integrationId") Long integrationId,
            @Param("profile") String profile, @Param("businessKey") String businessKey);

    Map<String, Object> selectBatchById(@Param("batchId") Long batchId);

    Map<String, Object> selectBatchByMessageId(@Param("integrationId") Long integrationId,
            @Param("messageId") String messageId);

    Map<String, Object> selectBatchByPlatformRequestId(@Param("platformRequestId") String platformRequestId);

    int insertBatch(Map<String, Object> batch);

    int updateBatch(Map<String, Object> batch);

    List<Long> selectRecoverableBatchIds(@Param("limit") Integer limit);

    List<Map<String, Object>> selectMessagesByBatch(@Param("batchId") Long batchId);

    Map<String, Object> selectMessageById(@Param("integrationMessageId") Long integrationMessageId);

    Map<String, Object> selectMessageByBusinessKey(@Param("integrationId") Long integrationId,
            @Param("profile") String profile, @Param("businessKey") String businessKey);

    int insertMessage(Map<String, Object> message);

    int updateMessage(Map<String, Object> message);

    Map<String, Object> selectRecordByBusinessKey(@Param("integrationId") Long integrationId,
            @Param("profile") String profile, @Param("businessKey") String businessKey);

    int insertRecord(Map<String, Object> record);

    int updateRecord(Map<String, Object> record);

    List<Map<String, Object>> selectBatchList(@Param("integrationId") Long integrationId,
            @Param("status") String status, @Param("limit") Integer limit);

    List<Map<String, Object>> selectDeadLetterList(@Param("integrationId") Long integrationId,
            @Param("keyword") String keyword, @Param("limit") Integer limit);

    int insertNonce(Map<String, Object> nonce);

    int deleteExpiredNonces();

    int clearExpiredRawBodies();

    int revokeExpiredMediaRefs();

    int deleteExpiredMediaCandidates();

    Map<String, Object> selectMediaRef(@Param("mediaRef") String mediaRef);

    int insertMediaRef(Map<String, Object> mediaRef);

    int consumeMediaCandidate(@Param("mediaRef") String mediaRef);

    int revokeMediaRefsByPage(@Param("pageId") Long pageId, @Param("revisionId") Long revisionId);

    int revokeMediaRefsForDeletedPages();

    int revokeMediaRefsByShare(@Param("shareId") Long shareId);
}
