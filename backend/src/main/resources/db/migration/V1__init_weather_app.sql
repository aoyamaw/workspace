create schema if not exists weather_app;
create extension if not exists pgcrypto;

create table if not exists weather_app.app_users (
    id uuid primary key default gen_random_uuid(),
    identity_type text not null check (identity_type in ('anonymous', 'registered')),
    external_subject text,
    display_name text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    constraint app_users_subject_unique unique (identity_type, external_subject)
);

create table if not exists weather_app.favorite_cities (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references weather_app.app_users(id) on delete cascade,
    provider_location_id text not null,
    display_name text not null,
    country_code text,
    latitude numeric(9, 6),
    longitude numeric(9, 6),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint favorite_cities_owner_location_unique unique (owner_id, provider_location_id)
);

create table if not exists weather_app.weather_query_history (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid references weather_app.app_users(id) on delete set null,
    query_text text not null,
    resolved_location_id text,
    resolved_display_name text,
    result_status text not null check (result_status in ('resolved', 'ambiguous', 'unsupported', 'failed')),
    created_at timestamptz not null default now()
);

create table if not exists weather_app.weather_cache (
    provider_location_id text primary key,
    display_name text not null,
    payload jsonb not null,
    source_name text not null,
    observed_at timestamptz not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists weather_app.ai_conversations (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references weather_app.app_users(id) on delete cascade,
    related_location_id text,
    user_message text not null,
    assistant_message text not null,
    weather_context jsonb,
    created_at timestamptz not null default now()
);

create table if not exists weather_app.notification_subscriptions (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references weather_app.app_users(id) on delete cascade,
    favorite_city_id uuid not null references weather_app.favorite_cities(id) on delete cascade,
    channel text not null check (channel in ('web_push', 'in_app')),
    permission_status text not null check (permission_status in ('default', 'granted', 'denied', 'unsupported')),
    push_endpoint text,
    push_p256dh text,
    push_auth text,
    daily_summary_enabled boolean not null default false,
    severe_weather_enabled boolean not null default true,
    cooldown_minutes integer not null default 60 check (cooldown_minutes >= 0),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notification_subscriptions_endpoint_required check (
        channel <> 'web_push'
        or permission_status <> 'granted'
        or push_endpoint is not null
    )
);

create index if not exists favorite_cities_owner_idx on weather_app.favorite_cities(owner_id);
create index if not exists weather_query_history_owner_created_idx on weather_app.weather_query_history(owner_id, created_at desc);
create index if not exists weather_cache_expires_idx on weather_app.weather_cache(expires_at);
create index if not exists ai_conversations_owner_created_idx on weather_app.ai_conversations(owner_id, created_at desc);
create index if not exists notification_subscriptions_owner_idx on weather_app.notification_subscriptions(owner_id);
create index if not exists notification_subscriptions_city_idx on weather_app.notification_subscriptions(favorite_city_id);
