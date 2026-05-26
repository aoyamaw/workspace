package com.example.weatherapp.recommendations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocalRecommendationResponse(
		UUID batchId,
		String providerLocationId,
		String displayName,
		List<LocalRecommendationItem> foods,
		List<LocalRecommendationItem> places,
		boolean hasMore,
		String message,
		Instant generatedAt
) {
}
