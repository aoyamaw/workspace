package com.example.weatherapp.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class WeatherService {

	// 这个服务负责天气查询的核心流程：城市匹配 -> 调第三方天气接口 -> 组装返回值 -> 写入天气缓存。
	private final WebClient forecastClient;
	private final WebClient qweatherGeoClient;
	private final WebClient qweatherWeatherClient;
	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;
	private final CitySuggestionIndex cityIndex;
	private final String provider;
	private final String qweatherApiKey;

	public WeatherService(
			WebClient.Builder webClientBuilder,
			DatabaseClient databaseClient,
			ObjectMapper objectMapper,
			CitySuggestionIndex cityIndex,
			@Value("${app.weather.provider:open-meteo}") String provider,
			@Value("${app.weather.forecast-base-url:https://api.open-meteo.com}") String forecastBaseUrl,
			@Value("${app.weather.qweather.api-key:}") String qweatherApiKey,
			@Value("${app.weather.qweather.geo-base-url:https://geoapi.qweather.com}") String qweatherGeoBaseUrl,
			@Value("${app.weather.qweather.weather-base-url:https://devapi.qweather.com}") String qweatherWeatherBaseUrl
	) {
		this.forecastClient = webClientBuilder.baseUrl(forecastBaseUrl).build();
		this.qweatherGeoClient = webClientBuilder.baseUrl(qweatherGeoBaseUrl).build();
		this.qweatherWeatherClient = webClientBuilder.baseUrl(qweatherWeatherBaseUrl).build();
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
		this.cityIndex = cityIndex;
		this.provider = provider == null ? "open-meteo" : provider;
		this.qweatherApiKey = qweatherApiKey == null ? "" : qweatherApiKey;
	}

	public Mono<WeatherSearchResponse> search(String query, String locationId) {
		// 如果配置了 QWeather 就优先用和风天气；失败时自动回退到 Open-Meteo。
		if (usesQWeather()) {
			return searchQWeather(query, locationId).onErrorResume(error -> searchOpenMeteo(query, locationId));
		}
		return searchOpenMeteo(query, locationId);
	}

	public Mono<WeatherSearchResponse> nearby(double latitude, double longitude) {
		var candidate = cityIndex.nearestTo(latitude, longitude);
		if (candidate == null) {
			return Mono.just(WeatherSearchResponse.unsupported("当前位置"));
		}
		return search(candidate.name(), candidate.id());
	}

	public Mono<Void> refreshCache(LocationCandidate location) {
		if (location == null) {
			return Mono.empty();
		}
		// 风险提醒的定时任务会调用这里刷新过期缓存；Open-Meteo 无需 API Key，适合后台稳定刷新。
		return openMeteoWeatherFor(location.name(), location, List.of()).then();
	}

	private Mono<WeatherSearchResponse> searchOpenMeteo(String query, String locationId) {
		// locationId 存在时说明前端已经选择了明确候选城市，可以直接查天气。
		var candidate = cityIndex.findById(locationId);
		if (candidate != null) {
			return openMeteoWeatherFor(query, candidate, List.of());
		}

		// 没有明确城市时，先在本地城市索引里做模糊匹配。
		var candidates = cityIndex.search(query);
		if (candidates.isEmpty()) {
			return Mono.just(WeatherSearchResponse.unsupported(query));
		}
		if (candidates.size() > 1 && query != null && query.trim().length() <= 2) {
			return Mono.just(WeatherSearchResponse.ambiguous(query, candidates));
		}
		var selected = candidates.getFirst();
		var otherCandidates = candidates.stream()
				.filter(item -> !item.id().equals(selected.id()))
				.toList();
		return openMeteoWeatherFor(query, selected, otherCandidates);
	}

	private Mono<WeatherSearchResponse> searchQWeather(String query, String locationId) {
		// 短关键词可能匹配多个城市，先让前端展示候选项，避免查错城市。
		var localCandidates = cityIndex.search(query);
		if (localCandidates.size() > 1 && query != null && query.trim().length() <= 2 && (locationId == null || locationId.isBlank())) {
			return Mono.just(WeatherSearchResponse.ambiguous(query, localCandidates));
		}
		return qweatherGeoClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/v2/city/lookup")
						.queryParam("location", query)
						.queryParam("key", qweatherApiKey)
						.build())
				.retrieve()
				.bodyToMono(JsonNode.class)
				.flatMap(payload -> {
					if (!"200".equals(payload.path("code").asText())) {
						return Mono.just(WeatherSearchResponse.unsupported(query));
					}
					var candidates = qweatherCandidates(payload.path("location"));
					if (candidates.isEmpty()) {
						return Mono.just(WeatherSearchResponse.unsupported(query));
					}
					var selected = selectCandidate(candidates, locationId);
					var otherCandidates = candidates.stream()
							.filter(candidate -> !candidate.id().equals(selected.id()))
							.toList();
					return qweatherWeatherFor(query, selected, otherCandidates);
				});
	}

	private Mono<WeatherSearchResponse> qweatherWeatherFor(String query, LocationCandidate location, List<LocationCandidate> candidates) {
		// 和风天气的实时天气和 7 天天气是两个接口，这里并发请求后合并结果。
		var now = qweatherWeatherClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/v7/weather/now")
						.queryParam("location", location.id())
						.queryParam("key", qweatherApiKey)
						.build())
				.retrieve()
				.bodyToMono(JsonNode.class);
		var forecast = qweatherWeatherClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/v7/weather/7d")
						.queryParam("location", location.id())
						.queryParam("key", qweatherApiKey)
						.build())
				.retrieve()
				.bodyToMono(JsonNode.class);
		return Mono.zip(now, forecast)
				.map(tuple -> WeatherSearchResponse.resolved(query, fromQWeather(location, tuple.getT1(), tuple.getT2()), candidates))
				.flatMap(response -> cacheWeather(response.weather()).thenReturn(response));
	}

	private Mono<WeatherSearchResponse> openMeteoWeatherFor(String query, LocationCandidate location, List<LocationCandidate> candidates) {
		// Open-Meteo 用经纬度查询，不需要 API Key，适合作为默认天气数据源。
		return forecastClient.get()
				.uri(uriBuilder -> uriBuilder
						.path("/v1/forecast")
						.queryParam("latitude", location.latitude())
						.queryParam("longitude", location.longitude())
						.queryParam("current", "temperature_2m,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m")
						.queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
						.queryParam("timezone", location.timezone() == null ? "auto" : location.timezone())
						.build())
				.retrieve()
				.bodyToMono(JsonNode.class)
				.map(payload -> WeatherSearchResponse.resolved(query, fromOpenMeteo(location, payload), candidates))
				.flatMap(response -> cacheWeather(response.weather()).thenReturn(response))
				.onErrorResume(error -> Mono.just(WeatherSearchResponse.resolved(query, fallbackWeather(location), candidates)));
	}

	private Mono<Void> cacheWeather(WeatherResult weather) {
		// 查询成功后把天气写入缓存，收藏城市列表可以直接显示最近天气，不用每次重新调外部接口。
		if (weather == null || weather.location() == null || weather.current() == null) {
			return Mono.empty();
		}
		return databaseClient.sql("""
				insert into weather_app.weather_cache (
				    provider_location_id, display_name, payload, source_name, observed_at, expires_at, updated_at
				)
				values (
				    :locationId, :displayName, cast(:payload as jsonb), :sourceName, :observedAt, :expiresAt, now()
				)
				on conflict (provider_location_id)
				do update set
				    display_name = excluded.display_name,
				    payload = excluded.payload,
				    source_name = excluded.source_name,
				    observed_at = excluded.observed_at,
				    expires_at = excluded.expires_at,
				    updated_at = now()
				""")
				.bind("locationId", weather.location().id())
				.bind("displayName", weather.location().displayName())
				.bind("payload", cachePayload(weather).toString())
				.bind("sourceName", weather.sourceName())
				.bind("observedAt", parseInstant(weather.current().time(), Instant.now()))
				.bind("expiresAt", Instant.now().plusSeconds(1800))
				.then()
				.onErrorResume(error -> Mono.empty());
	}

	private ObjectNode cachePayload(WeatherResult weather) {
		// 缓存只保存列表展示需要的字段，结构模仿 Open-Meteo 的 current/daily 格式。
		var root = objectMapper.createObjectNode();
		var current = root.putObject("current");
		current.put("temperature_2m", weather.current().temperatureCelsius());
		current.put("relative_humidity_2m", weather.current().humidityPercent());
		current.put("precipitation", weather.current().precipitationMm());
		current.put("wind_speed_10m", weather.current().windSpeedKmh());
		current.put("weather_code", weather.current().weatherCode());
		current.put("condition", weather.current().condition());
		current.put("time", weather.current().time());
		var daily = root.putObject("daily");
		ArrayNode dates = daily.putArray("time");
		ArrayNode highs = daily.putArray("temperature_2m_max");
		ArrayNode lows = daily.putArray("temperature_2m_min");
		ArrayNode rain = daily.putArray("precipitation_probability_max");
		ArrayNode codes = daily.putArray("weather_code");
		for (var item : weather.forecast()) {
			dates.add(item.date());
			highs.add(item.highCelsius());
			lows.add(item.lowCelsius());
			if (item.precipitationProbabilityPercent() == null) {
				rain.addNull();
			} else {
				rain.add(item.precipitationProbabilityPercent());
			}
			if (item.weatherCode() == null) {
				codes.addNull();
			} else {
				codes.add(item.weatherCode());
			}
		}
		return root;
	}

	private WeatherResult fromQWeather(LocationCandidate location, JsonNode nowPayload, JsonNode forecastPayload) {
		// 把和风天气原始 JSON 转成前端统一使用的 WeatherResult。
		var now = nowPayload.path("now");
		var code = nullableInt(now.path("icon"));
		var forecast = qweatherForecast(forecastPayload.path("daily"));
		return new WeatherResult(
				location,
				new WeatherCurrent(
						nullableText(now.path("obsTime")),
						nullableDouble(now.path("temp")),
						nullableDouble(now.path("humidity")),
						nullableDouble(now.path("windSpeed")),
						nullableDouble(now.path("wind360")),
						nullableDouble(now.path("precip")),
						code,
						defaultString(nullableText(now.path("text")), "天气数据暂不可用")
				),
				forecast,
				"QWeather",
				location.timezone(),
				defaultString(nullableText(nowPayload.path("updateTime")), Instant.now().toString())
		);
	}

	private List<WeatherForecastDay> qweatherForecast(JsonNode daily) {
		// 前端只展示最近 5 天预报，所以这里最多取 5 条。
		var result = new ArrayList<WeatherForecastDay>();
		for (int index = 0; index < Math.min(5, daily.size()); index += 1) {
			var item = daily.path(index);
			var dayText = nullableText(item.path("textDay"));
			var nightText = nullableText(item.path("textNight"));
			var condition = dayText == null || dayText.equals(nightText) ? defaultString(dayText, "天气数据暂不可用") : dayText + "转" + nightText;
			result.add(new WeatherForecastDay(
					nullableText(item.path("fxDate")),
					nullableDouble(item.path("tempMax")),
					nullableDouble(item.path("tempMin")),
					nullableInt(item.path("precip")),
					nullableInt(item.path("iconDay")),
					condition
			));
		}
		return result;
	}

	private List<LocationCandidate> qweatherCandidates(JsonNode locations) {
		var result = new ArrayList<LocationCandidate>();
		for (var item : locations) {
			var name = nullableText(item.path("name"));
			var admin = nullableText(item.path("adm1"));
			var country = nullableText(item.path("country"));
			result.add(new LocationCandidate(
					nullableText(item.path("id")),
					name,
					displayName(name, admin, country),
					country,
					countryCode(country),
					admin,
					nullableText(item.path("tz")),
					parseDouble(nullableText(item.path("lat"))),
					parseDouble(nullableText(item.path("lon")))
			));
		}
		return result;
	}

	private static LocationCandidate selectCandidate(List<LocationCandidate> candidates, String locationId) {
		if (locationId != null && !locationId.isBlank()) {
			return candidates.stream()
					.filter(candidate -> candidate.id().equals(locationId))
					.findFirst()
					.orElse(candidates.getFirst());
		}
		return candidates.getFirst();
	}

	private WeatherResult fromOpenMeteo(LocationCandidate location, JsonNode payload) {
		var current = payload.path("current");
		var code = nullableInt(current.path("weather_code"));
		var forecast = parseForecast(payload.path("daily"));
		var time = nullableText(current.path("time"));
		return new WeatherResult(
				location,
				new WeatherCurrent(
						time,
						nullableDouble(current.path("temperature_2m")),
						nullableDouble(current.path("relative_humidity_2m")),
						nullableDouble(current.path("wind_speed_10m")),
						nullableDouble(current.path("wind_direction_10m")),
						nullableDouble(current.path("precipitation")),
						code,
						WeatherCode.describe(code)
				),
				forecast,
				"Open-Meteo",
				nullableText(payload.path("timezone")),
				Instant.now().toString()
		);
	}

	private List<WeatherForecastDay> parseForecast(JsonNode daily) {
		var result = new ArrayList<WeatherForecastDay>();
		var dates = daily.path("time");
		for (int index = 0; index < Math.min(5, dates.size()); index += 1) {
			var code = nullableInt(daily.path("weather_code").path(index));
			result.add(new WeatherForecastDay(
					dates.path(index).asText(),
					nullableDouble(daily.path("temperature_2m_max").path(index)),
					nullableDouble(daily.path("temperature_2m_min").path(index)),
					nullableInt(daily.path("precipitation_probability_max").path(index)),
					code,
					WeatherCode.describe(code)
			));
		}
		return result;
	}

	private WeatherResult fallbackWeather(LocationCandidate location) {
		var today = LocalDate.now();
		var forecast = new ArrayList<WeatherForecastDay>();
		for (int index = 0; index < 5; index += 1) {
			forecast.add(new WeatherForecastDay(today.plusDays(index).toString(), 24.0 + index, 17.0 + index, 20, 2, "少云"));
		}
		return new WeatherResult(
				location,
				new WeatherCurrent(Instant.now().toString(), 22.0, 65.0, 12.0, 90.0, 0.0, 2, "少云"),
				forecast,
				"本地降级数据",
				location.timezone(),
				Instant.now().toString()
		);
	}

	private static Double nullableDouble(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
	}

	private static Integer nullableInt(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
	}

	private static String nullableText(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
	}

	private boolean usesQWeather() {
		return provider.toLowerCase(Locale.ROOT).contains("qweather") && !qweatherApiKey.isBlank();
	}

	private static String displayName(String name, String admin, String country) {
		var parts = new ArrayList<String>();
		if (name != null && !name.isBlank()) {
			parts.add(name);
		}
		if (admin != null && !admin.isBlank() && !admin.equals(name)) {
			parts.add(admin);
		}
		if (country != null && !country.isBlank()) {
			parts.add(country);
		}
		return String.join(", ", parts);
	}

	private static String countryCode(String country) {
		return "中国".equals(country) ? "CN" : null;
	}

	private static double parseDouble(String value) {
		try {
			return value == null ? 0.0 : Double.parseDouble(value);
		} catch (NumberFormatException error) {
			return 0.0;
		}
	}

	private static String defaultString(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Instant parseInstant(String value, Instant fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (Exception ignored) {
			try {
				return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
			} catch (Exception ignoredAgain) {
				return fallback;
			}
		}
	}
}
