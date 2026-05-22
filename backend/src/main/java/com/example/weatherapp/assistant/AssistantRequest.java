package com.example.weatherapp.assistant;

import com.example.weatherapp.weather.WeatherResult;

public record AssistantRequest(String message, WeatherResult weatherContext) {
}
