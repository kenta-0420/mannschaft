-- F08.10 / 01 §B.3: player_appearances（出場時間）。
--
-- 1 選手 1 行のサマリ。computed_minutes は全 in/out 区間の合計（再出場対応）。
-- match ドメイン内 → 親 matches へ CASCADE 可（原則2）。
--
-- 原則準拠（CLAUDE.md・01 §A.4/§B.3）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4）。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--   - player_user_id/owning_team_id はクロスドメイン ID 参照（原則1・FK なし）。
--   - UNIQUE(match_id, player_user_id): 登録選手は 1 試合 1 行（NULL=未登録は MySQL UNIQUE 重複扱いされず複数可）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.3
-- ※採番はマージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること。

CREATE TABLE player_appearances (
    id               BINARY(16)        NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id         BINARY(16)        NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    player_user_id   BIGINT            NULL                  COMMENT '選手（user ドメイン ID 参照・未登録は NULL・FK なし）',
    player_name      VARCHAR(128)      NULL                  COMMENT '未登録選手名',
    team_side        ENUM('HOME','AWAY') NOT NULL            COMMENT '所属サイド',
    is_starter       BOOLEAN           NOT NULL DEFAULT FALSE COMMENT '先発フラグ',
    position         VARCHAR(30)       NULL                  COMMENT 'ポジション（器は競技非依存・語彙は競技別＝サッカーは sports/01 §7 の GK/DF/MF/FW 等）',
    jersey_number    SMALLINT UNSIGNED NULL                  COMMENT '背番号（未登録選手の同一性キーの一部）',
    first_in_minute  SMALLINT UNSIGNED NULL                  COMMENT '最初の出場開始分（STARTER=0 / 初回 SUB_IN・代表値）',
    last_out_minute  SMALLINT UNSIGNED NULL                  COMMENT '最後の退場分（代表値）',
    computed_minutes SMALLINT UNSIGNED NULL                  COMMENT '自動算出出場分＝全 in/out 区間の合計（再出場対応）',
    owning_team_id   BIGINT            NOT NULL              COMMENT '自チーム編集権限の判定（team ドメイン ID 参照・FK なし）',
    created_at       DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_appearance_match_player (match_id, player_user_id),
    KEY idx_appearance_match (match_id, team_side),
    KEY idx_appearance_player (player_user_id),
    CONSTRAINT fk_appearance_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/01 出場時間（match ドメイン内・テナント分離は親 matches）';
