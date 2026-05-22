package com.example.weatherapp.api;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppHealthController {

	private final String databaseSchema;

	public AppHealthController(@Value("${app.database.schema:weather_app}") String databaseSchema) {
		this.databaseSchema = databaseSchema;
	}

	@GetMapping("/api/health")
	AppHealth health() {
		return new AppHealth("ok", databaseSchema, "anonymous-or-registered", Instant.now());
	}

	public record AppHealth(String status, String databaseSchema, String identityMode, Instant checkedAt) {
	}
}
