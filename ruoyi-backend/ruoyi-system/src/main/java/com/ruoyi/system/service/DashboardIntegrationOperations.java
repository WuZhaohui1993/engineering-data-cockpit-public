package com.ruoyi.system.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Set;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DashboardIntegrationStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 数据库锁提供消费 fencing；Redis 只保存共享限流和断路状态，不保存明文业务报文。 */
@Service
public class DashboardIntegrationOperations
{
    @Autowired private DashboardIntegrationStateMapper mapper;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Environment environment;
    @Autowired private StringRedisTemplate redis;
    @Autowired(required=false) private DashboardIntegrationPolicyService policyService;
    private final ObjectMapper json = new ObjectMapper();

    public <T> T withLease(String key, int seconds, Supplier<T> action)
    {
        String owner = UUID.randomUUID().toString();
        mapper.claimLease(key, owner, Math.max(10, Math.min(seconds, 3600)));
        try
        {
            return new TransactionTemplate(transactions).execute(status -> {
                // 持有行锁至业务事务提交。租期经过也不能让另一节点写入，避免暂停后的旧消费者覆盖新节点。
                if (!owner.equals(mapper.lockLease(key))) return null;
                return action.get();
            });
        }
        finally { mapper.releaseLease(key, owner); }
    }

    public Map<String,Object> snapshot(String key, boolean staleAllowed)
    {
        Map<String,Object> row = mapper.snapshot(key);
        if (row == null) return null;
        Object deadline=row.get("freshUntil");
        Instant until=deadline instanceof Timestamp timestamp?timestamp.toInstant():deadline instanceof java.time.LocalDateTime time?time.atZone(java.time.ZoneId.systemDefault()).toInstant():Instant.parse(String.valueOf(deadline));
        boolean stale = !until.isAfter(Instant.now());
        if (stale && !staleAllowed) return null;
        try
        {
            Map<String,Object> value = json.readValue(DashboardIntegrationCrypto.decrypt(
                    String.valueOf(row.get("payloadCiphertext")), environment), new TypeReference<>() { });
            value.put("snapshotStale", stale);
            return value;
        }
        catch (Exception ex) { throw new ServiceException("接入快照无法读取"); }
    }

