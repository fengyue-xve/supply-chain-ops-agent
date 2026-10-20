package com.demo.supplyagent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 ToolAccessInterceptor 挂到工具执行入口(/api/agent/tools/*,单段路径 = 单个工具名,
 * 不覆盖 /api/agent/tools 本体)。执行端点全部是 POST,拦截器内部再按方法过滤。
 */
@Configuration
public class WebMvcAclConfig implements WebMvcConfigurer {

    private final ToolAccessInterceptor toolAccessInterceptor;

    public WebMvcAclConfig(ToolAccessInterceptor toolAccessInterceptor) {
        this.toolAccessInterceptor = toolAccessInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(toolAccessInterceptor).addPathPatterns("/api/agent/tools/*");
    }
}
