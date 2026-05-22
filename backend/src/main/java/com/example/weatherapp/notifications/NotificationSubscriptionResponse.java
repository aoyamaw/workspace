package com.example.weatherapp.notifications;

import java.time.Instant;
import java.util.UUID;

public record NotificationSubscriptionResponse(
		UUID id,
		UUID favoriteCityId,
		String favoriteDisplayName,
		String channel,
		String permissionStatus,
		boolean dailySummaryEnabled,
		boolean severeWeatherEnabled,
		int cooldownMinutes,
		boolean active,
		Instant createdAt,
		Instant updatedAt
) {
}
