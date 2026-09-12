package com.ruoyi.system.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLSession;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;

/** 连接只使用已校验的 DNS 地址；TLS 仍验证原主机名，不继承系统代理或自动重定向。 */
public final class DashboardPinnedHttp
{
    private DashboardPinnedHttp() { }
    public static HttpResponse<InputStream> send(HttpRequest request,DashboardIntegrationNetwork.Target approved)
            throws IOException,InterruptedException
    {
        if(!request.uri().equals(approved.uri())) throw new IllegalArgumentException("目标与批准地址不一致");
        String host=approved.uri().getHost();
        DnsResolver resolver=new DnsResolver() {
            public InetAddress[] resolve(String requested) throws UnknownHostException {
                if(!host.equalsIgnoreCase(requested)) throw new UnknownHostException("主机不在本次批准范围");
                return approved.addresses().toArray(InetAddress[]::new);
            }
            public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                if(!host.equalsIgnoreCase(requested)) throw new UnknownHostException("主机不在本次批准范围");return host;
            }
        };
        var connections=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        CloseableHttpClient client=HttpClients.custom().setConnectionManager(connections)
                .disableRedirectHandling().disableAutomaticRetries().disableContentCompression().disableCookieManagement().build();
        try {
            HttpUriRequestBase outbound=new HttpUriRequestBase(request.method(),request.uri());
            long timeout=request.timeout().orElse(java.time.Duration.ofSeconds(10)).toMillis();
            outbound.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(timeout))
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeout)).setResponseTimeout(Timeout.ofMilliseconds(timeout)).build());
            request.headers().map().forEach((name,values)->values.forEach(value->outbound.addHeader(name,value)));
            if(request.bodyPublisher().isPresent()) {
                byte[] bytes=body(request.bodyPublisher().get());
                if(bytes.length>0) outbound.setEntity(new ByteArrayEntity(bytes,ContentType.parse(request.headers().firstValue("Content-Type").orElse("application/octet-stream"))));
            }
            CloseableHttpResponse response=client.execute(outbound);
            Map<String,List<String>> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for(var header:response.getHeaders()) headers.computeIfAbsent(header.getName(),ignored->new ArrayList<>()).add(header.getValue());
            InputStream stream=response.getEntity()==null?InputStream.nullInputStream():response.getEntity().getContent();
            InputStream body=new FilterInputStream(stream) { @Override public void close() throws IOException { try { super.close(); } finally { try {response.close();} finally {client.close();} } } };
            int status=response.getCode();
            return new HttpResponse<>() {
                public int statusCode(){return status;} public HttpRequest request(){return request;}
                public Optional<HttpResponse<InputStream>> previousResponse(){return Optional.empty();}
                public HttpHeaders headers(){return HttpHeaders.of(headers,(k,v)->true);}
                public InputStream body(){return body;}
                public Optional<SSLSession> sslSession(){return Optional.empty();}
                public URI uri(){return request.uri();} public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
            };
        } catch(IOException|InterruptedException|RuntimeException ex) {client.close();throw ex;}
    }
    private static byte[] body(HttpRequest.BodyPublisher publisher) throws IOException,InterruptedException
    {
        CompletableFuture<byte[]> result=new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
            public void onSubscribe(Flow.Subscription sub){subscription=sub;sub.request(1);}
            public void onNext(ByteBuffer buffer){
                if(buffer.remaining()>16*1024*1024-bytes.size()){subscription.cancel();result.completeExceptionally(new IOException("请求体超限"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);subscription.request(1);
            }
            public void onError(Throwable error){result.completeExceptionally(error);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        });
        try{return result.get(5,java.util.concurrent.TimeUnit.SECONDS);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException ex){throw new IOException("请求体无法编码",ex);}
    }
}
