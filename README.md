# Smart Weather App

Spec-driven smart weather forecast web application.

## Structure

- `openspec/changes/weather-web-app`: approved implementation constraints and tasks
- `frontend`: Vue 3 + Vite + TypeScript client
- `backend`: Spring Boot WebFlux service

## Local Configuration

Copy `.env.example` to `.env` for local development values. The application stores project tables in the `weather_app` PostgreSQL schema by default.

The first implementation pass uses anonymous session identity. Persistent user-owned data must be written with either a login user ID or an anonymous session ID; ownerless favorite, notification, and AI history writes are not allowed.
