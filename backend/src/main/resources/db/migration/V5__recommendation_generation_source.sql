alter table weather_app.local_recommendation_items
    add column if not exists generated_by text not null default 'legacy';

create index if not exists local_recommendation_items_generation_idx
    on weather_app.local_recommendation_items(provider_location_id, category, generated_by, created_at desc);
