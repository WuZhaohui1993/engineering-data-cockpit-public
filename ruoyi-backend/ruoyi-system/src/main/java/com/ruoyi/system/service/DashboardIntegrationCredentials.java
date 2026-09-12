package com.ruoyi.system.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.core.env.Environment;

/** 外部凭证引用按请求读取；挂载目录可由 Vault Agent / Kubernetes Secret 原子更新。 */
public final class DashboardIntegrationCredentials
{
    private DashboardIntegrationCredentials() { }
    public static String resolve(String ref,String cipher,Environment env)
    {
        if(ref==null || ref.isBlank()) return DashboardIntegrationCrypto.decrypt(cipher,env);
        try
        {
            String value;
            if(ref.matches("ENV:[A-Z][A-Z0-9_]{2,127}"))
            {
                String name=ref.substring(4);
                java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList(
                        env.getProperty("dashboard.integration.credential-env-allowlist","").split(",")));
                if(!allowed.contains(name)) throw new IllegalArgumentException();
                value=env.getProperty(name,"");
            }
            else if(ref.matches("FILE:[A-Za-z0-9][A-Za-z0-9._-]{0,100}"))
            {
                String configured=env.getProperty("dashboard.integration.credentials-directory","");
                if(configured.isBlank()) throw new IllegalArgumentException();
                Path root=Path.of(configured).toRealPath();
                Path file=root.resolve(ref.substring(5));
                if(!file.toRealPath().startsWith(root) || !Files.isRegularFile(file) || Files.size(file)>16384)
                    throw new IllegalArgumentException();
                value=Files.readString(file).stripTrailing();
            }
            else throw new IllegalArgumentException();
            if(value==null || value.isBlank() || value.length()>16384) throw new IllegalArgumentException();
            return value;
        }
        catch(Exception ex) { throw new ServiceException("外部凭证引用不可用，请检查服务端环境或挂载目录"); }
    }
}
