package com.example.weatherapp.api;

import com.example.weatherapp.assistant.AssistantRequest;
import com.example.weatherapp.assistant.AssistantResponse;
import com.example.weatherapp.assistant.AssistantService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class AssistantController {

	private final AssistantService assistantService;

	public AssistantController(AssistantService assistantService) {
		this.assistantService = assistantService;
	}

	@PostMapping("/api/assistant/messages")
	Mono<AssistantResponse> answer(@RequestHeader("X-User-Id") UUID userId, @RequestBody AssistantRequest request) {
		// 天气助手会把用户问题和当前天气上下文交给 AssistantService 生成回答。
		return assistantService.answer(userId, request);
	}
}
