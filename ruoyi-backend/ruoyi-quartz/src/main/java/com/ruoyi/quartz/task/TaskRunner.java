package com.ruoyi.quartz.task;

import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;

/**
 * 定时任务调度测试。
 */
@Component("taskRunner")
public class TaskRunner
{
    public void multipleParams(String text, Boolean enabled, Long longValue, Double decimalValue, Integer integerValue)
    {
        System.out.println(StringUtils.format("执行多参方法：字符串类型{}，布尔类型{}，长整型{}，浮点型{}，整型{}",
                text, enabled, longValue, decimalValue, integerValue));
    }

    public void params(String text)
    {
        System.out.println("执行有参方法：" + text);
    }

    public void noParams()
    {
        System.out.println("执行无参方法");
    }
}
