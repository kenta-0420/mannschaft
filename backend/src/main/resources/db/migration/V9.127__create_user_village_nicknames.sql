-- F17.1 Phase 1: ユーザー村ニックネームテーブル
-- village_id NULL = 全村共通（Phase 1 デフォルト） / 値あり = Phase 2 の村別上書き
-- village_id への FK は張らない（NULL 許容＋原則1 徹底）
-- user_id への FK も張らない（原則1）

CREATE TABLE user_village_nicknames (
    id                       BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    user_id                  BIGINT UNSIGNED NOT NULL                                COMMENT 'ユーザーID（FK 張らない）',
    village_id               BINARY(16)      NULL                                    COMMENT 'NULL=全村共通（Phase 1）/ 値=村別上書き（Phase 2）',
    nickname                 VARCHAR(40)     NOT NULL                                COMMENT '村ニックネーム（プラットフォーム全体で一意）',
    avatar_r2_key            VARCHAR(255)    NULL                                    COMMENT 'アバター（NULL なら共通アバター）',
    bio                      VARCHAR(500)    NULL                                    COMMENT '村内プロフィール一言',
    last_changed_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)   COMMENT 'スロットリング用',
    change_count_this_month  BIGINT UNSIGNED NOT NULL DEFAULT 0                      COMMENT '月内変更回数（ロールバック式）',
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- (user_id, NULL) は 1 件 / (user_id, village_id) も村ごと 1 件
    UNIQUE KEY uk_uvn_user_village (user_id, village_id),
    -- プラットフォーム全体でニックネーム一意（攻撃シナリオ防止）
    UNIQUE KEY uk_uvn_nickname (nickname),
    KEY idx_uvn_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ユーザーの村ニックネーム（F17.1）';
