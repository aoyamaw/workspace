package com.example.weatherapp.notifications;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class NotificationSubscriptionService {

	// 通知订阅服务：保存用户对某个收藏城市的提醒设置。
	private final DatabaseClient databaseClient;

	public NotificationSubscriptionService(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<NotificationSubscriptionResponse> list(UUID ownerId) {
		requireOwner(ownerId);
		// 只返回 active=true 的订阅，已关闭的订阅保留在数据库中方便追溯。
		return databaseClient.sql("""
				select s.id, s.favorite_city_id, f.display_name as favorite_display_name,
				       s.channel, s.permission_status, s.daily_summary_enabled,
				       s.severe_weather_enabled, s.cooldown_minutes, s.active,
				       s.created_at, s.updated_at
				from weather_app.notification_subscriptions s
				join weather_app.favorite_cities f on f.id = s.favorite_city_id
				where s.owner_id = :ownerId and s.active = true
				order by s.updated_at desc
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> new NotificationSubscriptionResponse(
						row.get("id", UUID.class),
						row.get("favorite_city_id", UUID.class),
						row.get("favorite_display_name", String.class),
						row.get("channel", String.class),
						row.get("permission_status", String.class),
						Boolean.TRUE.equals(row.get("daily_summary_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("severe_weather_enabled", Boolean.class)),
						valueOrDefault(row.get("cooldown_minutes", Integer.class), 60),
						Boolean.TRUE.equals(row.get("active", Boolean.class)),
						row.get("created_at", java.time.Instant.class),
						row.get("updated_at", java.time.Instant.class)
				))
				.all();
	}

	public Mono<NotificationSubscriptionResponse> upsert(UUID ownerId, NotificationSubscriptionRequest request) {
		requireOwner(ownerId);
		if (request.favoriteCityId() == null) {
			throw new IllegalArgumentException("favoriteCityId is required");
		}
		// 开启通知前先确认这个收藏城市属于当前用户。
		return databaseClient.sql("select id from weather_app.favorite_cities where id = :favoriteCityId and owner_id = :ownerId")
				.bind("favoriteCityId", request.favoriteCityId())
				.bind("ownerId", ownerId)
				.map(row -> row.get("id", UUID.class))
				.one()
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite city not found")))
				.flatMap(ignored -> saveSubscription(ownerId, request));
	}

	public Mono<Void> deactivate(UUID ownerId, UUID subscriptionId) {
		requireOwner(ownerId);
		return databaseClient.sql("""
				update weather_app.notification_subscriptions
				set active = false, updated_at = now()
				where id = :subscriptionId and owner_id = :ownerId
				""")
				.bind("subscriptionId", subscriptionId)
				.bind("ownerId", ownerId)
				.then();
	}

	private Mono<NotificationSubscriptionResponse> saveSubscription(UUID ownerId, NotificationSubscriptionRequest request) {
		// channel 为 web_push 时会保存浏览器推送参数；in_app 时这些字段可以为空。
		var channel = defaultString(request.channel(), "in_app");
		var permissionStatus = defaultString(request.permissionStatus(), "unsupported");
		var dailySummary = request.dailySummaryEnabled() == null || request.dailySummaryEnabled();
		var severeWeather = request.severeWeatherEnabled() == null || request.severeWeatherEnabled();
		var cooldown = request.cooldownMinutes() == null ? 1 : Math.max(0, request.cooldownMinutes());
		var spec = databaseClient.sql("""
				insert into weather_app.notification_subscriptions (
				    owner_id, favorite_city_id, channel, permission_status, push_endpoint, push_p256dh,
				    push_auth, daily_summary_enabled, severe_weather_enabled, cooldown_minutes, active
				)
				values (:ownerId, :favoriteCityId, :channel, :permissionStatus, :pushEndpoint, :pushP256dh,
				        :pushAuth, :dailySummary, :severeWeather, :cooldown, true)
				returning id
				""")
				.bind("ownerId", ownerId)
				.bind("favoriteCityId", request.favoriteCityId())
				.bind("channel", channel)
				.bind("permissionStatus", permissionStatus)
				.bind("dailySummary", dailySummary)
				.bind("severeWeather", severeWeather)
				.bind("cooldown", cooldown);
		spec = bindNullable(spec, "pushEndpoint", request.pushEndpoint(), String.class);
		spec = bindNullable(spec, "pushP256dh", request.pushP256dh(), String.class);
		spec = bindNullable(spec, "pushAuth", request.pushAuth(), String.class);
		return spec.map(row -> row.get("id", UUID.class))
				.one()
				.flatMap(id -> findById(ownerId, id));
	}

	private Mono<NotificationSubscriptionResponse> findById(UUID ownerId, UUID subscriptionId) {
		return databaseClient.sql("""
				select s.id, s.favorite_city_id, f.display_name as favorite_display_name,
				       s.channel, s.permission_status, s.daily_summary_enabled,
				       s.severe_weather_enabled, s.cooldown_minutes, s.active,
				       s.created_at, s.updated_at
				from weather_app.notification_subscriptions s
				join weather_app.favorite_cities f on f.id = s.favorite_city_id
				where s.owner_id = :ownerId and s.id = :subscriptionId
				""")
				.bind("ownerId", ownerId)
				.bind("subscriptionId", subscriptionId)
				.map((row, metadata) -> new NotificationSubscriptionResponse(
						row.get("id", UUID.class),
						row.get("favorite_city_id", UUID.class),
						row.get("favorite_display_name", String.class),
						row.get("channel", String.class),
						row.get("permission_status", String.class),
						Boolean.TRUE.equals(row.get("daily_summary_enabled", Boolean.class)),
						Boolean.TRUE.equals(row.get("severe_weather_enabled", Boolean.class)),
						valueOrDefault(row.get("cooldown_minutes", Integer.class), 60),
						Boolean.TRUE.equals(row.get("active", Boolean.class)),
						row.get("created_at", java.time.Instant.class),
						row.get("updated_at", java.time.Instant.class)
				))
				.one();
	}

	private static void requireOwner(UUID ownerId) {
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
	}

	private static String defaultString(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static int valueOrDefault(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	private static DatabaseClient.GenericExecuteSpec bindNullable(
			DatabaseClient.GenericExecuteSpec spec,
			String name,
			Object value,
			Class<?> type
	) {
		return value == null || (value instanceof String text && text.isBlank()) ? spec.bindNull(name, type) : spec.bind(name, value);
	}
}
