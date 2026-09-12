package com.ruoyi.system.service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 上游连接和最新消息只驻留当前实例内存。运维只记录元数据及有界生命周期事件。 */
@Service
public class DashboardUpstreamWebSocketPool
{
    public record Spec(URI uri,String authorization,String subscription,int heartbeatSeconds,int reconnectLimit) { }
    public record Snapshot(String payload,Instant receivedAt,boolean connected) { }
    @FunctionalInterface interface Connector { CompletionStage<WebSocket> connect(Spec spec,WebSocket.Listener listener); }
    private static final int MAX_MESSAGE_BYTES=512*1024;
    private final Map<String,Channel> channels=new ConcurrentHashMap<>();
    private final ObjectMapper json=new ObjectMapper();
    private final LongSupplier clock;
    private final Connector connector;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"dashboard-upstream-ws"));
    private final ThreadPoolExecutor eventExecutor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),r->daemon(r,"dashboard-upstream-ws-events"),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong droppedEvents=new AtomicLong(),failedEvents=new AtomicLong();
    @Autowired(required=false) private DashboardIntegrationOperations operations;
    public DashboardUpstreamWebSocketPool() { this(System::currentTimeMillis,defaultConnector(),true); }
    DashboardUpstreamWebSocketPool(LongSupplier clock,Connector connector,boolean scheduled)
    {
        this.clock=clock;this.connector=connector;
        if(scheduled) scheduler.scheduleWithFixedDelay(this::maintain,1,1,TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable task,String name) { Thread thread=new Thread(task,name);thread.setDaemon(true);return thread; }
    private static Connector defaultConnector()
    {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (spec,listener)-> {
            WebSocket.Builder builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if(!spec.authorization().isBlank()) builder.header("Authorization",spec.authorization());
            return builder.buildAsync(spec.uri(),listener);
        };
    }
    /** 兼容旧调用；新入口必须传独立来源编码，不能从 URI 或凭证推断身份。 */
    public Snapshot read(String key,Supplier<Spec> factory,int timeoutSeconds) throws Exception
    { return read("legacy",key,factory,timeoutSeconds); }
    public Snapshot read(String sourceCode,String key,Supplier<Spec> factory,int timeoutSeconds) throws Exception
    {
        String source=DashboardIntegrationOperations.resourceCode(sourceCode);
        if(source.isBlank()) throw new ServiceException("WebSocket 来源编码不能为空");
        String poolKey=source+":"+key;
        Channel channel=channels.get(poolKey);
        if(channel==null) synchronized(channels) {
            channel=channels.get(poolKey);
            if(channel==null) {
                if(channels.size()>=100) throw new ServiceException("上游实时连接数达到上限");
                channel=new Channel(source,factory);channels.put(poolKey,channel);channel.connect();
            }
        }
        channel.lastRead=clock.getAsLong();
        if(channel.invalid) throw new ServiceException("上游实时消息无效，请检查格式和大小");
        if(channel.latest==null) channel.firstMessage.get(Math.max(1,Math.min(timeoutSeconds,30)),TimeUnit.SECONDS);
        Snapshot result=channel.latest;
        if(result==null) throw new ServiceException("上游实时通道尚无有效消息");
        return new Snapshot(result.payload(),result.receivedAt(),channel.socket!=null);
    }
    void maintain()
    {
        long now=clock.getAsLong();
        channels.forEach((key,channel)-> {
            if(now-channel.lastRead>120000) { if(channels.remove(key,channel)) channel.close("IDLE_CLOSED");return; }
            channel.tick(now);
        });
    }
    /** 只返回本实例当前连接；计数从各连接建立开始，空闲回收后不保留历史。 */
    public Map<String,Object> monitoring(String resourceCode)
    {
        String source=DashboardIntegrationOperations.resourceCode(resourceCode);
        List<Map<String,Object>> connections=new ArrayList<>();
        channels.values().forEach(channel->{ if(source.isBlank()||source.equals(channel.source)) connections.add(channel.metadata()); });
        connections.sort(Comparator.comparing(row->String.valueOf(row.get("resourceCode"))+row.get("connectionId")));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope","CURRENT_INSTANCE");result.put("connectionCount",connections.size());
        for(String state:List.of("CONNECTED","CONNECTING","RECONNECTING","ERROR"))
            result.put(state.toLowerCase(Locale.ROOT)+"Count",connections.stream().filter(row->state.equals(row.get("status"))).count());
        result.put("messageCount",connections.stream().mapToLong(row->((Number)row.get("messageCount")).longValue()).sum());
        result.put("byteCount",connections.stream().mapToLong(row->((Number)row.get("byteCount")).longValue()).sum());
        result.put("eventDropCount",droppedEvents.get());result.put("eventPersistenceErrorCount",failedEvents.get());
        result.put("connections",connections);
        return result;
    }
    private void event(String source,String connectionId,String outcome,String error)
    {
        if(operations==null) return;
        try {
            eventExecutor.execute(()-> {
                try {
                    operations.audit("WEBSOCKET",source,connectionId,outcome,"system",0,0);
                    if(!error.isBlank()) operations.alert("WEBSOCKET",source,error);
                } catch(RuntimeException ex) { failedEvents.incrementAndGet(); }
            });
        } catch(RejectedExecutionException ex) { droppedEvents.incrementAndGet(); }
    }
    @PreDestroy public void shutdown()
    {
        scheduler.shutdownNow();channels.values().forEach(channel->channel.close("CLOSED"));channels.clear();
        eventExecutor.shutdown();
        try { if(!eventExecutor.awaitTermination(1,TimeUnit.SECONDS)) eventExecutor.shutdownNow(); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt();eventExecutor.shutdownNow(); }
    }
    private final class Channel
    {
        final String source,connectionId=UUID.randomUUID().toString();
        final Supplier<Spec> factory;
        // 等待器不持有正文；最新消息覆盖旧值，不额外保存首条样本。
        final CompletableFuture<Void> firstMessage=new CompletableFuture<>();
        volatile Snapshot latest;
        volatile WebSocket socket;
        volatile long lastRead=clock.getAsLong(),lastPong=lastRead,lastPing=0,nextConnect=0;
        long connectedAt=0,messageCount=0,byteCount=0,reconnectCount=0;
        int attempts=0,heartbeat=30,limit=5,failures=0,generation=0;
        boolean connecting=false,closed=false,everConnected=false;
        volatile boolean invalid=false;
        String state="CONNECTING",lastError="";
        Channel(String source,Supplier<Spec> factory) { this.source=source;this.factory=factory; }
        synchronized Map<String,Object> metadata()
        {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("connectionId",connectionId);row.put("resourceCode",source);row.put("status",state);
            row.put("connectedAt",connectedAt==0?null:Instant.ofEpochMilli(connectedAt).toString());
            row.put("lastMessageAt",latest==null?null:latest.receivedAt().toString());
            row.put("lastReadAt",Instant.ofEpochMilli(lastRead).toString());
            row.put("messageCount",messageCount);row.put("byteCount",byteCount);row.put("reconnectCount",reconnectCount);
            row.put("lastErrorCode",lastError);
            return row;
        }
        synchronized void connect()
        {
            if(connecting||closed||invalid) return;
            connecting=true;state=attempts==0?"CONNECTING":"RECONNECTING";
            if(attempts++>0) reconnectCount++;
            int current=++generation;
            try {
                Spec spec=factory.get();heartbeat=Math.max(1,Math.min(spec.heartbeatSeconds(),300));limit=Math.max(0,Math.min(spec.reconnectLimit(),20));
                connector.connect(spec,new WebSocket.Listener() {
                    final StringBuilder message=new StringBuilder();
                    @Override public void onOpen(WebSocket ws)
                    {
                        synchronized(Channel.this) {
                            if(current!=generation||closed) { ws.abort();return; }
                            socket=ws;connecting=false;state="CONNECTED";lastError="";
                            connectedAt=clock.getAsLong();lastPong=connectedAt;lastPing=0;
                            event(source,connectionId,everConnected?"RECONNECTED":"CONNECTED","");everConnected=true;
                        }
                        ws.request(1);
                        if(!spec.subscription().isBlank()) ws.sendText(spec.subscription(),true).whenComplete((r,e)->{if(e!=null) failed(current,"WS_SUBSCRIPTION_FAILED",false);});
                    }
                    @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last)
                    {
                        synchronized(Channel.this) {
                            if(current!=generation||closed) return CompletableFuture.completedFuture(null);
                            if(message.length()+text.length()>MAX_MESSAGE_BYTES) { failed(current,"WS_MESSAGE_TOO_LARGE",true);return CompletableFuture.completedFuture(null); }
                            message.append(text);
                            if(last) {
                                String payload=message.toString();long size=payload.getBytes(StandardCharsets.UTF_8).length;
                                if(size>MAX_MESSAGE_BYTES) { failed(current,"WS_MESSAGE_TOO_LARGE",true);return CompletableFuture.completedFuture(null); }
                                try { if(json.readTree(payload)==null) throw new IllegalArgumentException(); }
                                catch(Exception ex) { failed(current,"WS_INVALID_JSON",true);return CompletableFuture.completedFuture(null); }
                                latest=new Snapshot(payload,Instant.ofEpochMilli(clock.getAsLong()),true);
                                messageCount++;byteCount+=size;firstMessage.complete(null);message.setLength(0);failures=0;lastPong=clock.getAsLong();
                            }
                        }
                        ws.request(1);return CompletableFuture.completedFuture(null);
                    }
                    @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer data)
                    { synchronized(Channel.this) { if(current==generation) lastPong=clock.getAsLong(); }ws.request(1);return CompletableFuture.completedFuture(null); }
                    @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer data) { ws.request(1);return ws.sendPong(data); }
                    @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last)
                    { failed(current,"WS_UNSUPPORTED_MESSAGE",true);return CompletableFuture.completedFuture(null); }
                    @Override public void onError(WebSocket ws,Throwable error) { failed(current,"WS_DISCONNECTED",false); }
                    @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason)
                    { failed(current,"WS_DISCONNECTED",false);return CompletableFuture.completedFuture(null); }
                }).whenComplete((ws,error)-> {if(error!=null) failed(current,"WS_CONNECT_FAILED",false);});
            } catch(RuntimeException ex) { failed(current,"WS_CONNECT_FAILED",false); }
        }
        synchronized void failed(int expected,String error,boolean terminal)
        {
            if(expected!=generation||closed||invalid) return;
            generation++;connecting=false;WebSocket old=socket;socket=null;if(old!=null) old.abort();failures++;
            invalid=terminal;boolean exhausted=failures>limit;
            state=terminal||exhausted?"ERROR":"RECONNECTING";lastError=exhausted&&!terminal?"WS_RETRY_EXHAUSTED":error;
            nextConnect=clock.getAsLong()+Math.min(30000,500L*(1L<<Math.min(failures,6)));
            // 只记录首个异常和最终失败，同一轮重试的中间尝试不逐次写审计。
            if(failures==1||terminal||exhausted) event(source,connectionId,lastError,lastError);
            if(terminal||exhausted) firstMessage.completeExceptionally(new ServiceException(terminal?"上游实时消息无效":"上游实时连接重试次数已达上限"));
        }
        synchronized void tick(long now)
        {
            if(invalid||closed) return;
            WebSocket ws=socket;
            if(ws==null) { if(!connecting&&failures<=limit&&now>=nextConnect) connect();return; }
            if(now-lastPong>heartbeat*2000L) { failed(generation,"WS_HEARTBEAT_TIMEOUT",false);return; }
            if(now-lastPing>=heartbeat*1000L) { lastPing=now;int expected=generation;ws.sendPing(ByteBuffer.wrap(new byte[]{1})).whenComplete((r,e)->{if(e!=null) failed(expected,"WS_HEARTBEAT_TIMEOUT",false);}); }
        }
        synchronized void close(String outcome)
        {
            if(closed) return;
            closed=true;generation++;state="CLOSED";if(socket!=null) socket.abort();socket=null;firstMessage.cancel(true);latest=null;
            event(source,connectionId,outcome,"");
        }
    }
}
