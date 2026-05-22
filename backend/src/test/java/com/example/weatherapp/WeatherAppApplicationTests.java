package com.example.weatherapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeatherAppApplicationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anonymousSessionAndFavoriteLifecycleWorks() throws Exception {
        var userId = createAnonymousUser();
        var locationId = "test-location-" + UUID.randomUUID();
        insertWeatherCache(locationId, "Test City");

        var favorite = saveFavorite(userId, locationId, "Test City, Example");

        webTestClient.get()
                .uri("/api/favorites")
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].providerLocationId").isEqualTo(locationId)
                .jsonPath("$[0].latestTemperatureCelsius").isEqualTo(24.0)
                .jsonPath("$[0].latestCondition").isEqualTo("雨")
                .jsonPath("$[0].weatherStatus").isEqualTo("available");

        webTestClient.delete()
                .uri("/api/favorites/{favoriteId}", favorite.get("id").asText())
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void duplicateFavoriteUpdatesExistingCityName() throws Exception {
        var userId = createAnonymousUser();
        var locationId = "duplicate-location-" + UUID.randomUUID();

        saveFavorite(userId, locationId, "Old City Name");
        var updated = saveFavorite(userId, locationId, "New City Name");

        assertThat(updated.get("displayName").asText()).isEqualTo("New City Name");

        webTestClient.get()
                .uri("/api/favorites")
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].displayName").isEqualTo("New City Name");
    }

    @Test
    void assistantPersistsBoundaryResponseWithoutWeatherContext() {
        var userId = createAnonymousUser();

        webTestClient.post()
                .uri("/api/assistant/messages")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of("message", "What is the current weather now?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.usedWeatherContext").isEqualTo(false)
                .jsonPath("$.persisted").isEqualTo(true)
                .jsonPath("$.answer").value(answer ->
                        assertThat((String) answer).contains("还没有这个城市的实时天气数据"));

        var count = databaseClient
                .sql("select count(*)::int as count from weather_app.ai_conversations where owner_id = :ownerId")
                .bind("ownerId", userId)
                .map(row -> row.get("count", Integer.class))
                .one()
                .block();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void assistantProvidesWeatherAndAttractionGuidance() throws Exception {
        var userId = createAnonymousUser();
        var locationId = "ai-location-" + UUID.randomUUID();
        insertWeatherCache(locationId, "Cool City");
        var weather = objectMapper.readTree("""
                {
                  "location": {
                    "id": "%s",
                    "name": "Cool City",
                    "displayName": "Cool City, Example",
                    "country": "Example",
                    "countryCode": "EX",
                    "admin1": "Example",
                    "timezone": "Asia/Shanghai",
                    "latitude": 31.2,
                    "longitude": 121.4
                  },
                  "current": {
                    "time": "2026-05-04T10:00:00Z",
                    "temperatureCelsius": 26.0,
                    "humidityPercent": 60.0,
                    "windSpeedKmh": 8.0,
                    "windDirectionDegrees": 180.0,
                    "precipitationMm": 0.0,
                    "weatherCode": 1,
                    "condition": "多云"
                  },
                  "forecast": [],
                  "sourceName": "test",
                  "timezone": "Asia/Shanghai",
                  "retrievedAt": "2026-05-04T10:00:00Z"
                }
                """.formatted(locationId));

        webTestClient.post()
                .uri("/api/assistant/messages")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of(
                        "message", "附近有什么适合今天去的景点？",
                        "weatherContext", weather))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.answer").value(answer ->
                        assertThat((String) answer).contains("适合安排"));
    }

    @Test
    void notificationEvaluationGeneratesEventsAndSuppressesDuplicates() throws Exception {
        var userId = createAnonymousUser();
        var locationId = "notification-location-" + UUID.randomUUID();
        insertWeatherCache(locationId, "Storm City");
        var favorite = saveFavorite(userId, locationId, "Storm City");
        var favoriteId = favorite.get("id").asText();

        webTestClient.post()
                .uri("/api/notifications/subscriptions")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of(
                        "favoriteCityId", favoriteId,
                        "channel", "in_app",
                        "permissionStatus", "unsupported",
                        "dailySummaryEnabled", true,
                        "severeWeatherEnabled", true,
                        "cooldownMinutes", 60))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post()
                .uri("/api/notifications/subscriptions/evaluate")
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generated").isEqualTo(2)
                .jsonPath("$.suppressed").isEqualTo(0);

        webTestClient.post()
                .uri("/api/notifications/subscriptions/evaluate")
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generated").isEqualTo(0)
                .jsonPath("$.suppressed").isEqualTo(2);

        var eventId = databaseClient
                .sql("""
                        select id
                        from weather_app.notification_events
                        where owner_id = :ownerId
                        limit 1
                        """)
                .bind("ownerId", userId)
                .map(row -> row.get("id", UUID.class))
                .one()
                .block();

        webTestClient.delete()
                .uri("/api/notifications/events/{eventId}", eventId)
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk();

        var remainingEvents = databaseClient
                .sql("select count(*)::int as count from weather_app.notification_events where owner_id = :ownerId")
                .bind("ownerId", userId)
                .map(row -> row.get("count", Integer.class))
                .one()
                .block();
        assertThat(remainingEvents).isEqualTo(1);
    }

    @Test
    void qweatherSunnyCodeGeneratesNormalSummaryInsteadOfThunderstormRisk() throws Exception {
        var userId = createAnonymousUser();
        var locationId = "qweather-sunny-" + UUID.randomUUID();
        insertWeatherCache(locationId, "Sunny City", """
                {
                  "current": {
                    "temperature_2m": 24.0,
                    "weather_code": 100,
                    "wind_speed_10m": 8.0,
                    "precipitation": 0.0
                  },
                  "daily": {
                    "time": ["2026-05-02"],
                    "temperature_2m_max": [28.0],
                    "temperature_2m_min": [20.0],
                    "precipitation_probability_max": [10],
                    "weather_code": [100]
                  }
                }
                """, "QWeather");
        var favorite = saveFavorite(userId, locationId, "Sunny City");

        webTestClient.post()
                .uri("/api/notifications/subscriptions")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of(
                        "favoriteCityId", favorite.get("id").asText(),
                        "channel", "in_app",
                        "permissionStatus", "unsupported",
                        "dailySummaryEnabled", true,
                        "severeWeatherEnabled", true,
                        "cooldownMinutes", 60))
                .exchange()
                .expectStatus().isOk();

        webTestClient.post()
                .uri("/api/notifications/subscriptions/evaluate")
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.generated").isEqualTo(1)
                .jsonPath("$.suppressed").isEqualTo(0)
                .jsonPath("$.events[0].eventType").isEqualTo("daily_summary")
                .jsonPath("$.events[0].title").isEqualTo("Sunny City天气正常提醒");
    }

    @Test
    void validationAndOwnershipErrorsReturnExpectedStatuses() {
        var userId = createAnonymousUser();

        webTestClient.get()
                .uri("/api/weather/search?city=上")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ambiguous")
                .jsonPath("$.candidates[0].displayName").isEqualTo("上海, 上海市, 中国");

        webTestClient.post()
                .uri("/api/notifications/subscriptions")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of(
                        "favoriteCityId", UUID.randomUUID().toString(),
                        "channel", "in_app",
                        "permissionStatus", "unsupported"))
                .exchange()
                .expectStatus().isNotFound();
    }

    private UUID createAnonymousUser() {
        var body = webTestClient.post()
                .uri("/api/sessions/anonymous")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        try {
            return UUID.fromString(objectMapper.readTree(body).get("userId").asText());
        } catch (Exception exception) {
            throw new AssertionError("Anonymous session response was not valid JSON", exception);
        }
    }

    private JsonNode saveFavorite(UUID userId, String locationId, String displayName) throws Exception {
        var body = webTestClient.post()
                .uri("/api/favorites")
                .header("X-User-Id", userId.toString())
                .bodyValue(Map.of(
                        "providerLocationId", locationId,
                        "displayName", displayName,
                        "countryCode", "TS",
                        "latitude", 31.2,
                        "longitude", 121.4))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return objectMapper.readTree(body);
    }

    private void insertWeatherCache(String locationId, String displayName) {
        insertWeatherCache(locationId, displayName, """
                {
                  "current": {
                    "temperature_2m": 24.0,
                    "weather_code": 61
                  },
                  "daily": {
                    "time": ["2026-05-02"],
                    "temperature_2m_max": [36.0],
                    "temperature_2m_min": [20.0],
                    "precipitation_probability_max": [90],
                    "weather_code": [61]
                  }
                }
                """, "test");
    }

    private void insertWeatherCache(String locationId, String displayName, String payload, String sourceName) {
        databaseClient
                .sql("""
                        insert into weather_app.weather_cache (
                            provider_location_id,
                            display_name,
                            payload,
                            source_name,
                            observed_at,
                            expires_at,
                            updated_at
                        )
                        values (
                            :locationId,
                            :displayName,
                            cast(:payload as jsonb),
                            :sourceName,
                            :observedAt,
                            :expiresAt,
                            :updatedAt
                        )
                        on conflict (provider_location_id)
                        do update set
                            payload = excluded.payload,
                            observed_at = excluded.observed_at,
                            expires_at = excluded.expires_at,
                            updated_at = excluded.updated_at
                        """)
                .bind("locationId", locationId)
                .bind("displayName", displayName)
                .bind("payload", payload)
                .bind("sourceName", sourceName)
                .bind("observedAt", Instant.now())
                .bind("expiresAt", Instant.now().plusSeconds(1800))
                .bind("updatedAt", Instant.now())
                .then()
                .block();
    }
}
