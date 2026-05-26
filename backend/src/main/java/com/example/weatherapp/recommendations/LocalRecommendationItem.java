package com.example.weatherapp.recommendations;

import java.util.UUID;

public record LocalRecommendationItem(
		UUID id,
		String category,
		int rank,
		String name,
		String description,
		String imageUrl,
		String imageAlt,
		String sourceTitle,
		String sourceUrl,
		UUID batchId
) {
}
