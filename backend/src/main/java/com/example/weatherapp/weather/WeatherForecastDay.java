package com.example.weatherapp.weather;

public record WeatherForecastDay(
		String date,
		Double highCelsius,
		Double lowCelsius,
		Integer precipitationProbabilityPercent,
		Integer weatherCode,
		String condition
) {
}
