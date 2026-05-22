package com.example.weatherapp.notifications;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class NotificationScheduler {

	// 定时任务：按配置间隔扫描所有 active 的通知订阅并生成提醒。
	private final DatabaseClient databaseClient;
	private final NotificationEventService notificationEventService;
	private final boolean enabled;

	public NotificationScheduler(
			DatabaseClient databaseClient,
			NotificationEventService notificationEventService,
			@Value("${app.notifications.scheduler.enabled:true}") boolean enabled
	) {
		this.databaseClient = databaseClient;
		this.notificationEventService = notificationEventService;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelayString = "${app.notifications.scheduler.delay-ms:60000}", initialDelayString = "${app.notifications.scheduler.initial-delay-ms:60000}")
	public void evaluateActiveSubscriptions() {
		if (!enabled) {
			return;
		}
		// flatMap 的并发数为 4，避免一次性并发处理过多用户。
		activeOwners()
				.flatMap(notificationEventService::evaluate, 4)
				.subscribe();
	}

	private Flux<UUID> activeOwners() {
		return databaseClient.sql("""
				select distinct owner_id
				from weather_app.notification_subscriptions
				where active = true
				""")
				.map(row -> row.get("owner_id", UUID.class))
				.all();
	}
}
