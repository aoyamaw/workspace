package com.example.weatherapp.notifications;

import java.util.UUID;

public record NotificationSubscriptionRequest(
		UUID favoriteCityId,
		String channel,
		String permissionStatus,
		String pushEndpoint,
		String pushP256dh,
		String pushAuth,
		Boolean dailySummaryEnabled,
		Boolean severeWeatherEnabled,
		Integer cooldownMinutes
) {
}
