INSERT INTO users (
    id, email, last_name, first_name, display_name,
    is_searchable, locale, timezone, status,
    encryption_key_version, reporting_restricted,
    created_at, updated_at
) VALUES (
    0, 'deleted@system.internal', '', '', '退会済みユーザー',
    0, 'ja', 'Asia/Tokyo', 'ARCHIVED',
    1, 0,
    NOW(), NOW()
) ON DUPLICATE KEY UPDATE id = id;
