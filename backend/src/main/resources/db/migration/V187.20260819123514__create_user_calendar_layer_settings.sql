-- F03.19 統合カレンダービュー: ユーザー×レイヤーの表示設定（色・表示可否）
-- 設計書: docs/features/F03.19_unified_calendar_view.md §3
--
-- 採用方針（CLAUDE.md アーキテクチャ思想）:
--   - 主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - クロスドメイン FK なし（原則1）。user_id / scope_id は論理参照でインデックスのみ。
--   - scope_type='PERSONAL' の行は scope_id=0（センチネル）。NULL 混在でユニークが壊れるのを避ける。
--   - 設定が無い＝自動色（決定的ハッシュ・§3.3）。「未設定」を表すために行を作る必要はない。

CREATE TABLE user_calendar_layer_settings (
    id             BINARY(16)   NOT NULL          COMMENT 'UUIDv7 主キー',

    user_id        BIGINT       NOT NULL          COMMENT '設定の所有者（論理参照・FK なし。本人以外は読み書き不可）',
    scope_type     VARCHAR(20)  NOT NULL          COMMENT 'レイヤー種別（PERSONAL / TEAM / ORGANIZATION）',
    scope_id       BIGINT       NOT NULL DEFAULT 0
                                                  COMMENT 'レイヤー対象ID（TEAM=teams.id / ORGANIZATION=organizations.id / PERSONAL=0 センチネル。論理参照・FK なし）',

    color          CHAR(7)      NULL              COMMENT 'ユーザー指定色（#RRGGBB 大文字。NULL=自動色にフォールバック）',
    hidden         BOOLEAN      NOT NULL DEFAULT FALSE
                                                  COMMENT '既定で非表示にするか（フィルタの初期状態。true でもレイヤー一覧には必ず出る＝P3）',

    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                  ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_user_calendar_layer (user_id, scope_type, scope_id)
        COMMENT '1ユーザー1レイヤーにつき1行（upsert キー・AC-05）。user_id が左端のため findByUserId の索引も兼ねる'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.19 ユーザー×カレンダーレイヤーの表示設定（色・表示可否）';
