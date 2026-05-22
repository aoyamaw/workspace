package com.example.weatherapp.favorites;

public record FavoriteCityRequest(
		String providerLocationId,
		String displayName,
		String countryCode,
		Double latitude,
		Double longitude
) {
}
