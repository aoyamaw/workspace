package com.example.weatherapp.favorites;

import java.time.Instant;
import java.util.UUID;

public record FavoriteCityResponse(
		UUID id,
		String providerLocationId,
		String displayName,
		String countryCode,
		Double latitude,
		Double longitude,
		Double latestTemperatureCelsius,
		String latestCondition,
		boolean weatherAvailable,
		String weatherStatus,
		Instant weatherUpdatedAt,
		Instant createdAt
) {
}
