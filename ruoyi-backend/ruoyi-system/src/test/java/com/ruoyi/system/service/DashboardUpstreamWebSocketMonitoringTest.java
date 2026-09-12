package com.ruoyi.system.service;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实监听器事件验证状态机与并发复用；不联网、不使用业务凭据。 */
public class DashboardUpstreamWebSocketMonitoringTest
{
    @Test public void concurrentReadersReuseOneConnectionAndOnlyLatestPayloadIsRetained() throws Exception
    {
        try(Fixture f=new Fixture(3)) {
            CompletableFuture<DashboardUpstreamWebSocketPool.Snapshot> first=f.read();
            CompletableFuture<DashboardUpstreamWebSocketPool.Snapshot> second=f.read();
            await(()->f.connector.attempts.size()==1);
            assertEquals(1L,f.count("connectingCount"));
            Attempt initial=f.connector.attempts.get(0);initial.open();
            assertEquals(1L,f.count("connectedCount"));assertEquals(0L,f.count("messageCount"));
            assertFalse(first.isDone());assertNull(f.row().get("lastMessageAt"));
            initial.text("{\"city\":\"西",false);initial.text("安\"}",true);
            assertEquals("{\"city\":\"西安\"}",first.get(1,TimeUnit.SECONDS).payload());
            assertEquals(first.get().payload(),second.get(1,TimeUnit.SECONDS).payload());
            initial.text("{\"temperature\":29}",true);
            assertEquals("{\"temperature\":29}",f.pool.read("weather-source","shared",f::spec,1).payload());
            assertEquals(2L,f.count("messageCount"));
            assertEquals((long)("{\"city\":\"西安\"}{\"temperature\":29}").getBytes(StandardCharsets.UTF_8).length,f.count("byteCount"));
            await(()->f.operations.events.size()==1);
            assertEquals(List.of("CONNECTED"),f.operations.events);
            String metadata=f.pool.monitoring("").toString()+f.operations.resources;
            for(String secret:List.of("secret-token","private-host","subscribe-secret","temperature","西安"))
                assertFalse(secret,metadata.contains(secret));
            assertEquals("CURRENT_INSTANCE",f.pool.monitoring("").get("scope"));
            assertEquals(0,((Number)f.pool.monitoring("other").get("connectionCount")).intValue());
        }
    }

    @Test public void disconnectAndReconnectReportAccurateStateAndIdleReleasesMessage() throws Exception
    {
        try(Fixture f=new Fixture(3)) {
            CompletableFuture<?> read=f.read();await(()->f.connector.attempts.size()==1);
            Attempt old=f.connector.attempts.get(0);old.open();old.text("{\"value\":1}",true);read.get(1,TimeUnit.SECONDS);
            old.listener.onError(old.socket,new IllegalStateException("secret-token"));
            assertEquals(1L,f.count("reconnectingCount"));assertEquals(0L,f.count("connectedCount"));
            assertFalse(f.pool.read("weather-source","shared",f::spec,1).connected());
            f.clock.addAndGet(1001);f.pool.maintain();
            assertEquals(2,f.connector.attempts.size());assertEquals(1L,f.row().get("reconnectCount"));
            Attempt reopened=f.connector.attempts.get(1);reopened.open();reopened.text("{\"value\":2}",true);
            old.listener.onError(old.socket,new IllegalStateException("ignored late callback"));
            assertEquals("CONNECTED",f.row().get("status"));assertEquals("",f.row().get("lastErrorCode"));
            f.clock.addAndGet(120001);f.pool.maintain();
            assertEquals(0,((Number)f.pool.monitoring("").get("connectionCount")).intValue());
            await(()->f.operations.events.size()==4);
            assertEquals(List.of("CONNECTED","WS_DISCONNECTED","RECONNECTED","IDLE_CLOSED"),f.operations.events);
            assertEquals(List.of("WS_DISCONNECTED"),f.operations.alerts);
            assertTrue(reopened.socket.aborted);
        }
    }

    @Test public void failedInitialConnectionHasNoFalseSuccessAndBoundedRetryEvents() throws Exception
    {
        try(Fixture f=new Fixture(2)) {
            CompletableFuture<?> read=f.read();await(()->f.connector.attempts.size()==1);
            f.connector.attempts.get(0).fail();
            assertEquals("RECONNECTING",f.row().get("status"));assertNull(f.row().get("connectedAt"));
            f.clock.addAndGet(1001);f.pool.maintain();f.connector.attempts.get(1).fail();
            f.clock.addAndGet(2001);f.pool.maintain();f.connector.attempts.get(2).fail();
            assertEquals("ERROR",f.row().get("status"));assertEquals("WS_RETRY_EXHAUSTED",f.row().get("lastErrorCode"));
            try { read.get(1,TimeUnit.SECONDS);fail("failed connection yielded a message"); } catch(ExecutionException expected) { }
            f.clock.addAndGet(5000);f.pool.maintain();assertEquals(3,f.connector.attempts.size());
            await(()->f.operations.events.size()==2);
            assertEquals(List.of("WS_CONNECT_FAILED","WS_RETRY_EXHAUSTED"),f.operations.events);
            assertEquals(0L,f.count("messageCount"));
        }
    }

