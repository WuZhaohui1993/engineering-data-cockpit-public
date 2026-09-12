package com.ruoyi.system.service;

import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.core.env.Environment;

/** 受部署环境控制的 ClamAV INSTREAM 适配器，扫描失败或未配置时拒绝需要扫描的文件。 */
public final class DashboardMediaScanner
{
    private DashboardMediaScanner() { }
    public static void scan(byte[] bytes,Environment env,boolean required)
    {
        String host=env.getProperty("dashboard.integration.scanner.host","");
        if(host.isBlank()) { if(required) throw new ServiceException("媒体要求病毒扫描，但服务端未配置扫描器"); return; }
        try(Socket socket=new Socket())
        {
            int timeout=Math.max(1,Math.min(env.getProperty("dashboard.integration.scanner.timeout-seconds",Integer.class,10),60));
            socket.connect(new InetSocketAddress(host,env.getProperty("dashboard.integration.scanner.port",Integer.class,3310)),timeout*1000);
            socket.setSoTimeout(timeout*1000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for(int i=0;i<bytes.length;i+=65536) { int size=Math.min(65536,bytes.length-i);out.writeInt(size);out.write(bytes,i,size); }
            out.writeInt(0);out.flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            int value;while(response.size()<1024&&(value=socket.getInputStream().read())>0) response.write(value);
            if(!"stream: OK".equals(response.toString(StandardCharsets.US_ASCII).trim())) throw new ServiceException("媒体扫描未通过");
        }
        catch(ServiceException ex) { throw ex; }
        catch(Exception ex) { throw new ServiceException("媒体扫描服务不可用"); }
    }
}
