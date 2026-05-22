package com.example.weatherapp.favorites;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class FavoriteCityService {

	// 收藏城市服务：负责收藏列表、保存收藏、删除收藏，以及读取缓存天气用于列表显示。
	private final DatabaseClient databaseClient;

	public FavoriteCityService(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	public Flux<FavoriteCityResponse> listFavorites(UUID ownerId) {
		requireOwner(ownerId);
		// 左连接 weather_cache，这样即使缓存不存在，收藏城市本身也能显示。
		return databaseClient.sql("""
				select f.id, f.provider_location_id, f.display_name, f.country_code,
				       f.latitude::float8 as latitude, f.longitude::float8 as longitude,
				       f.created_at,
				       wc.payload #>> '{current,temperature_2m}' as latest_temperature,
				       wc.payload #>> '{current,condition}' as latest_condition,
				       wc.payload #>> '{current,weather_code}' as latest_weather_code,
				       wc.updated_at as weather_updated_at,
				       wc.expires_at
				from weather_app.favorite_cities f
				left join weather_app.weather_cache wc on wc.provider_location_id = f.provider_location_id
				where f.owner_id = :ownerId
				order by f.created_at desc
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> mapFavorite(row))
				.all();
	}

	public Mono<FavoriteCityResponse> saveFavorite(UUID ownerId, FavoriteCityRequest request) {
		requireOwner(ownerId);
		if (request.providerLocationId() == null || request.providerLocationId().isBlank() || request.displayName() == null || request.displayName().isBlank()) {
			throw new IllegalArgumentException("providerLocationId and displayName are required");
		}
		// on conflict 可以防止同一用户重复收藏同一城市时插入重复数据。
		return databaseClient.sql("""
				insert into weather_app.favorite_cities (
				    owner_id, provider_location_id, display_name, country_code, latitude, longitude
				)
				values (:ownerId, :providerLocationId, :displayName, :countryCode, :latitude, :longitude)
				on conflict (owner_id, provider_location_id)
				do update set
				    display_name = excluded.display_name,
				    country_code = excluded.country_code,
				    latitude = excluded.latitude,
				    longitude = excluded.longitude,
				    updated_at = now()
				returning id, provider_location_id, display_name, country_code,
				          latitude::float8 as latitude, longitude::float8 as longitude, created_at
				""")
				.bind("ownerId", ownerId)
				.bind("providerLocationId", request.providerLocationId())
				.bind("displayName", request.displayName())
				.bind("countryCode", request.countryCode())
				.bind("latitude", request.latitude())
				.bind("longitude", request.longitude())
				.map((row, metadata) -> new FavoriteCityResponse(
						row.get("id", UUID.class),
						row.get("provider_location_id", String.class),
						row.get("display_name", String.class),
						row.get("country_code", String.class),
						row.get("latitude", Double.class),
						row.get("longitude", Double.class),
						null,
						null,
						false,
						"unavailable",
						null,
						row.get("created_at", Instant.class)
				))
				.one();
	}

	public Mono<Void> deleteFavorite(UUID ownerId, UUID favoriteId) {
		requireOwner(ownerId);
		return databaseClient.sql("delete from weather_app.favorite_cities where owner_id = :ownerId and id = :favoriteId")
				.bind("ownerId", ownerId)
				.bind("favoriteId", favoriteId)
				.then();
	}

	private static FavoriteCityResponse mapFavorite(io.r2dbc.spi.Row row) {
		// 把数据库行转换成前端需要的对象，并判断缓存天气是否过期。
		var expiresAt = row.get("expires_at", Instant.class);
		var weatherUpdatedAt = row.get("weather_updated_at", Instant.class);
		var temperature = parseDouble(row.get("latest_temperature", String.class));
		var condition = row.get("latest_condition", String.class);
		var code = parseInteger(row.get("latest_weather_code", String.class));
		var available = weatherUpdatedAt != null && (expiresAt == null || expiresAt.isAfter(Instant.now()));
		var status = weatherUpdatedAt == null ? "unavailable" : available ? "available" : "stale";
		return new FavoriteCityResponse(
				row.get("id", UUID.class),
				row.get("provider_location_id", String.class),
				row.get("display_name", String.class),
				row.get("country_code", String.class),
				row.get("latitude", Double.class),
				row.get("longitude", Double.class),
				temperature,
				condition == null || condition.isBlank() ? describeWeather(code) : condition,
				available,
				status,
				weatherUpdatedAt,
				row.get("created_at", Instant.class)
		);
	}

	private static void requireOwner(UUID ownerId) {
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
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

	private static String describeWeather(Integer code) {
		if (code == null) {
			return null;
		}
		if (code >= 61 && code <= 82) {
			return "雨";
		}
		if (code >= 95) {
			return "雷暴";
		}
		return code == 0 ? "晴" : "多变";
	}
}
