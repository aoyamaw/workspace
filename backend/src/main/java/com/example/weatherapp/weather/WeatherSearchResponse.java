package com.example.weatherapp.weather;

import java.util.List;

public record WeatherSearchResponse(
		String query,
		String status,
		String message,
		List<LocationCandidate> candidates,
		WeatherResult weather
) {
	public static WeatherSearchResponse unsupported(String query) {
		return new WeatherSearchResponse(query, "unsupported", "未找到可支持的城市，请尝试更明确的城市名称。", List.of(), null);
	}

	public static WeatherSearchResponse ambiguous(String query, List<LocationCandidate> candidates) {
		return new WeatherSearchResponse(query, "ambiguous", "请选择一个匹配的城市。", candidates, null);
	}

	public static WeatherSearchResponse suggestions(String query, List<LocationCandidate> candidates) {
		return ambiguous(query, candidates);
	}

	public static WeatherSearchResponse resolved(String query, WeatherResult weather) {
		return resolved(query, weather, List.of());
	}

	public static WeatherSearchResponse resolved(String query, WeatherResult weather, List<LocationCandidate> candidates) {
		return new WeatherSearchResponse(query, "resolved", "resolved", candidates, weather);
	}
}
