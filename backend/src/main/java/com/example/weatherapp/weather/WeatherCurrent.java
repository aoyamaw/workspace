package com.example.weatherapp.weather;

public record WeatherCurrent(
		String time,
		Double temperatureCelsius,
		Double humidityPercent,
		Double windSpeedKmh,
		Double windDirectionDegrees,
		Double precipitationMm,
		Integer weatherCode,
		String condition
) {
}
