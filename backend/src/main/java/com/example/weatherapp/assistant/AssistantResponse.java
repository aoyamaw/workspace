package com.example.weatherapp.assistant;

import java.time.Instant;

public record AssistantResponse(
		String answer,
		boolean usedWeatherContext,
		boolean persisted,
		Instant createdAt
) {
}