    public void saveSnapshot(String key, String source, String endpoint, Map<String,Object> value,
            int freshSeconds, int staleSeconds)
    {
        try
        {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key",key); row.put("source",source); row.put("endpoint",endpoint);
            row.put("ciphertext",DashboardIntegrationCrypto.encrypt(json.writeValueAsString(value),environment));
            row.put("freshSeconds",freshSeconds);
            row.put("retentionSeconds",Math.max(1,freshSeconds+staleSeconds));
            mapper.saveSnapshot(row);
        }
        catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("接入快照保存失败"); }
    }

    public boolean rateAllowed(String key, int limit)
    {
        if (limit <= 0) return true;
        Long count = redis.execute(new DefaultRedisScript<>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],1000) end; return n",Long.class),
                List.of("dashboard:integration:rate:"+key));
        return count != null && count <= limit;
    }

    public String acquirePermit(String key, int limit, int seconds)
    {
        String owner=UUID.randomUUID().toString();
        Long allowed=redis.execute(new DefaultRedisScript<>(
                "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); "
                +"redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now); "
                +"if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end; "
                +"redis.call('ZADD',KEYS[1],now+tonumber(ARGV[2])*1000,ARGV[3]); "
                +"redis.call('EXPIRE',KEYS[1],tonumber(ARGV[2])+1); return 1",Long.class),
                List.of("dashboard:integration:permit:"+key),String.valueOf(limit),String.valueOf(seconds),owner);
        return Long.valueOf(1).equals(allowed)?owner:null;
    }
    public void releasePermit(String key, String owner)
    {
        if(owner!=null) redis.opsForZSet().remove("dashboard:integration:permit:"+key,owner);
    }
    public long circuitRemaining(String key)
    {
        Long ttl=redis.getExpire("dashboard:integration:circuit:"+key,TimeUnit.SECONDS);
        return ttl==null?0:Math.max(ttl,0);
    }
    public void circuitSuccess(String key) { redis.delete(List.of("dashboard:integration:failures:"+key,"dashboard:integration:circuit:"+key)); }
    public void circuitFailure(String key,int threshold,int seconds)
    {
        Long opened=redis.execute(new DefaultRedisScript<>(
                "local n=redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],ARGV[2]); "
                +"if n>=tonumber(ARGV[1]) then redis.call('SET',KEYS[2],'1','EX',ARGV[2]); return 1 end; return 0",Long.class),
                List.of("dashboard:integration:failures:"+key,"dashboard:integration:circuit:"+key),String.valueOf(threshold),String.valueOf(seconds));
        if(Long.valueOf(1).equals(opened)) alert(key,"CIRCUIT_OPEN");
    }
    public void audit(String category,String resource,String requestId,String outcome,String actor,long bytes,long ms)
    {
        // 媒体旧入口只传不可逆引用摘要；在引用仍有效时补齐来源，后续运维按来源筛选。
        String resolved = "MEDIA".equals(category) ? mapper.mediaResource(resource) : null;
        String auditResource = resolved == null ? resource : resolved;
        recordAudit(category,auditResource,requestId,outcome,actor,bytes,ms);
    }
    public void auditMedia(String mediaRef,String outcome,String actor,long bytes)
    {
        // 当前入口用主键查询来源，不扫描或向日志传递媒体引用。
        String resource=mapper.mediaResourceByReference(mediaRef);
        if(resource==null) resource=DashboardIntegrationSecurity.sha256(mediaRef.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        recordAudit("MEDIA",resource,UUID.randomUUID().toString(),outcome,actor,bytes,0);
    }
    private void recordAudit(String category,String resource,String requestId,String outcome,String actor,long bytes,long ms)
    {
        newTransaction(() -> mapper.audit(Map.of("category",cut(category,32),"resource",cut(resource,128),"requestId",cut(requestId,64),
                "outcome",cut(outcome,48),"actor",cut(actor,64),"bytes",bytes,"durationMs",ms)));
    }
    public void alert(String resource,String code)
    {
        alert("DEAD_LETTER".equals(code) ? "INBOUND" : code != null && code.startsWith("WS_") ? "WEBSOCKET" : "OUTBOUND",resource,code);
    }
    public void alert(String category,String resource,String code)
    {
        String scope=category(category);
        if(scope.isBlank()) throw new ServiceException("告警必须指定接入类型");
        String key=DashboardIntegrationSecurity.sha256((scope+":"+resource+":"+code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        newTransaction(() -> mapper.categorizedAlert(key,scope,cut(resource,128),cut(code,48)));
    }
    public List<Map<String,Object>> audits(String category) { return audits(category,null); }
    public List<Map<String,Object>> audits(String category,String resourceCode)
    { return mapper.auditsByScope(category(category),resourceCode(resourceCode)); }
    public List<Map<String,Object>> alerts() { return alerts(null,null); }
    public List<Map<String,Object>> alerts(String category,String resourceCode)
    { return mapper.alertsByScope(category(category),resourceCode(resourceCode)); }
    public Map<String,Object> metrics() { return metrics(null,null); }
    public Map<String,Object> metrics(String category,String resourceCode)
    { return mapper.metricsByScope(category(category),resourceCode(resourceCode)); }
    public List<Map<String,Object>> resources() { return mapper.resources(); }
    public int acknowledge(Long id,String actor) { return mapper.acknowledge(id,actor); }
    public void signal(Long id) { mapper.signal(id); }
    public long version(Long id) { Long version=mapper.version(id); return version==null?0:version; }
    public void cleanup()
    {
        mapper.cleanupSnapshots(); mapper.cleanupLeases();
        int days=policyService != null ? policyService.auditRetentionDays()
                : environment == null ? 30 : environment.getProperty("dashboard.integration.audit-retention-days",Integer.class,30);
        mapper.cleanupAudits(Math.max(1,Math.min(days,3650)));
        if (policyService != null) policyService.cleanup();
    }
    static String category(String value)
    {
        String result=value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.isBlank() && !Set.of("OUTBOUND","INBOUND","WEBSOCKET","MEDIA","INBOUND_RETENTION").contains(result))
            throw new ServiceException("接入类型不支持");
        return result;
    }
    static String resourceCode(String value)
    {
        String result=value == null ? "" : value.trim();
        if (!result.isBlank() && !result.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new ServiceException("来源或接入方编码不合法");
        return result;
    }
    private <T> T newTransaction(Supplier<T> callback) {
        TransactionTemplate template=new TransactionTemplate(transactions);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status->callback.get());
    }
    private static String cut(String text,int max) { return text==null?"":text.substring(0,Math.min(text.length(),max)); }
}
