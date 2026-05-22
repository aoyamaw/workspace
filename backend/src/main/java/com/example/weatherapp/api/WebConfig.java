package com.example.weatherapp.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class WebConfig implements WebFluxConfigurer {

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		// 允许本地 Vite 前端访问 /api/** 接口，否则浏览器会因为跨域限制拦截请求。
		registry.addMapping("/api/**")
				.allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*");
	}
}
