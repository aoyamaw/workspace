package com.example.weatherapp.recommendations;

record RecommendationSeed(
		RecommendationCategory category,
		String name,
		String description,
		String imageUrl,
		String imageAlt,
		String sourceTitle,
		String sourceUrl
) {
}