    @Test public void invalidOrOversizedMessageIsNotCountedOrExposedInMetadata() throws Exception
    {
        for(String value:List.of("invalid-secret-payload", "\""+ "中".repeat(180000)+"\"")) {
            try(Fixture f=new Fixture(3)) {
                CompletableFuture<?> read=f.read();await(()->f.connector.attempts.size()==1);
                Attempt initial=f.connector.attempts.get(0);initial.open();initial.text(value,true);
                try { read.get(1,TimeUnit.SECONDS);fail("invalid message accepted"); } catch(ExecutionException expected) { }
                assertEquals("ERROR",f.row().get("status"));assertEquals(0L,f.count("messageCount"));
                assertEquals(0L,f.count("byteCount"));assertTrue(initial.socket.aborted);
                assertFalse(f.pool.monitoring("").toString().contains("invalid-secret-payload"));
            }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final AtomicLong clock=new AtomicLong(1_000_000);
        final FakeConnector connector=new FakeConnector();
        final Events operations=new Events();
        final DashboardUpstreamWebSocketPool pool=new DashboardUpstreamWebSocketPool(clock::get,connector,false);
        final int retries;
        Fixture(int retries) throws Exception { this.retries=retries;Field field=DashboardUpstreamWebSocketPool.class.getDeclaredField("operations");field.setAccessible(true);field.set(pool,operations); }
        DashboardUpstreamWebSocketPool.Spec spec() { return new DashboardUpstreamWebSocketPool.Spec(URI.create("wss://private-host/feed"),"secret-token","subscribe-secret",30,retries); }
        CompletableFuture<DashboardUpstreamWebSocketPool.Snapshot> read() {
            return CompletableFuture.supplyAsync(()-> { try{return pool.read("weather-source","shared",this::spec,2);}catch(Exception ex){throw new CompletionException(ex);} });
        }
        long count(String key) { return ((Number)pool.monitoring("").get(key)).longValue(); }
        @SuppressWarnings("unchecked") Map<String,Object> row() { return ((List<Map<String,Object>>)pool.monitoring("").get("connections")).get(0); }
        public void close() { pool.shutdown(); }
    }
    private static final class Events extends DashboardIntegrationOperations
    {
        final List<String> events=new CopyOnWriteArrayList<>(),alerts=new CopyOnWriteArrayList<>(),resources=new CopyOnWriteArrayList<>();
        @Override public void audit(String category,String resource,String request,String outcome,String actor,long bytes,long duration) { assertEquals("WEBSOCKET",category);resources.add(resource);events.add(outcome); }
        @Override public void alert(String category,String resource,String code) { assertEquals("WEBSOCKET",category);alerts.add(code); }
    }
    private static final class FakeConnector implements DashboardUpstreamWebSocketPool.Connector
    {
        final List<Attempt> attempts=new CopyOnWriteArrayList<>();
        public CompletionStage<WebSocket> connect(DashboardUpstreamWebSocketPool.Spec spec,WebSocket.Listener listener) { Attempt attempt=new Attempt(listener);attempts.add(attempt);return attempt.future; }
    }
    private static final class Attempt
    {
        final WebSocket.Listener listener;final Socket socket=new Socket();final CompletableFuture<WebSocket> future=new CompletableFuture<>();
        Attempt(WebSocket.Listener listener) { this.listener=listener; }
        void open() { listener.onOpen(socket);future.complete(socket); }
        void text(String text,boolean last) { listener.onText(socket,text,last); }
        void fail() { future.completeExceptionally(new IllegalStateException("credential and host must never leak")); }
    }
    private static final class Socket implements WebSocket
    {
        boolean aborted;
        public CompletableFuture<WebSocket> sendText(CharSequence data,boolean last) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data,boolean last) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendClose(int code,String reason) { return CompletableFuture.completedFuture(this); }
        public void request(long count) { }
        public String getSubprotocol() { return ""; }
        public boolean isOutputClosed() { return aborted; }
        public boolean isInputClosed() { return aborted; }
        public void abort() { aborted=true; }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue("condition did not become true",condition.getAsBoolean());
    }
}
