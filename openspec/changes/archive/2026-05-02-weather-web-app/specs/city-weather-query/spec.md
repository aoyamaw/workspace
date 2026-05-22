## ADDED Requirements

### Requirement: City weather search
The system SHALL allow users to search weather information by entering a city name.

#### Scenario: User searches a valid city
- **WHEN** the user submits a valid city name
- **THEN** the system MUST display current weather information for the resolved city

#### Scenario: User searches an ambiguous city name
- **WHEN** the user submits a city name that maps to multiple locations
- **THEN** the system MUST ask the user to choose from matching locations before showing weather data

#### Scenario: User searches an unsupported city
- **WHEN** the user submits a city that cannot be resolved or is not covered by the weather provider
- **THEN** the system MUST show a clear unsupported-city message without fabricating weather data

### Requirement: Core weather indicators
The system SHALL display core weather indicators for a resolved city, including temperature, humidity, wind speed, wind direction when available, weather condition, precipitation probability when available, and data update time.

#### Scenario: Weather result is available
- **WHEN** weather data is returned for a city
- **THEN** the system MUST display all available core indicators with units and update time

#### Scenario: Optional indicator is unavailable
- **WHEN** the weather provider does not return an optional indicator
- **THEN** the system MUST omit that indicator or mark it as unavailable instead of inventing a value

### Requirement: Forecast range
The system SHALL provide current weather and multi-day forecast information when supported by the weather provider.

#### Scenario: User views city details
- **WHEN** the user opens the weather detail view for a resolved city
- **THEN** the system MUST show current conditions and the available forecast range

### Requirement: Weather data freshness
The system SHALL communicate whether displayed weather data is fresh enough for user decisions.

#### Scenario: Data is within freshness threshold
- **WHEN** displayed weather data was updated within the configured freshness threshold
- **THEN** the system MUST present it as current data

#### Scenario: Data is stale
- **WHEN** displayed weather data exceeds the configured freshness threshold
- **THEN** the system MUST mark the data as stale and attempt to refresh it

### Requirement: Weather query error handling
The system SHALL handle weather query failures with actionable user feedback.

#### Scenario: Weather provider request fails
- **WHEN** the weather provider request fails
- **THEN** the system MUST show a retry option and explain that weather data is temporarily unavailable

#### Scenario: Network is unavailable
- **WHEN** the user's network connection is unavailable
- **THEN** the system MUST show a network error state and preserve any previously loaded useful data when available
