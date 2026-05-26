package com.example.weatherapp.recommendations;

import java.util.List;
import java.util.UUID;

public record LocalRecommendationRefreshRequest(
		String providerLocationId,
		String displayName,
		UUID currentBatchId,
		List<UUID> displayedItemIds
) {
}
