create table if not exists weather_app.local_recommendation_items (
    id uuid primary key default gen_random_uuid(),
    provider_location_id text not null,
    display_name text not null,
    category text not null check (category in ('food', 'place')),
    name text not null,
    description text not null,
    image_url text not null,
    image_alt text not null,
    source_title text not null,
    source_url text not null,
    content_fingerprint text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint local_recommendation_items_unique unique (
        provider_location_id, category, content_fingerprint
    )
);

create table if not exists weather_app.local_recommendation_batches (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid references weather_app.app_users(id) on delete set null,
    provider_location_id text not null,
    display_name text not null,
    batch_type text not null check (batch_type in ('initial', 'refresh')),
    message text,
    created_at timestamptz not null default now()
);

create table if not exists weather_app.local_recommendation_batch_items (
    batch_id uuid not null references weather_app.local_recommendation_batches(id) on delete cascade,
    recommendation_item_id uuid not null references weather_app.local_recommendation_items(id) on delete cascade,
    category text not null check (category in ('food', 'place')),
    rank integer not null check (rank between 1 and 5),
    primary key (batch_id, recommendation_item_id),
    constraint local_recommendation_batch_rank_unique unique (batch_id, category, rank)
);

create table if not exists weather_app.local_recommendation_views (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references weather_app.app_users(id) on delete cascade,
    provider_location_id text not null,
    recommendation_item_id uuid not null references weather_app.local_recommendation_items(id) on delete cascade,
    viewed_at timestamptz not null default now(),
    constraint local_recommendation_views_unique unique (
        owner_id, provider_location_id, recommendation_item_id
    )
);

create index if not exists local_recommendation_items_location_category_idx
    on weather_app.local_recommendation_items(provider_location_id, category, created_at desc);

create index if not exists local_recommendation_batches_location_idx
    on weather_app.local_recommendation_batches(provider_location_id, created_at desc);

create index if not exists local_recommendation_views_owner_location_idx
    on weather_app.local_recommendation_views(owner_id, provider_location_id, viewed_at desc);
