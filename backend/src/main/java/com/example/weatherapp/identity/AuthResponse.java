package com.example.weatherapp.identity;

import java.time.Instant;
import java.util.UUID;

public record AuthResponse(
		UUID userId,
		String email,
		String displayName,
		String identityType,
		String token,
		Instant expiresAt
) {
}
