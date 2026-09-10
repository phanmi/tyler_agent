package org.tyler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置。
 *
 * <p>Tyler 前端有两种运行形态：
 * <ul>
 *   <li>开发态：Vite dev server（http://localhost:5173）通过 /api 代理访问后端，
 *       浏览器视角同源，天然没有 CORS 问题。</li>
 *   <li>生产态（Electron）：main 进程 loadFile 加载 dist/index.html，页面 origin 是
 *       file://，前端直接 fetch http://127.0.0.1:8080/api/**，属于跨源请求，
 *       必须由后端放行。</li>
 * </ul>
 *
 * <p>本配置只对 /api/** 放开 CORS，且不携带凭据（allowCredentials=false）。
 * 后端面向本机内部服务，后续 Phase 10 会进一步收紧监听地址与来源白名单。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}