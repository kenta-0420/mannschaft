-- F03.4.1 機能E: 予約メニューマスタ reservation_menus を新設する。
--
-- チームが提供するサービスメニュー（例:「カット 60分」「整体 90分」）のマスタ。
-- 所要時間（30分の倍数・30〜480）を持ち、F03.4.3 の予約グループが必要枠数
-- （duration_minutes / 30）を自動確保する起点となる。料金は表示のみ（決済しない・マスター御裁可）。
--
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6（新規テーブル・UuidV7Entity 継承）。
-- team_id / created_by は teams / users ドメインへのクロスドメイン参照なので FK なし・INDEX のみ
-- （アーキ原則1）。既存 reservation_lines.team_id（V3.060）/ reservation_slots.created_by（V3.061）
-- と同型の BIGINT UNSIGNED に統一する（設計書 §3）。
--
-- 論理削除（deleted_at）: 削除済みメニューは新規予約の起点にできないが、既存予約グループ
-- （F03.4.3 第二弾 reservations.menu_id）からの名前解決用に行は物理削除しない（§3 備考）。
--
-- CHECK 制約は DB 最終防御・Service 層が一次検証（400 = RESERVATION_034）。
-- MySQL 8.0.16+ で CHECK は実 enforce される。
--
-- ⚠ 採番: origin/main 現行最大 major は V140（観測時点 2026-07-05）。同弾並行の F03.4.2 とは
--   異なる major を採る（E=V141、F03.4.2 隊は V142 予定 — feedback_migration_version_collision）。
--   マージ直前に origin/main の最大 major を再確認し、衝突があればリネームすること。

CREATE TABLE reservation_menus (
    id               BINARY(16)      NOT NULL,
    team_id          BIGINT UNSIGNED NOT NULL,
    -- メニュー名（例:「カット」「整体60分コース」）。同一チーム内の重複は許可（§3 備考）。
    name             VARCHAR(100)    NOT NULL,
    -- 所要時間（分）。30の倍数・30〜480。上限480 = 8時間16枠（F03.4.3 グループ最大枠数と整合）。
    duration_minutes INT             NOT NULL,
    -- 表示用料金（決済しない）。NULL = 料金表示なし。
    price            DECIMAL(10,2)   NULL,
    -- メニュー説明（会員向け表示）。
    description      VARCHAR(500)    NULL,
    -- 表示順（1〜20・チーム内。Service 層で範囲検証。一意は強制しない — 三段ソートで安定）。
    display_order    INT             NOT NULL DEFAULT 1,
    -- 有効/無効。FALSE は会員向け一覧・予約起点から除外（既存予約は影響なし）。
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE,
    -- 作成者 user_id（users テーブルへのクロスドメイン参照・FK なし）。
    created_by       BIGINT UNSIGNED NULL,
    created_at       DATETIME(6)     NOT NULL,
    updated_at       DATETIME(6)     NOT NULL,
    -- 論理削除（@SQLRestriction("deleted_at IS NULL")）。
    deleted_at       DATETIME(6)     NULL,

    PRIMARY KEY (id),
    -- チーム別メニュー一覧（表示順）。
    INDEX idx_rm_team (team_id, display_order),
    -- 有効メニュー検索。
    INDEX idx_rm_team_active (team_id, is_active),
    -- 所要時間の DB 最終防御（Service 一次検証 = RESERVATION_034）。
    CONSTRAINT chk_rm_duration CHECK (duration_minutes % 30 = 0 AND duration_minutes BETWEEN 30 AND 480)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='予約メニューマスタ（F03.4.1 機能E・チーム単位・所要時間30分倍数・料金表示のみ・上限20件）';
