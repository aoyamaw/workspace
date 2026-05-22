package com.example.weatherapp.notifications;

import java.time.Instant;
import java.util.UUID;

public record NotificationEventResponse(
		UUID id,
		UUID subscriptionId,
		UUID favoriteCityId,
		String favoriteDisplayName,
		String eventType,
		String title,
		String body,
		String severity,
		String deliveryStatus,
		Instant createdAt
) {
}
