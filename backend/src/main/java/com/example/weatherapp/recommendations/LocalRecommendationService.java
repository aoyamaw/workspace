package com.example.weatherapp.recommendations;

import io.r2dbc.spi.Row;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class LocalRecommendationService {

	private static final int CATEGORY_SIZE = 5;

	private final DatabaseClient databaseClient;
	private final LocalRecommendationGenerator generator;

	public LocalRecommendationService(DatabaseClient databaseClient, LocalRecommendationGenerator generator) {
		this.databaseClient = databaseClient;
		this.generator = generator;
	}

	public Mono<LocalRecommendationResponse> initial(UUID ownerId, String providerLocationId, String displayName) {
		return buildBatch(ownerId, normalizeLocationId(providerLocationId), normalizeDisplayName(displayName), List.of(), "initial");
	}

	public Mono<LocalRecommendationResponse> refresh(UUID ownerId, LocalRecommendationRefreshRequest request) {
		var displayed = request.displayedItemIds() == null ? List.<UUID>of() : request.displayedItemIds();
		return currentBatchItemIds(request.currentBatchId())
				.map(ids -> {
					var combined = new ArrayList<UUID>(displayed);
					combined.addAll(ids);
					return combined.stream().filter(Objects::nonNull).distinct().toList();
				})
				.flatMap(excluded -> buildBatch(
						ownerId,
						normalizeLocationId(request.providerLocationId()),
						normalizeDisplayName(request.displayName()),
						excluded,
						"refresh"
				));
	}

	@Transactional
	public Mono<LocalRecommendationResponse> buildBatch(
			UUID ownerId,
			String providerLocationId,
			String displayName,
			List<UUID> excludedIds,
			String batchType
	) {
		return ensurePool(providerLocationId, displayName, 0)
				.then(selectCategory(ownerId, providerLocationId, RecommendationCategory.food, excludedIds))
				.zipWith(selectCategory(ownerId, providerLocationId, RecommendationCategory.place, excludedIds))
				.flatMap(tuple -> {
					var foods = tuple.getT1();
					var places = tuple.getT2();
					if (foods.size() < CATEGORY_SIZE || places.size() < CATEGORY_SIZE) {
						return ensurePool(providerLocationId, displayName, (int) (System.nanoTime() % 11))
								.then(selectCategory(ownerId, providerLocationId, RecommendationCategory.food, excludedIds))
								.zipWith(selectCategory(ownerId, providerLocationId, RecommendationCategory.place, excludedIds));
					}
					return Mono.just(tuple);
				})
				.flatMap(tuple -> persistBatch(ownerId, providerLocationId, displayName, tuple.getT1(), tuple.getT2(), batchType));
	}

	private Mono<Void> ensurePool(String providerLocationId, String displayName, int offset) {
		return countItems(providerLocationId)
				.flatMap(count -> {
					if (count >= CATEGORY_SIZE * 2L) {
						return Mono.empty();
					}
					return Flux.fromIterable(generator.generate(displayName, offset))
							.flatMap(seed -> saveSeed(providerLocationId, displayName, seed))
							.then();
				});
	}

	private Mono<Long> countItems(String providerLocationId) {
		return databaseClient.sql("""
				select count(*) as item_count
				from weather_app.local_recommendation_items
				where provider_location_id = :providerLocationId
				""")
				.bind("providerLocationId", providerLocationId)
				.map((row, metadata) -> row.get("item_count", Long.class))
				.one()
				.defaultIfEmpty(0L);
	}

	private Mono<Void> saveSeed(String providerLocationId, String displayName, RecommendationSeed seed) {
		var sourceUrl = validUrl(seed.sourceUrl()) ? seed.sourceUrl() : "https://www.mct.gov.cn/";
		return databaseClient.sql("""
				insert into weather_app.local_recommendation_items (
				    provider_location_id, display_name, category, name, description, image_url,
				    image_alt, source_title, source_url, content_fingerprint
				)
				values (
				    :providerLocationId, :displayName, :category, :name, :description, :imageUrl,
				    :imageAlt, :sourceTitle, :sourceUrl, :fingerprint
				)
				on conflict (provider_location_id, category, content_fingerprint)
				do update set updated_at = now()
				""")
				.bind("providerLocationId", providerLocationId)
				.bind("displayName", displayName)
				.bind("category", seed.category().name())
				.bind("name", bounded(seed.name(), 80))
				.bind("description", bounded(seed.description(), 120))
				.bind("imageUrl", validUrl(seed.imageUrl()) ? seed.imageUrl() : fallbackImageUrl())
				.bind("imageAlt", bounded(seed.imageAlt(), 80))
				.bind("sourceTitle", bounded(seed.sourceTitle(), 80))
				.bind("sourceUrl", sourceUrl)
				.bind("fingerprint", fingerprint(providerLocationId, seed.category(), seed.name()))
				.then();
	}

	private Mono<List<StoredItem>> selectCategory(
			UUID ownerId,
			String providerLocationId,
			RecommendationCategory category,
			List<UUID> excludedIds
	) {
		var excluded = excludedIds == null ? List.<UUID>of() : excludedIds.stream().filter(Objects::nonNull).toList();
		var sql = new StringBuilder("""
				select i.id, i.category, i.name, i.description, i.image_url, i.image_alt,
				       i.source_title, i.source_url
				from weather_app.local_recommendation_items i
				where i.provider_location_id = :providerLocationId
				  and i.category = :category
				  and (:ownerId is null or not exists (
				      select 1 from weather_app.local_recommendation_views v
				      where v.owner_id = :ownerId and v.provider_location_id = :providerLocationId
				        and v.recommendation_item_id = i.id
				  ))
				""");
		for (int index = 0; index < excluded.size(); index++) {
			sql.append(" and i.id <> :excludedId").append(index).append("\n");
		}
		sql.append("""
				order by i.created_at desc, i.name
				limit 5
				""");
		var spec = databaseClient.sql(sql.toString())
				.bind("providerLocationId", providerLocationId)
				.bind("category", category.name());
		spec = ownerId == null ? spec.bindNull("ownerId", UUID.class) : spec.bind("ownerId", ownerId);
		for (int index = 0; index < excluded.size(); index++) {
			spec = spec.bind("excludedId" + index, excluded.get(index));
		}
		return spec.map((row, metadata) -> mapStored(row)).all().collectList();
	}

	private Mono<LocalRecommendationResponse> persistBatch(
			UUID ownerId,
			String providerLocationId,
			String displayName,
			List<StoredItem> foods,
			List<StoredItem> places,
			String batchType
	) {
		var message = foods.size() < CATEGORY_SIZE || places.size() < CATEGORY_SIZE ? "暂无更多不重复推荐。" : "";
		var spec = databaseClient.sql("""
				insert into weather_app.local_recommendation_batches (
				    owner_id, provider_location_id, display_name, batch_type, message
				)
				values (:ownerId, :providerLocationId, :displayName, :batchType, :message)
				returning id, created_at
				""")
				.bind("providerLocationId", providerLocationId)
				.bind("displayName", displayName)
				.bind("batchType", batchType)
				.bind("message", message);
		spec = ownerId == null ? spec.bindNull("ownerId", UUID.class) : spec.bind("ownerId", ownerId);
		return spec.map((row, metadata) -> new Batch(row.get("id", UUID.class), row.get("created_at", Instant.class)))
				.one()
				.flatMap(batch -> persistBatchItems(batch.id(), RecommendationCategory.food, foods)
						.then(persistBatchItems(batch.id(), RecommendationCategory.place, places))
						.then(recordViews(ownerId, providerLocationId, foods, places))
						.thenReturn(toResponse(batch, providerLocationId, displayName, foods, places, message)));
	}

	private Mono<Void> persistBatchItems(UUID batchId, RecommendationCategory category, List<StoredItem> items) {
		var ranked = new ArrayList<Mono<Void>>();
		for (int index = 0; index < Math.min(CATEGORY_SIZE, items.size()); index++) {
			var item = items.get(index);
			var rank = index + 1;
			ranked.add(databaseClient.sql("""
					insert into weather_app.local_recommendation_batch_items (
					    batch_id, recommendation_item_id, category, rank
					)
					values (:batchId, :itemId, :category, :rank)
					""")
					.bind("batchId", batchId)
					.bind("itemId", item.id())
					.bind("category", category.name())
					.bind("rank", rank)
					.then());
		}
		return Flux.concat(ranked).then();
	}

	private Mono<Void> recordViews(UUID ownerId, String providerLocationId, List<StoredItem> foods, List<StoredItem> places) {
		if (ownerId == null) {
			return Mono.empty();
		}
		var items = new ArrayList<StoredItem>();
		items.addAll(foods);
		items.addAll(places);
		return Flux.fromIterable(items)
				.flatMap(item -> databaseClient.sql("""
						insert into weather_app.local_recommendation_views (
						    owner_id, provider_location_id, recommendation_item_id
						)
						values (:ownerId, :providerLocationId, :itemId)
						on conflict (owner_id, provider_location_id, recommendation_item_id) do nothing
						""")
						.bind("ownerId", ownerId)
						.bind("providerLocationId", providerLocationId)
						.bind("itemId", item.id())
						.then())
				.then();
	}

	private Mono<List<UUID>> currentBatchItemIds(UUID batchId) {
		if (batchId == null) {
			return Mono.just(List.of());
		}
		return databaseClient.sql("""
				select recommendation_item_id
				from weather_app.local_recommendation_batch_items
				where batch_id = :batchId
				""")
				.bind("batchId", batchId)
				.map((row, metadata) -> row.get("recommendation_item_id", UUID.class))
				.all()
				.collectList();
	}

	private LocalRecommendationResponse toResponse(
			Batch batch,
			String providerLocationId,
			String displayName,
			List<StoredItem> foods,
			List<StoredItem> places,
			String message
	) {
		return new LocalRecommendationResponse(
				batch.id(),
				providerLocationId,
				displayName,
				toItems(batch.id(), RecommendationCategory.food, foods),
				toItems(batch.id(), RecommendationCategory.place, places),
				foods.size() >= CATEGORY_SIZE && places.size() >= CATEGORY_SIZE,
				message,
				batch.createdAt()
		);
	}

	private List<LocalRecommendationItem> toItems(UUID batchId, RecommendationCategory category, List<StoredItem> items) {
		var result = new ArrayList<LocalRecommendationItem>();
		for (int index = 0; index < Math.min(CATEGORY_SIZE, items.size()); index++) {
			var item = items.get(index);
			result.add(new LocalRecommendationItem(
					item.id(),
					category.name(),
					index + 1,
					item.name(),
					item.description(),
					item.imageUrl(),
					item.imageAlt(),
					item.sourceTitle(),
					item.sourceUrl(),
					batchId
			));
		}
		return result;
	}

	private static StoredItem mapStored(Row row) {
		return new StoredItem(
				row.get("id", UUID.class),
				row.get("name", String.class),
				row.get("description", String.class),
				row.get("image_url", String.class),
				row.get("image_alt", String.class),
				row.get("source_title", String.class),
				row.get("source_url", String.class)
		);
	}

	private static String normalizeLocationId(String providerLocationId) {
		if (providerLocationId == null || providerLocationId.isBlank()) {
			throw new IllegalArgumentException("providerLocationId is required");
		}
		return providerLocationId.trim();
	}

	private static String normalizeDisplayName(String displayName) {
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("displayName is required");
		}
		return displayName.trim();
	}

	private static String bounded(String value, int maxLength) {
		var normalized = value == null || value.isBlank() ? "暂无说明" : value.trim();
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}

	private static boolean validUrl(String value) {
		try {
			var uri = URI.create(value);
			return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
		} catch (RuntimeException error) {
			return false;
		}
	}

	private static String fallbackImageUrl() {
		return "https://images.unsplash.com/photo-1548919973-5cef591cdbc9?auto=format&fit=crop&w=640&q=80";
	}

	private static String fingerprint(String providerLocationId, RecommendationCategory category, String name) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			var text = providerLocationId + "|" + category.name() + "|" + (name == null ? "" : name.trim().toLowerCase());
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	private record StoredItem(
			UUID id,
			String name,
			String description,
			String imageUrl,
			String imageAlt,
			String sourceTitle,
			String sourceUrl
	) {
	}

	private record Batch(UUID id, Instant createdAt) {
	}
}
