package com.example.weatherapp.api;

import com.example.weatherapp.identity.IdentityService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class AnonymousSessionController {

	private final IdentityService identityService;

	public AnonymousSessionController(IdentityService identityService) {
		this.identityService = identityService;
	}

	@PostMapping("/api/sessions/anonymous")
	Mono<AnonymousSessionResponse> createAnonymousSession() {
		// 未登录用户也会得到一个匿名身份，收藏、通知和助手记录都可以绑定到这个身份。
		var sessionId = UUID.randomUUID();
		return identityService.createAnonymousUser(sessionId)
				.map(userId -> new AnonymousSessionResponse(userId, sessionId, "anonymous", Instant.now()));
	}

	public record AnonymousSessionResponse(UUID userId, UUID sessionId, String identityType, Instant createdAt) {
	}
}
