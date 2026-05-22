package com.example.weatherapp.notifications;

import java.time.Instant;
import java.util.List;

public record NotificationEvaluationResponse(
		int generated,
		int suppressed,
		List<NotificationEventResponse> events,
		Instant evaluatedAt
) {
}
