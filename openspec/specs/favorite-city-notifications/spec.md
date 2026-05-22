# favorite-city-notifications Specification

## Purpose
TBD - created by archiving change weather-web-app. Update Purpose after archive.
## Requirements
### Requirement: Favorite city management
The system SHALL allow users to add, view, and remove favorite cities.

#### Scenario: User favorites a city
- **WHEN** the user selects the favorite action on a resolved city
- **THEN** the system MUST save that city to the user's favorites

#### Scenario: User removes a favorite city
- **WHEN** the user removes a city from favorites
- **THEN** the system MUST remove that city from the user's favorites list

#### Scenario: User opens favorites
- **WHEN** the user opens the favorites view
- **THEN** the system MUST display saved favorite cities and their latest available weather summaries

### Requirement: Favorite city persistence
The system SHALL persist favorite cities through the backend and PostgreSQL so they remain available across page refreshes, user sessions, and supported devices.

#### Scenario: User returns to the application
- **WHEN** the user reopens the application after saving favorite cities
- **THEN** the system MUST restore the saved favorite cities

#### Scenario: User accesses favorites from another supported device
- **WHEN** the same identified user opens the application from another supported device
- **THEN** the system MUST load the user's saved favorite cities from PostgreSQL

### Requirement: User data ownership
The system SHALL associate favorite cities, notification subscriptions, and AI conversation records with an identified user or an explicit anonymous-session identity.

#### Scenario: Identified user stores personal data
- **WHEN** a user saves favorites, enables notifications, or asks the AI assistant a question
- **THEN** the system MUST store the resulting data with that user's identifier

#### Scenario: Anonymous session is supported
- **WHEN** the application allows use without login
- **THEN** the system MUST assign an anonymous-session identity and keep its saved data separate from other users

#### Scenario: User identity is unavailable
- **WHEN** the system cannot determine a user or anonymous-session identity
- **THEN** the system MUST prevent persistent favorite, notification, and AI history writes instead of storing ownerless user data

### Requirement: Notification permission
The system SHALL request notification permission before enabling weather push notifications.

#### Scenario: User enables notifications
- **WHEN** the user chooses to enable weather notifications
- **THEN** the system MUST request browser or platform notification permission before sending notifications

#### Scenario: User denies notification permission
- **WHEN** the user denies notification permission
- **THEN** the system MUST keep notifications disabled and explain how to enable them later

### Requirement: Weather push subscriptions
The system SHALL allow users to subscribe to weather push notifications for favorite cities.

#### Scenario: User subscribes to a favorite city
- **WHEN** the user enables push notifications for a favorite city
- **THEN** the system MUST register a notification subscription for that city and persist the subscription in PostgreSQL

#### Scenario: User unsubscribes from a favorite city
- **WHEN** the user disables push notifications for a favorite city
- **THEN** the system MUST deactivate the persisted subscription and stop sending notifications for that city

### Requirement: Notification delivery channels
The system SHALL distinguish browser Web Push delivery from in-app realtime status updates.

#### Scenario: Browser push is enabled
- **WHEN** a user grants browser notification permission and subscribes to a favorite city
- **THEN** the system MUST store the browser push endpoint and use it for background weather notifications when supported

#### Scenario: User is actively using the app
- **WHEN** the user has the Web application open
- **THEN** the system MAY use SSE, WebSocket, or another Reactor Netty-backed realtime channel to update notification status in the interface

#### Scenario: Browser push is unavailable
- **WHEN** browser Web Push is unsupported, blocked, or missing required HTTPS/service worker capability
- **THEN** the system MUST keep background push disabled and continue to support in-app weather updates when possible

### Requirement: Notification content
The system SHALL send useful weather notifications for subscribed favorite cities.

#### Scenario: Severe weather is detected
- **WHEN** severe weather or a configured alert condition is detected for a subscribed city
- **THEN** the system MUST send a notification containing the city, condition, severity when available, and relevant time

#### Scenario: Daily summary is scheduled
- **WHEN** a daily weather summary schedule is due for a subscribed city
- **THEN** the system MUST send a concise forecast summary for that city

### Requirement: Notification rate limiting
The system SHALL limit weather notification frequency to avoid excessive notifications.

#### Scenario: Multiple updates occur rapidly
- **WHEN** multiple weather updates occur for the same city within the configured notification cooldown window
- **THEN** the system MUST consolidate or suppress duplicate notifications

