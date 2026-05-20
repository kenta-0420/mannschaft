-- k6 負荷テスト用ユーザー初期化スクリプト
-- 実行: docker exec -i mannschaft-mysql mysql -uroot -proot mannschaft < k6/seed-test-user.sql
-- ※ 開発環境専用。本番環境では絶対に実行しないこと。

-- パスワードは BCrypt ハッシュ（平文: "Password1!"）
-- Spring Security の BCryptPasswordEncoder(10) で生成
--
-- 注意: last_name / first_name は AES-256-GCM 暗号化カラム（TEXT 型）のため、
-- 直接挿入する場合は空文字列を使用する（V9.053 以降の仕様）。
-- 認証はメール + パスワードハッシュのみで行われるため、k6 テストの目的では影響なし。

INSERT INTO users (
    email,
    password_hash,
    last_name,
    first_name,
    display_name,
    is_searchable,
    locale,
    timezone,
    status,
    encryption_key_version,
    reporting_restricted,
    created_at,
    updated_at
) VALUES (
    'k6test@example.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVKhBgO.uK',  -- Password1!
    '',
    '',
    'k6テストユーザー',
    0,
    'ja',
    'Asia/Tokyo',
    'ACTIVE',
    1,
    0,
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    status = 'ACTIVE',
    updated_at = NOW();
