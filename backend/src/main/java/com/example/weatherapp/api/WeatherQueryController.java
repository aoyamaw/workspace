package com.example.weatherapp.api;

import com.example.weatherapp.weather.WeatherSearchResponse;
import com.example.weatherapp.weather.WeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class WeatherQueryController {

	private final WeatherService weatherService;

	public WeatherQueryController(WeatherService weatherService) {
		this.weatherService = weatherService;
	}

	@GetMapping("/api/weather/search")
	Mono<WeatherSearchResponse> search(
			@RequestParam("city") String city,
			@RequestParam(value = "locationId", required = false) String locationId
	) {
		// 前端搜索城市时进入这里，再由 WeatherService 去匹配城市并请求天气数据。
		return weatherService.search(city, locationId);
	}

	@GetMapping("/api/weather/nearby")
	Mono<WeatherSearchResponse> nearby(
			@RequestParam("latitude") double latitude,
			@RequestParam("longitude") double longitude
	) {
		return weatherService.nearby(latitude, longitude);
	}
}
