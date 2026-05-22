## ADDED Requirements

### Requirement: Main weather dashboard
The system SHALL provide a main dashboard where users can search cities, view weather results, access favorites, and open the AI assistant.

#### Scenario: User opens the application
- **WHEN** the user opens the Web application
- **THEN** the system MUST show the city search entry point, weather result area, favorites access, and AI assistant access

### Requirement: Responsive layout
The system SHALL provide a responsive layout for desktop and mobile screens.

#### Scenario: User opens the app on mobile
- **WHEN** the viewport is a mobile-sized screen
- **THEN** the system MUST keep search, weather information, favorites, and assistant controls usable without horizontal scrolling

#### Scenario: User opens the app on desktop
- **WHEN** the viewport is a desktop-sized screen
- **THEN** the system MUST present weather details, favorites, and assistant interactions in an efficient layout

### Requirement: Loading and empty states
The system SHALL provide clear loading, empty, and first-use states.

#### Scenario: Weather search is loading
- **WHEN** a weather search request is in progress
- **THEN** the system MUST show a loading state in the weather result area

#### Scenario: No city has been searched
- **WHEN** the user has not searched any city and has no selected favorite city
- **THEN** the system MUST show a useful empty state that invites the user to search for a city

### Requirement: Accessibility basics
The system SHALL support basic keyboard and screen reader accessibility for core interactions.

#### Scenario: User navigates by keyboard
- **WHEN** the user uses keyboard navigation
- **THEN** the system MUST allow access to search, favorite actions, notification controls, and AI assistant input

#### Scenario: User uses assistive technology
- **WHEN** weather information or errors are displayed
- **THEN** the system MUST expose meaningful labels and status text to assistive technology

### Requirement: Unit preferences
The system SHALL support at least metric units for weather values and allow future extension to additional unit systems.

#### Scenario: Weather values are displayed
- **WHEN** temperature, humidity, wind speed, or precipitation values are displayed
- **THEN** the system MUST show the corresponding units
