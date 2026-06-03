-- F08.7.1 / 05 試合メンバー表（項目拡充）: エントリーテンプレのベンチ役員（テンプレ ID 配下）。
--
-- ベンチ役員も「メンバー表テンプレ」として保存し、apply-template 時に match_roster_staff へ複製する。
-- 構造は match_roster_staff と対応させる。
--
-- 【型方針＝案A（着手時 SHOW COLUMNS / DDL で実体確認済）】
--   親 tournament_entry_templates.id の実 DB 物理型は CHAR(36)（V9.123 / V9.124 が CHAR(36) で宣言。
--   JPA Entity は @UuidGenerator UUID だが、実テーブルは CHAR(36)）。
--   よって本テーブルの FK 列 template_id は「参照先 PK の実体物理型」に合わせて CHAR(36) とする。
--   既存テーブルの型は変更しない（別 Issue #1231 で管理）。
--   既存 tournament_entry_template_members.template_id も CHAR(36) で FK CASCADE が成立している（V9.124）。
--
-- 原則準拠:
--   - 新規テーブルゆえ主キーは UUIDv7（原則6・UuidV7Entity 継承）。id は BINARY(16)。
--   - template_id は同一 tournament ドメイン内（template の子）への参照ゆえ FK / CASCADE 許可（原則2）。
--   - user_id は user ドメインへの ID 参照（クロスドメイン FK なし／原則1・NULL 可）。
--
-- 設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §8.4 / §8.6

CREATE TABLE tournament_entry_template_staff (
    id          BINARY(16)   NOT NULL COMMENT 'UUIDv7（原則6）',
    template_id CHAR(36)     NOT NULL COMMENT '親 tournament_entry_templates.id（実体型 CHAR(36)・案A）',
    role        VARCHAR(32)  NOT NULL COMMENT '役職（監督/コーチ/トレーナー 等）',
    name        VARCHAR(128) NOT NULL COMMENT '氏名（アプリ未登録者も記載可）',
    user_id     BIGINT       NULL     COMMENT 'user ドメインへの ID 参照（FK なし／原則1・NULL 可）',
    sort_order  SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '並び順',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    KEY idx_template_staff_template (template_id, sort_order),
    -- 同一 tournament ドメイン内（template の子）なので CASCADE 可（原則2）。
    -- FK 列型は参照先 PK の実体型 CHAR(36) に一致させている（案A・上記参照）。
    CONSTRAINT fk_template_staff_template FOREIGN KEY (template_id)
        REFERENCES tournament_entry_templates (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.7.1/05 エントリーテンプレのベンチ役員（template 配下・CASCADE）';
