package com.example.weatherapp.notifications;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Component
public class InAppNotificationBroker {

	// 应用内通知的临时消息总线：NotificationEventService 发布，Controller 的 SSE 流订阅。
	private final Sinks.Many<UserNotificationBatch> sink = Sinks.many().multicast().directBestEffort();

	public void publish(UUID ownerId, List<NotificationEventResponse> events) {
		if (ownerId == null || events == null || events.isEmpty()) {
			return;
		}
		sink.tryEmitNext(new UserNotificationBatch(ownerId, events));
	}

	public Flux<List<NotificationEventResponse>> stream(UUID ownerId) {
		// 每 25 秒发送一个空列表作为心跳，防止浏览器或代理长时间无数据后断开连接。
		return sink.asFlux()
				.filter(batch -> batch.ownerId().equals(ownerId))
				.map(UserNotificationBatch::events)
				.mergeWith(Flux.interval(Duration.ofSeconds(25)).map(tick -> List.of()));
	}

	private record UserNotificationBatch(UUID ownerId, List<NotificationEventResponse> events) {
	}
}
