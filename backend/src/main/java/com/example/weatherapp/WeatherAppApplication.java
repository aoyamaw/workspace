package com.example.weatherapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WeatherAppApplication {

	public static void main(String[] args) {
		// 后端启动入口：运行后会启动 Spring Boot 服务，默认监听 application.properties 里的 8080 端口。
		SpringApplication.run(WeatherAppApplication.class, args);
	}
}
