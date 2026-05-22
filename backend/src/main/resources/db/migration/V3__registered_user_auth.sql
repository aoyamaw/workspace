create table if not exists weather_app.registered_user_credentials (
    user_id uuid primary key references weather_app.app_users(id) on delete cascade,
    email text not null unique,
    password_hash text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists weather_app.auth_sessions (
    token_hash text primary key,
    user_id uuid not null references weather_app.app_users(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create index if not exists auth_sessions_user_idx
    on weather_app.auth_sessions(user_id, created_at desc);

create index if not exists auth_sessions_expires_idx
    on weather_app.auth_sessions(expires_at);
