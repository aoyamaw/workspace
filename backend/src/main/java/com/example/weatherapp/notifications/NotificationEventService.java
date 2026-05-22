package com.example.weatherapp.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.weatherapp.weather.LocationCandidate;
import com.example.weatherapp.weather.WeatherService;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class NotificationEventService {

	// 通知事件服务：根据订阅生成提醒记录，并分发给应用内通知和浏览器推送。
	private final DatabaseClient databaseClient;
	private final WebPushService webPushService;
	private final InAppNotificationBroker inAppNotificationBroker;
	private final WeatherService weatherService;

	public NotificationEventService(
			DatabaseClient databaseClient,
			WebPushService webPushService,
			InAppNotificationBroker inAppNotificationBroker,
			WeatherService weatherService
	) {
		this.databaseClient = databaseClient;
		this.webPushService = webPushService;
		this.inAppNotificationBroker = inAppNotificationBroker;
		this.weatherService = weatherService;
	}

	public Flux<NotificationEventResponse> listEvents(UUID ownerId) {
		requireOwner(ownerId);
		return databaseClient.sql("""
				select e.id, e.subscription_id, e.favorite_city_id, f.display_name as favorite_display_name,
				       e.event_type, e.title, e.body, e.severity, e.delivery_status, e.created_at
				from weather_app.notification_events e
				join weather_app.favorite_cities f on f.id = e.favorite_city_id
				where e.owner_id = :ownerId
				order by e.created_at desc
				limit 50
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> mapEvent(row))
				.all();
	}

	public Mono<Void> deleteEvent(UUID ownerId, UUID eventId) {
		requireOwner(ownerId);
		if (eventId == null) {
			throw new IllegalArgumentException("eventId is required");
		}
		return databaseClient.sql("""
				delete from weather_app.notification_events
				where owner_id = :ownerId and id = :eventId
				""")
				.bind("ownerId", ownerId)
				.bind("eventId", eventId)
				.then();
	}

	public Mono<NotificationEvaluationResponse> evaluate(UUID ownerId) {
		requireOwner(ownerId);
		// 评估流程：刷新过期天气 -> 找订阅 -> 根据天气缓存判断风险 -> 去重抑制 -> 写入事件表 -> 分发通知。
		return refreshStaleSubscribedWeather(ownerId)
				.thenMany(activeSubscriptions(ownerId))
				.flatMap(this::intentsForSubscription)
				.collectList()
				.flatMap(intents -> Flux.fromIterable(intents)
						.flatMap(intent -> shouldSuppress(ownerId, intent)
								.flatMap(suppress -> suppress
										? Mono.<NotificationEventResponse>empty()
										: createEvent(ownerId, intent)))
						.collectList()
						.flatMap(events -> dispatch(ownerId, events)
								.thenReturn(new NotificationEvaluationResponse(
										events.size(),
										intents.size() - events.size(),
										events,
										Instant.now()
								))));
	}

	public Mono<NotificationEvaluationResponse> testPush(UUID ownerId) {
		requireOwner(ownerId);
		// 测试推送只取第一个有效订阅，用于验证前后端推送链路是否正常。
		return activeSubscriptions(ownerId)
				.next()
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No active notification subscription")))
				.flatMap(subscription -> createEvent(ownerId, new NotificationIntent(
						subscription.subscriptionId(),
						subscription.favoriteCityId(),
							subscription.favoriteDisplayName(),
							"weather_change",
							subscription.favoriteDisplayName() + "测试推送",
							"这是一条测试天气推送，用于验证后台 Web Push 和应用内实时通知链路。",
							"test",
							subscription.cooldownMinutes()
					)))
				.flatMap(event -> dispatch(ownerId, List.of(event))
						.thenReturn(new NotificationEvaluationResponse(1, 0, List.of(event), Instant.now())));
	}

	private Mono<Void> refreshStaleSubscribedWeather(UUID ownerId) {
		return databaseClient.sql("""
				select distinct f.provider_location_id, f.display_name, f.country_code,
				       f.latitude::float8 as latitude, f.longitude::float8 as longitude
				from weather_app.notification_subscriptions s
				join weather_app.favorite_cities f on f.id = s.favorite_city_id
				left join weather_app.weather_cache wc on wc.provider_location_id = f.provider_location_id
				where s.owner_id = :ownerId
				  and s.active = true
				  and f.latitude is not null
				  and f.longitude is not null
				  and (wc.provider_location_id is null or wc.expires_at <= now())
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> new LocationCandidate(
						row.get("provider_location_id", String.class),
						cityName(row.get("display_name", String.class)),
						row.get("display_name", String.class),
						null,
						row.get("country_code", String.class),
						null,
						"Asia/Shanghai",
						row.get("latitude", Double.class),
						row.get("longitude", Double.class)
				))
				.all()
				.flatMap(weatherService::refreshCache, 4)
				.then()
				.onErrorResume(error -> Mono.empty());
	}

	private Mono<Void> dispatch(UUID ownerId, List<NotificationEventResponse> events) {
		// 同一批事件会同时发给页面内实时通知和浏览器后台 Web Push。
		inAppNotificationBroker.publish(ownerId, events);
		return webPushService.send(ownerId, events);
	}

	private Flux<SubscriptionRow> activeSubscriptions(UUID ownerId) {
		return databaseClient.sql("""
				select s.id as subscription_id,
				       s.favorite_city_id,
				       f.display_name,
				       s.daily_summary_enabled,
				       s.severe_weather_enabled,
				       s.cooldown_minutes,
				       wc.source_name,
				       wc.updated_at as weather_updated_at,
				       wc.expires_at,
				       wc.payload #>> '{current,temperature_2m}' as current_temperature,
				       wc.payload #>> '{current,weather_code}' as current_weather_code,
				       wc.payload #>> '{current,wind_speed_10m}' as current_wind_speed,
				       wc.payload #>> '{current,precipitation}' as current_precipitation,
				       wc.payload #>> '{daily,temperature_2m_max,0}' as forecast_high,
				       wc.payload #>> '{daily,temperature_2m_min,0}' as forecast_low,
				       wc.payload #>> '{daily,precipitation_probability_max,0}' as rain_probability,
				       wc.payload #>> '{daily,weather_code,0}' as forecast_weather_code
				from weather_app.notification_subscriptions s
				join weather_app.favorite_cities f on f.id = s.favorite_city_id
				left join weather_app.weather_cache wc on wc.provider_location_id = f.provider_location_id
				where s.owner_id = :ownerId and s.active = true
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> new SubscriptionRow(
						row.get("subscription_id", UUID.class),
						row.get("favorite_city_id", UUID.class),
						row.get("display_name", String.class),
						Boolean.TRUE.equals(row.get("daily_summary_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("severe_weather_enabled", Boolean.class)),
						valueOrDefault(row.get("cooldown_minutes", Integer.class), 60),
						row.get("source_name", String.class),
						row.get("weather_updated_at", Instant.class),
						row.get("expires_at", Instant.class),
						parseDouble(row.get("current_temperature", String.class)),
						parseInteger(row.get("current_weather_code", String.class)),
						parseDouble(row.get("current_wind_speed", String.class)),
						parseDouble(row.get("current_precipitation", String.class)),
						parseDouble(row.get("forecast_high", String.class)),
						parseDouble(row.get("forecast_low", String.class)),
						parseInteger(row.get("rain_probability", String.class)),
						parseInteger(row.get("forecast_weather_code", String.class))
				))
				.all();
	}

	private Flux<NotificationIntent> intentsForSubscription(SubscriptionRow subscription) {
		// 只有天气缓存仍然有效时才判断风险，避免用过期天气生成误导性提醒。
		if (!subscription.severeWeatherEnabled() || subscription.weatherUpdatedAt() == null || isExpired(subscription.expiresAt())) {
			return Flux.empty();
		}

		var intents = new java.util.ArrayList<NotificationIntent>();
		var city = subscription.favoriteDisplayName();
		var forecastCode = subscription.forecastWeatherCode();
		var currentCode = subscription.currentWeatherCode();
		var code = forecastCode == null ? currentCode : forecastCode;

		if (isThunderstorm(subscription.sourceName(), code)) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"severe_weather",
					city + "雷暴风险提醒",
					"预报中出现雷暴风险，建议减少户外活动，出行前关注官方气象预警。",
					"danger",
					subscription.cooldownMinutes()
			));
		} else if (isHeavyRainRisk(subscription.sourceName(), subscription.rainProbability(), code, subscription.currentPrecipitation())) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"severe_weather",
					city + "降雨风险提醒",
					rainBody(subscription.rainProbability()),
					"watch",
					subscription.cooldownMinutes()
			));
		}

		if (subscription.forecastHigh() != null && subscription.forecastHigh() >= 35) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"weather_change",
					city + "高温风险提醒",
					"预计最高气温约 " + Math.round(subscription.forecastHigh()) + "℃，建议减少暴晒、及时补水并注意防暑。",
					"watch",
					subscription.cooldownMinutes()
			));
		}

		if (subscription.forecastLow() != null && subscription.forecastLow() <= 5) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"weather_change",
					city + "低温风险提醒",
					"预计最低气温约 " + Math.round(subscription.forecastLow()) + "℃，建议增加保暖衣物并留意道路结冰风险。",
					"watch",
					subscription.cooldownMinutes()
			));
		}

		if (subscription.currentWindSpeed() != null && subscription.currentWindSpeed() >= 30) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"severe_weather",
					city + "大风风险提醒",
					"当前风速约 " + Math.round(subscription.currentWindSpeed()) + " km/h，建议固定室外物品，骑行和高空活动注意安全。",
					"watch",
					subscription.cooldownMinutes()
			));
		}

		if (intents.isEmpty() && subscription.dailySummaryEnabled()) {
			intents.add(new NotificationIntent(
					subscription.subscriptionId(),
					subscription.favoriteCityId(),
					city,
					"daily_summary",
					city + "天气正常提醒",
					normalWeatherBody(subscription),
					"info",
					subscription.cooldownMinutes()
			));
		}

		return Flux.fromIterable(intents);
	}

	private Mono<Boolean> shouldSuppress(UUID ownerId, NotificationIntent intent) {
		// 在订阅的冷却时间内，相同订阅、相同类型的通知只保留一条，避免重复提醒。
		return databaseClient.sql("""
				select count(*)::int as count
				from weather_app.notification_events
				where owner_id = :ownerId
				  and subscription_id = :subscriptionId
				  and event_type = :eventType
				  and created_at > now() - (:cooldownMinutes * interval '1 minute')
				""")
				.bind("ownerId", ownerId)
				.bind("subscriptionId", intent.subscriptionId())
				.bind("eventType", intent.eventType())
				.bind("cooldownMinutes", Math.max(0, intent.cooldownMinutes()))
				.map(row -> row.get("count", Integer.class))
				.one()
				.map(count -> count != null && count > 0);
	}

	private Mono<NotificationEventResponse> createEvent(UUID ownerId, NotificationIntent intent) {
		return databaseClient.sql("""
				insert into weather_app.notification_events (
				    subscription_id, owner_id, favorite_city_id, event_type, title, body, severity, delivery_status
				)
				values (:subscriptionId, :ownerId, :favoriteCityId, :eventType, :title, :body, :severity, 'sent')
				returning id, subscription_id, favorite_city_id, event_type, title, body, severity, delivery_status, created_at
				""")
				.bind("subscriptionId", intent.subscriptionId())
				.bind("ownerId", ownerId)
				.bind("favoriteCityId", intent.favoriteCityId())
				.bind("eventType", intent.eventType())
				.bind("title", intent.title())
				.bind("body", intent.body())
				.bind("severity", intent.severity())
				.map((row, metadata) -> new NotificationEventResponse(
						row.get("id", UUID.class),
						row.get("subscription_id", UUID.class),
						row.get("favorite_city_id", UUID.class),
						intent.favoriteDisplayName(),
						row.get("event_type", String.class),
						row.get("title", String.class),
						row.get("body", String.class),
						row.get("severity", String.class),
						row.get("delivery_status", String.class),
						row.get("created_at", Instant.class)
				))
				.one();
	}

	private static NotificationEventResponse mapEvent(io.r2dbc.spi.Row row) {
		return new NotificationEventResponse(
				row.get("id", UUID.class),
				row.get("subscription_id", UUID.class),
				row.get("favorite_city_id", UUID.class),
				row.get("favorite_display_name", String.class),
				row.get("event_type", String.class),
				row.get("title", String.class),
				row.get("body", String.class),
				row.get("severity", String.class),
				row.get("delivery_status", String.class),
				row.get("created_at", Instant.class)
		);
	}

	private static void requireOwner(UUID ownerId) {
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
	}

	private static String cityName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			return "未知城市";
		}
		var commaIndex = displayName.indexOf(',');
		return commaIndex < 0 ? displayName : displayName.substring(0, commaIndex).trim();
	}

	private static boolean isExpired(Instant expiresAt) {
		return expiresAt == null || !expiresAt.isAfter(Instant.now());
	}

	private static boolean isThunderstorm(String sourceName, Integer weatherCode) {
		if (weatherCode == null) {
			return false;
		}
		if (isQWeather(sourceName)) {
			return weatherCode >= 302 && weatherCode <= 304;
		}
		return weatherCode == 95 || weatherCode == 96 || weatherCode == 99;
	}

	private static boolean isHeavyRainRisk(String sourceName, Integer rainProbability, Integer weatherCode, Double currentPrecipitation) {
		return (rainProbability != null && rainProbability >= 60)
				|| isRainCode(sourceName, weatherCode)
				|| (currentPrecipitation != null && currentPrecipitation > 0);
	}

	private static boolean isRainCode(String sourceName, Integer weatherCode) {
		if (weatherCode == null) {
			return false;
		}
		if (isQWeather(sourceName)) {
			return weatherCode >= 305 && weatherCode <= 318;
		}
		return weatherCode >= 61 && weatherCode <= 82;
	}

	private static boolean isQWeather(String sourceName) {
		return sourceName != null && sourceName.equalsIgnoreCase("QWeather");
	}

	private static String rainBody(Integer rainProbability) {
		if (rainProbability == null) {
			return "检测到降雨风险，建议出门携带雨具，通勤和户外活动注意路面湿滑。";
		}
		return "预计降雨概率约 " + rainProbability + "%，建议出门携带雨具，通勤和户外活动注意路面湿滑。";
	}

	private static String normalWeatherBody(SubscriptionRow subscription) {
		var high = subscription.forecastHigh() == null ? "最高温暂不可用" : "最高约 " + Math.round(subscription.forecastHigh()) + "℃";
		var low = subscription.forecastLow() == null ? "最低温暂不可用" : "最低约 " + Math.round(subscription.forecastLow()) + "℃";
		var rain = subscription.rainProbability() == null ? "降雨概率暂不可用" : "降雨概率约 " + subscription.rainProbability() + "%";
		return "当前未检测到明显天气风险，" + high + "，" + low + "，" + rain + "，整体天气还算正常，适合按计划出门。";
	}

	private static Double parseDouble(String value) {
		try {
			return value == null ? null : Double.parseDouble(value);
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static Integer parseInteger(String value) {
		try {
			return value == null ? null : Integer.parseInt(value);
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static int valueOrDefault(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	private record SubscriptionRow(
			UUID subscriptionId,
			UUID favoriteCityId,
			String favoriteDisplayName,
			boolean dailySummaryEnabled,
			boolean severeWeatherEnabled,
			int cooldownMinutes,
			String sourceName,
			Instant weatherUpdatedAt,
			Instant expiresAt,
			Double currentTemperature,
			Integer currentWeatherCode,
			Double currentWindSpeed,
			Double currentPrecipitation,
			Double forecastHigh,
			Double forecastLow,
			Integer rainProbability,
			Integer forecastWeatherCode
	) {
	}

	private record NotificationIntent(
			UUID subscriptionId,
			UUID favoriteCityId,
			String favoriteDisplayName,
				String eventType,
				String title,
				String body,
				String severity,
				int cooldownMinutes
	) {
	}
}
