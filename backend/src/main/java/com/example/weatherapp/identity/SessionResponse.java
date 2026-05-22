package com.example.weatherapp.identity;

import java.util.UUID;

public record SessionResponse(UUID userId, String email, String displayName, String identityType) {
}
