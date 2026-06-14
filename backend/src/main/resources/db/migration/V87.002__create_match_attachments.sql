-- F08.10 / 01 §B.7 / 03 §C.7a: match_attachments（局面写真など match スコープの添付・盤上競技）。
--
-- 盤上競技（将棋/囲碁）の局面写真を保持する。既存添付基盤（presign 方式・bulletin_attachments と同方式・
-- SVG 除外・サイズ上限 10MB・IDOR 逆引き）の実装パターンを踏襲し、match スコープ（match_id 帰属確認）の
-- 添付として実装する（新規ストレージ機構は作らない）。
--
-- 原則準拠（CLAUDE.md・01 §A.4 / §B.7）:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6）。id は BINARY(16)。
--   - organization_id / deleted_at は【持たない】。テナント分離は親 matches で担保し、子は match_id
--     スコープでのみアクセスする二段アクセス（01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。
--   - match_id → matches(id) は同一 match ドメイン内ゆえ FK＋ON DELETE CASCADE 可（原則2）。
--     クロスドメイン FK は張らない（原則1）。
--
-- 採番（CLAUDE.md / feedback_flyway_version_sort_after_global_max）:
--   matches CREATE は V76 系。本 CREATE は origin/main 全体最大 major（V86）の次（V87 系・V87.001 の後）を採る。
--   ※マージ直前に origin/main の最大 Flyway 番号を再確認しリネームすること（並行 PR 衝突回避）。
--
-- 設計書: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.7
--   / 03_permissions_and_recording_modes.md §C.7a / sports/05_shogi.md §8.2

CREATE TABLE match_attachments (
    id                BINARY(16)         NOT NULL              COMMENT 'UUIDv7（原則6）',
    match_id          BINARY(16)         NOT NULL              COMMENT 'matches(id)（同一ドメイン → FK CASCADE）',
    file_key          VARCHAR(512)       NOT NULL              COMMENT 'R2 オブジェクトキー（server 採番・クライアント任意 key を信用しない）',
    original_filename VARCHAR(255)       NULL                  COMMENT '元ファイル名（表示用）',
    content_type      VARCHAR(128)       NOT NULL              COMMENT 'MIME（画像のみ・SVG 除外）',
    file_size         BIGINT             NOT NULL              COMMENT 'バイト数（上限 10MB）',
    created_by        BIGINT             NOT NULL              COMMENT 'アップロード者（user ドメイン ID 参照・FK なし）',
    created_at        DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_match_attachments_match (match_id, created_at),
    CONSTRAINT fk_match_attachments_match FOREIGN KEY (match_id)
        REFERENCES matches (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.10/01 §B.7 局面写真など match スコープ添付（盤上競技・テナント分離は親 matches）';
