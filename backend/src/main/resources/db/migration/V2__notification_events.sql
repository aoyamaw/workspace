create table if not exists weather_app.notification_events (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null references weather_app.notification_subscriptions(id) on delete cascade,
    owner_id uuid not null references weather_app.app_users(id) on delete cascade,
    favorite_city_id uuid not null references weather_app.favorite_cities(id) on delete cascade,
    event_type text not null check (event_type in ('severe_weather', 'daily_summary', 'weather_change')),
    title text not null,
    body text not null,
    severity text,
    payload jsonb,
    delivery_status text not null default 'pending' check (delivery_status in ('pending', 'sent', 'suppressed', 'failed')),
    created_at timestamptz not null default now()
);

create index if not exists notification_events_subscription_created_idx
    on weather_app.notification_events(subscription_id, created_at desc);

create index if not exists notification_events_owner_created_idx
    on weather_app.notification_events(owner_id, created_at desc);

create index if not exists notification_events_type_created_idx
    on weather_app.notification_events(event_type, created_at desc);
