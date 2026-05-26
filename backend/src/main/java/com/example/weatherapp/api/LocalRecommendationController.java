package com.example.weatherapp.api;

import com.example.weatherapp.recommendations.LocalRecommendationRefreshRequest;
import com.example.weatherapp.recommendations.LocalRecommendationResponse;
import com.example.weatherapp.recommendations.LocalRecommendationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class LocalRecommendationController {

	private final LocalRecommendationService localRecommendationService;

	public LocalRecommendationController(LocalRecommendationService localRecommendationService) {
		this.localRecommendationService = localRecommendationService;
	}

	@GetMapping("/api/recommendations/local")
	Mono<LocalRecommendationResponse> list(
			@RequestHeader(value = "X-User-Id", required = false) UUID userId,
			@RequestParam String providerLocationId,
			@RequestParam String displayName
	) {
		return localRecommendationService.initial(userId, providerLocationId, displayName);
	}

	@PostMapping("/api/recommendations/local/refresh")
	Mono<LocalRecommendationResponse> refresh(
			@RequestHeader(value = "X-User-Id", required = false) UUID userId,
			@RequestBody LocalRecommendationRefreshRequest request
	) {
		return localRecommendationService.refresh(userId, request);
	}
}
