package com.example.weatherapp.weather;

public record LocationCandidate(
		String id,
		String name,
		String displayName,
		String country,
		String countryCode,
		String admin1,
		String timezone,
		double latitude,
		double longitude
) {
}
