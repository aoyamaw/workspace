package com.example.weatherapp.api;

import com.example.weatherapp.notifications.NotificationEvaluationResponse;
import com.example.weatherapp.notifications.NotificationEventResponse;
import com.example.weatherapp.notifications.NotificationEventService;
import com.example.weatherapp.notifications.InAppNotificationBroker;
import com.example.weatherapp.notifications.NotificationSubscriptionRequest;
import com.example.weatherapp.notifications.NotificationSubscriptionResponse;
import com.example.weatherapp.notifications.NotificationSubscriptionService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class NotificationSubscriptionController {

	private final NotificationSubscriptionService notificationSubscriptionService;
	private final NotificationEventService notificationEventService;
	private final InAppNotificationBroker inAppNotificationBroker;

	public NotificationSubscriptionController(
			NotificationSubscriptionService notificationSubscriptionService,
			NotificationEventService notificationEventService,
			InAppNotificationBroker inAppNotificationBroker
	) {
		this.notificationSubscriptionService = notificationSubscriptionService;
		this.notificationEventService = notificationEventService;
		this.inAppNotificationBroker = inAppNotificationBroker;
	}

	@GetMapping("/api/notifications/subscriptions")
	Flux<NotificationSubscriptionResponse> list(@RequestHeader("X-User-Id") UUID userId) {
		// 查询当前用户已经开启的通知订阅。
		return notificationSubscriptionService.list(userId);
	}

	@PostMapping("/api/notifications/subscriptions")
	Mono<NotificationSubscriptionResponse> save(
			@RequestHeader("X-User-Id") UUID userId,
			@RequestBody NotificationSubscriptionRequest request
	) {
		// 新增或更新某个收藏城市的通知设置。
		return notificationSubscriptionService.upsert(userId, request);
	}

	@DeleteMapping("/api/notifications/subscriptions/{subscriptionId}")
	Mono<Void> deactivate(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID subscriptionId) {
		// 这里是软删除：把 active 设为 false，而不是直接删除历史记录。
		return notificationSubscriptionService.deactivate(userId, subscriptionId);
	}

	@GetMapping("/api/notifications/subscriptions/events")
	Flux<NotificationEventResponse> events(@RequestHeader("X-User-Id") UUID userId) {
		// 读取最近生成的通知记录。
		return notificationEventService.listEvents(userId);
	}

	@DeleteMapping("/api/notifications/events/{eventId}")
	Mono<Void> deleteEvent(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID eventId) {
		// 删除单条提醒记录时校验 owner_id，只允许用户删除自己的消息。
		return notificationEventService.deleteEvent(userId, eventId);
	}

	@PostMapping("/api/notifications/subscriptions/evaluate")
	Mono<NotificationEvaluationResponse> evaluate(@RequestHeader("X-User-Id") UUID userId) {
		// 手动触发一次通知评估，方便前端按钮演示。
		return notificationEventService.evaluate(userId);
	}

	@PostMapping("/api/notifications/subscriptions/test")
	Mono<NotificationEvaluationResponse> testPush(@RequestHeader("X-User-Id") UUID userId) {
		return notificationEventService.testPush(userId);
	}

	@GetMapping("/api/notifications/subscriptions/stream")
	Flux<ServerSentEvent<List<NotificationSubscriptionResponse>>> stream(
			@RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
			@RequestParam(value = "userId", required = false) UUID queryUserId
	) {
		// Server-Sent Events 长连接：前端不用轮询，也能持续拿到订阅状态变化。
		var ownerId = headerUserId != null ? headerUserId : queryUserId;
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
		return Flux.interval(Duration.ZERO, Duration.ofSeconds(15))
				.flatMap(tick -> notificationSubscriptionService.list(ownerId).collectList())
				.map(data -> ServerSentEvent.<List<NotificationSubscriptionResponse>>builder()
						.event("notification-subscriptions")
						.data(data)
						.build());
	}

	@GetMapping("/api/notifications/events/stream")
	Flux<ServerSentEvent<List<NotificationEventResponse>>> eventStream(
			@RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
			@RequestParam(value = "userId", required = false) UUID queryUserId
	) {
		// 应用内通知实时流：后端生成通知后会推送到当前用户的前端页面。
		var ownerId = headerUserId != null ? headerUserId : queryUserId;
		if (ownerId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
		}
		return inAppNotificationBroker.stream(ownerId)
				.map(events -> ServerSentEvent.<List<NotificationEventResponse>>builder()
						.event("notification-events")
						.data(events)
						.build());
	}
}
