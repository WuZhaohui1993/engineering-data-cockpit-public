package com.ruoyi.system.service;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.lang.reflect.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import com.sun.net.httpserver.HttpServer;

public class DashboardIntegrationReliabilityTest
{
    private DashboardIntegrationService service(Map<String,Object> properties) throws Exception
    {
        StandardEnvironment env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("test",properties));
        DashboardIntegrationService service=new DashboardIntegrationService();
        Field field=DashboardIntegrationService.class.getDeclaredField("environment");field.setAccessible(true);field.set(service,env);return service;
    }
    private Object invoke(Object target,String method,Class<?>[] parameters,Object... args) throws Exception
    {
        Method selected=target.getClass().getDeclaredMethod(method,parameters);selected.setAccessible(true);
        try {return selected.invoke(target,args);}catch(InvocationTargetException ex) {if(ex.getCause() instanceof Exception error) throw error;throw ex;}
    }
    private DashboardIntegrationService.MediaPayload media(byte[] input,String mime,Map<String,Object> properties) throws Exception
    {
        return (DashboardIntegrationService.MediaPayload)invoke(service(properties),"prepareMediaPayload",new Class<?>[]{String.class,byte[].class,Map.class},"test-reference",input,
                Map.of("mediaProfile",mime.startsWith("image/")?"IMAGE":"DOCUMENT","media",Map.of("allowedMimeTypes",List.of(mime),"stripMetadata",true)));
    }
    @Test public void localTimesUseDeclaredZoneAndRejectAmbiguousDst() throws Exception
    {
        DashboardIntegrationService service=service(Map.of());Class<?>[] types={String.class,ZoneId.class};
        Instant parsed=(Instant)invoke(service,"parseInstant",types,"2026-09-09 08:00:00",ZoneId.of("Asia/Shanghai"));
        assertEquals(Instant.parse("2026-09-09T00:00:00Z"),parsed);
        try {invoke(service,"parseInstant",types,"2026-11-01 01:30:00",ZoneId.of("America/New_York"));fail("ambiguous time accepted");}
        catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("歧义"));}
    }
    @Test public void gifIsDecodedAndRewritten() throws Exception
    {
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"gif",output);
        var result=media(output.toByteArray(),"image/gif",Map.of());assertEquals("image/gif",result.contentType());assertEquals(2,result.width());assertNotNull(ImageIO.read(new ByteArrayInputStream(result.bytes())));
    }
    @Test public void webpIsSafelyConvertedToPng() throws Exception
    {
        byte[] bytes=getClass().getResourceAsStream("/integration/tiny.webp").readAllBytes();
        var result=media(bytes,"image/webp",Map.of());assertEquals("image/png",result.contentType());assertEquals(2,result.width());assertNotNull(ImageIO.read(new ByteArrayInputStream(result.bytes())));
    }
    @Test public void decodedImageLimitsAreEnforced() throws Exception
    {
        BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
        try {invoke(service(Map.of()),"prepareMediaPayload",new Class<?>[]{String.class,byte[].class,Map.class},"test",output.toByteArray(),Map.of("media",Map.of("maxWidth",2)));fail("oversized image accepted");}
        catch(com.ruoyi.common.exception.ServiceException expected) {assertTrue(expected.getMessage().contains("尺寸"));}
    }
    @Test public void pdfRequiresScannerAndRemovesActionsAndAnnotations() throws Exception
    {
        byte[] bytes;
        try(PDDocument doc=new PDDocument()) {
            PDPage page=new PDPage();doc.addPage(page);page.setAnnotations(List.of(new PDAnnotationLink()));
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('test')"));
            ByteArrayOutputStream out=new ByteArrayOutputStream();doc.save(out);bytes=out.toByteArray();
        }
        try {media(bytes,"application/pdf",Map.of());fail("unscanned PDF accepted");}
        catch(com.ruoyi.common.exception.ServiceException expected) {assertTrue(expected.getMessage().contains("扫描"));}
        try(ScannerStub scanner=new ScannerStub("stream: OK")) {
            var result=media(bytes,"application/pdf",scanner.properties());assertFalse(result.inline());
            try(PDDocument safe=Loader.loadPDF(result.bytes())) {assertNull(safe.getDocumentCatalog().getOpenAction());assertTrue(safe.getPage(0).getAnnotations().isEmpty());assertEquals(1,safe.getNumberOfPages());}
        }
    }
    @Test public void scannerRejectsInfectedResponse() throws Exception
    {
        try(ScannerStub scanner=new ScannerStub("stream: isolated-test FOUND")) {
            try {media(new byte[]{1,2,3},"image/png",scanner.properties());fail("invalid media accepted");}
            catch(com.ruoyi.common.exception.ServiceException expected) {assertNotNull(expected.getMessage());}
            StandardEnvironment env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("test",scanner.properties()));
            try {DashboardMediaScanner.scan(new byte[]{1,2,3},env,true);fail("infected stream accepted");}
            catch(com.ruoyi.common.exception.ServiceException expected) {assertTrue(expected.getMessage().contains("扫描"));}
        }
    }
    @Test public void pinnedTransportDoesNotResolveAgainOrFollowRedirects() throws Exception
    {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getResponseHeaders().add("Location","http://169.254.169.254/");exchange.sendResponseHeaders(302,-1);exchange.close();});server.start();
        try {
            URI uri=URI.create("http://not-in-dns.invalid:"+server.getAddress().getPort()+"/");
            // The supplied address represents the already approved DNS answer. A second resolution cannot work.
            var approved=new DashboardIntegrationNetwork.Target(uri,List.of(InetAddress.getByName("127.0.0.1")));
            var response=DashboardPinnedHttp.send(HttpRequest.newBuilder(uri).GET().build(),approved);
            try(InputStream body=response.body()) {assertEquals(302,response.statusCode());}
            try {DashboardIntegrationNetwork.validate("https://169.254.169.254/","PUBLIC_HTTPS",List.of("169.254.169.254"),List.of(),List.of(443),false,false);fail("metadata address accepted");}
            catch(IllegalArgumentException expected) {assertNotNull(expected.getMessage());}
        } finally {server.stop(0);}
    }
    private static class ScannerStub implements AutoCloseable
    {
        final ServerSocket listener;final Thread thread;
        ScannerStub(String response) throws Exception {
            listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(()->{try(Socket socket=listener.accept()) {
                DataInputStream in=new DataInputStream(socket.getInputStream());socket.setSoTimeout(3000);
                while(in.readByte()!=0) { }
                int count;while((count=in.readInt())!=0) {if(count<0||count>65536)throw new IOException();in.readNBytes(count);}
                socket.getOutputStream().write((response+"\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }catch(IOException ignored){}});thread.setDaemon(true);thread.start();
        }
        Map<String,Object> properties(){return Map.of("dashboard.integration.scanner.host","127.0.0.1","dashboard.integration.scanner.port",listener.getLocalPort());}
        public void close() throws Exception {listener.close();thread.join(4000);}
    }
}
