package com.example.weatherapp.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class WebPushService {

	// Web Push 服务：把后端生成的通知发送给浏览器，即使页面不在前台也能提醒。
	private final DatabaseClient databaseClient;
	private final ObjectMapper objectMapper;
	private final String publicKey;
	private final String privateKey;
	private final String subject;

	public WebPushService(
			DatabaseClient databaseClient,
			ObjectMapper objectMapper,
			@Value("${app.web-push.public-key:}") String publicKey,
			@Value("${app.web-push.private-key:}") String privateKey,
			@Value("${app.web-push.subject:mailto:weather-app@example.local}") String subject
	) {
		this.databaseClient = databaseClient;
		this.objectMapper = objectMapper;
		this.publicKey = publicKey == null ? "" : publicKey;
		this.privateKey = privateKey == null ? "" : privateKey;
		this.subject = subject;
	}

	public Mono<Void> send(UUID ownerId, List<NotificationEventResponse> events) {
		// 没有配置 VAPID 公私钥时直接跳过，项目仍然可以使用应用内通知。
		if (ownerId == null || events == null || events.isEmpty() || publicKey.isBlank() || privateKey.isBlank()) {
			return Mono.empty();
		}
		return activeWebPushSubscriptions(ownerId)
				.flatMap(subscription -> reactor.core.publisher.Flux.fromIterable(events)
						.flatMap(event -> sendOne(subscription, event)))
				.then();
	}

	private reactor.core.publisher.Flux<PushTarget> activeWebPushSubscriptions(UUID ownerId) {
		return databaseClient.sql("""
				select id, push_endpoint, push_p256dh, push_auth
				from weather_app.notification_subscriptions
				where owner_id = :ownerId
				  and active = true
				  and channel = 'web_push'
				  and permission_status = 'granted'
				  and push_endpoint is not null
				""")
				.bind("ownerId", ownerId)
				.map((row, metadata) -> new PushTarget(
						row.get("id", UUID.class),
						row.get("push_endpoint", String.class),
						row.get("push_p256dh", String.class),
						row.get("push_auth", String.class)
				))
				.all();
	}

	private Mono<Void> sendOne(PushTarget target, NotificationEventResponse event) {
		return Mono.fromCallable(() -> {
					// web-push 库是阻塞调用，所以外层会放到 boundedElastic 线程池执行。
					var payload = objectMapper.writeValueAsBytes(new PushPayload(event.title(), event.body(), event.eventType(), event.id().toString()));
					var notification = new Notification(
							target.endpoint(),
							target.p256dh(),
							target.auth(),
							payload
					);
					var pushService = new PushService(publicKey, privateKey, subject);
					HttpResponse response = pushService.send(notification);
					var status = response.getStatusLine().getStatusCode();
					if (status == 404 || status == 410) {
						// 404/410 表示浏览器订阅已失效，自动关闭这条订阅。
						deactivateSubscription(target.subscriptionId()).subscribe();
					}
					return status;
				})
				.subscribeOn(Schedulers.boundedElastic())
				.then()
				.onErrorResume(error -> Mono.empty());
	}

	private Mono<Void> deactivateSubscription(UUID subscriptionId) {
		return databaseClient.sql("""
				update weather_app.notification_subscriptions
				set active = false, updated_at = now()
				where id = :subscriptionId
				""")
				.bind("subscriptionId", subscriptionId)
				.then();
	}

	private record PushTarget(UUID subscriptionId, String endpoint, String p256dh, String auth) {
	}

	private record PushPayload(String title, String body, String type, String eventId) {
	}
}
