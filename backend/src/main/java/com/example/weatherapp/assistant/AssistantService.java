package com.example.weatherapp.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class AssistantService {

	// AI 天气助手服务：生成回答，并把对话记录保存到数据库。
	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;
	private final WebClient aiClient;
	private final String aiApiKey;
	private final String aiModel;

	public AssistantService(
			DatabaseClient databaseClient,
			ObjectMapper objectMapper,
			WebClient.Builder webClientBuilder,
			@Value("${app.ai.base-url:}") String aiBaseUrl,
			@Value("${app.ai.api-key:}") String aiApiKey,
			@Value("${app.ai.model:gpt-5.5}") String aiModel
	) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
		this.aiClient = webClientBuilder.baseUrl(normalizeAiBaseUrl(aiBaseUrl)).build();
		this.aiApiKey = aiApiKey == null ? "" : aiApiKey;
		this.aiModel = aiModel == null || aiModel.isBlank() ? "gpt-5.5" : aiModel;
	}

	public Mono<AssistantResponse> answer(UUID ownerId, AssistantRequest request) {
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
		if (request.message() == null || request.message().isBlank()) {
			throw new IllegalArgumentException("message is required");
		}
		var usedContext = request.weatherContext() != null;
		var createdAt = Instant.now();
		// 先生成回答，再尝试保存记录；保存失败不会影响前端拿到回答。
		return generateAnswer(request)
				.flatMap(answer -> persist(ownerId, request, answer)
						.thenReturn(new AssistantResponse(answer, usedContext, true, createdAt))
						.onErrorReturn(new AssistantResponse(answer, usedContext, false, createdAt)));
	}

	private Mono<Void> persist(UUID ownerId, AssistantRequest request, String answer) {
		// 保存用户问题、助手回答和当时的天气上下文，便于后续查看历史记录。
		var locationId = request.weatherContext() == null ? null : request.weatherContext().location().id();
		var weatherContext = toJson(request.weatherContext());
		var spec = databaseClient.sql("""
				insert into weather_app.ai_conversations (
				    owner_id, related_location_id, user_message, assistant_message, weather_context
				)
				values (:ownerId, :locationId, :userMessage, :assistantMessage, cast(:weatherContext as jsonb))
				""")
				.bind("ownerId", ownerId)
				.bind("userMessage", request.message().trim())
				.bind("assistantMessage", answer);
		spec = locationId == null ? spec.bindNull("locationId", String.class) : spec.bind("locationId", locationId);
		spec = weatherContext == null ? spec.bindNull("weatherContext", String.class) : spec.bind("weatherContext", weatherContext);
		return spec.then();
	}

	private Mono<String> generateAnswer(AssistantRequest request) {
		// 没有配置 AI_API_KEY 时，使用本地规则生成一个可演示的回答。
		var fallback = request.weatherContext() == null ? boundaryAnswer() : contextualAnswer(request);
		if (aiApiKey.isBlank()) {
			return Mono.just(fallback);
		}
		return aiClient.post()
				.uri("/responses")
				.header("Authorization", "Bearer " + aiApiKey)
				.header("Content-Type", "application/json")
				.accept(MediaType.TEXT_EVENT_STREAM)
				.bodyValue(Map.of(
						"model", aiModel,
						"instructions", instructions(),
						"input", responseInput(request),
						"reasoning", Map.of("effort", "low"),
						"text", Map.of(
								"format", Map.of("type", "text"),
								"verbosity", "low"
						),
						"max_output_tokens", 700,
						"stream", true,
						"store", false
				))
				.retrieve()
				.bodyToMono(String.class)
				.map(this::extractAssistantAnswer)
				.filter(answer -> answer != null && !answer.isBlank())
				.onErrorResume(error -> {
					log.warn("AI assistant request failed, using fallback answer: {}", error.getMessage());
					return Mono.just(fallback);
				})
				.defaultIfEmpty(fallback);
	}

	private String extractAssistantAnswer(String payload) {
		if (payload == null || payload.isBlank()) {
			return "";
		}
		var trimmed = payload.trim();
		if (trimmed.startsWith("data:") || trimmed.contains("\ndata:") || trimmed.contains("\r\ndata:")) {
			return extractStreamingAnswer(trimmed);
		}
		return extractJsonAnswer(trimmed);
	}

	private String extractStreamingAnswer(String payload) {
		var answer = new StringBuilder();
		for (var line : payload.split("\\R")) {
			var trimmed = line.trim();
			if (!trimmed.startsWith("data:")) {
				continue;
			}
			var data = trimmed.substring("data:".length()).trim();
			if (data.isBlank() || "[DONE]".equals(data)) {
				continue;
			}
			var chunk = readJson(data);
			if (chunk == null) {
				continue;
			}
			var content = chunk.path("choices").path(0).path("delta").path("content").asText("");
			if (content.isBlank()) {
				content = chunk.path("delta").asText("");
			}
			if (!content.isBlank()) {
				answer.append(content);
			}
		}
		return answer.toString().trim();
	}

	private String extractJsonAnswer(String payload) {
		var response = readJson(payload);
		if (response == null) {
			return "";
		}
		return response.path("choices").path(0).path("message").path("content").asText("").trim();
	}

	private JsonNode readJson(String payload) {
		try {
			return objectMapper.readTree(payload);
		} catch (JsonProcessingException error) {
			log.warn("Failed to parse AI response JSON: {}", error.getMessage());
			return null;
		}
	}

	private String instructions() {
		return """
				你是智能天气应用里的天气助手。你必须直接输出最终答案，不要只进行推理，也不要返回空内容。只根据用户提供的天气上下文和常识给出建议；不要编造实时天气、灾害预警或官方通知。涉及极端天气、安全决策、预警和应急事项时，提醒用户以官方气象和应急渠道为准。回答简洁、具体、中文优先。
				""";
	}

	private String responseInput(AssistantRequest request) {
		if (request.weatherContext() == null) {
			return "当前没有实时天气上下文。请提示用户先搜索城市，只能提供通用天气建议。\n\n用户问题：" + request.message().trim();
		}
		return "当前天气上下文 JSON: " + toJson(request.weatherContext()) + "\n\n用户问题：" + request.message().trim();
	}

	private List<Map<String, String>> messages(AssistantRequest request) {
		// 拼装发给 AI 模型的消息：系统约束 + 天气上下文 + 用户问题。
		var messages = new ArrayList<Map<String, String>>();
		messages.add(Map.of(
				"role", "system",
				"content", """
						你是智能天气应用里的天气助手。你必须直接输出最终答案，不要只进行推理，也不要返回空内容。只根据用户提供的天气上下文和常识给出建议；不要编造实时天气、灾害预警或官方通知。涉及极端天气、安全决策、预警和应急事项时，提醒用户以官方气象和应急渠道为准。回答简洁、具体、中文优先。
						"""
		));
		if (request.weatherContext() != null) {
			messages.add(Map.of(
					"role", "system",
					"content", "当前天气上下文 JSON: " + toJson(request.weatherContext())
			));
		} else {
			messages.add(Map.of(
					"role", "system",
					"content", "当前没有实时天气上下文。请提示用户先搜索城市，只能提供通用天气建议。"
			));
		}
		messages.add(Map.of("role", "user", "content", request.message().trim()));
		return messages;
	}

	private String contextualAnswer(AssistantRequest request) {
		var weather = request.weatherContext();
		var current = weather.current();
		var city = weather.location().displayName();
		var temperature = current.temperatureCelsius() == null ? "温度暂不可用" : Math.round(current.temperatureCelsius()) + "℃";
		var wind = current.windSpeedKmh() == null ? "风速暂不可用" : Math.round(current.windSpeedKmh()) + " km/h";
		var rain = current.precipitationMm() != null && current.precipitationMm() > 0;
		var message = request.message().toLowerCase();
		var advice = new StringBuilder();
		advice.append(city)
				.append("当前")
				.append(current.condition())
				.append("，气温")
				.append(temperature)
				.append("，风速")
				.append(wind)
				.append("。");
		if (rain || message.contains("雨") || message.contains("伞")) {
			advice.append("建议带伞，鞋包尽量选择防水材质。");
		} else if (current.temperatureCelsius() != null && current.temperatureCelsius() >= 30) {
			advice.append("高温时段减少暴晒，补水并选择轻薄透气衣物。");
		} else if (current.temperatureCelsius() != null && current.temperatureCelsius() <= 8) {
			advice.append("低温环境建议增加保暖层，注意颈部和手部保暖。");
		} else {
			advice.append("适合安排通勤、散步或轻量户外活动，出门前再看一次最新预报。");
		}
		advice.append(" 极端天气和安全决策请以官方气象与应急渠道为准。");
		return advice.toString();
	}

	private static String boundaryAnswer() {
		return "还没有这个城市的实时天气数据。请先搜索并选择城市，我可以结合当前天气回答穿衣、出行、降雨、风力和活动安排；涉及灾害预警时请以官方气象渠道为准。";
	}

	private String toJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException error) {
			return null;
		}
	}

	private static String normalizeAiBaseUrl(String aiBaseUrl) {
		if (aiBaseUrl == null || aiBaseUrl.isBlank()) {
			return "http://localhost";
		}
		var trimmed = aiBaseUrl.trim();
		if (trimmed.endsWith("/chat/completions")) {
			return trimmed.substring(0, trimmed.length() - "/chat/completions".length());
		}
		if (trimmed.endsWith("/v1")) {
			return trimmed;
		}
		if (trimmed.endsWith("/v1/")) {
			return trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.endsWith("/") ? trimmed + "v1" : trimmed + "/v1";
	}
}
