package com.example.weatherapp.identity;

public record AuthRequest(String email, String password, String displayName) {
}
