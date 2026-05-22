package com.example.weatherapp.weather;

import java.util.List;

public record WeatherResult(
		LocationCandidate location,
		WeatherCurrent current,
		List<WeatherForecastDay> forecast,
		String sourceName,
		String timezone,
		String retrievedAt
) {
}
