package com.example.weatherapp.api;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherStatusController {

	private final String aiApiKey;
	private final String webPushPublicKey;

	public WeatherStatusController(
			@Value("${app.ai.api-key:}") String aiApiKey,
			@Value("${app.web-push.public-key:}") String webPushPublicKey
	) {
		this.aiApiKey = aiApiKey;
		this.webPushPublicKey = webPushPublicKey == null ? "" : webPushPublicKey;
	}

	@GetMapping("/api/status/capabilities")
	CapabilitiesResponse capabilities() {
		// 给前端展示当前后端具备哪些能力，例如天气、收藏、通知和助手。
		return new CapabilitiesResponse(
				List.of("weather-search", "favorites", "notifications", "assistant"),
				true,
				aiApiKey != null && !aiApiKey.isBlank(),
				Instant.now()
		);
	}

	@GetMapping("/api/status/web-push")
	WebPushConfigResponse webPushConfig() {
		// 前端根据这个接口判断能不能启用浏览器后台推送。
		return new WebPushConfigResponse(webPushPublicKey, !webPushPublicKey.isBlank(), Instant.now());
	}

	public record CapabilitiesResponse(
			List<String> capabilities,
			boolean weatherProviderConfigured,
			boolean aiProviderConfigured,
			Instant checkedAt
	) {
	}

	public record WebPushConfigResponse(String publicKey, boolean configured, Instant checkedAt) {
	}
}
