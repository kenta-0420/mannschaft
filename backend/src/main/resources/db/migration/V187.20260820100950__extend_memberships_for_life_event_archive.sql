-- F14.3 住民ライフイベント（逝去・転出）アーカイブ Phase 1: memberships 拡張
--
-- 設計書: docs/features/F14.3_resident_life_events.md §5.2 / §14 M-1（M-1a〜M-1e 統合）
--
-- 【2リリース分割の撤回について】
-- 設計書 §14.0 はローリングデプロイ中の新旧併存事故を避けるため、本 migration を
-- R1（列追加のみ）/ R2（LEGACY バックフィル + 対称 CHECK）の 2 リリースに分ける前提だった。
-- 本戦役では §14.0 に追記のうえこの分割を撤回し、1 本の migration に統合する
-- （根拠は同設計書 §14.0 追記を参照。要旨: 本番に実データが無く、まだ稼働している
-- 「古い版」自体が存在しないため、事故の成立条件そのものが揃わない）。
--
-- memberships へ列を 7 本追加する:
--   archived_at / archive_reason / archived_by / archive_expires_at
--   left_trigger / left_by / archive_generation
--
-- 既存の退会済み行（left_at NOT NULL）は left_trigger を LEGACY でバックフィルしてから
-- 対称 CHECK 制約 chk_memberships_left_trigger を追加する
-- （列追加 → バックフィル → 制約追加の順序を守る。§5.2.2.1）。
--
-- leave_reason の ENUM を DECEASED / RELOCATED の 2 値拡張する（§5.4）。
-- MODIFY COLUMN は列定義を丸ごと置き換えるため、現行定義（V60.001:31）を verbatim でコピーする。

-- ① 列を追加する（archive_generation 以外はすべて nullable）
ALTER TABLE memberships
    ADD COLUMN archived_at DATETIME(3) NULL,
    ADD COLUMN archive_reason VARCHAR(10) NULL,
    ADD COLUMN archived_by BIGINT UNSIGNED NULL,
    ADD COLUMN archive_expires_at DATETIME(3) NULL,
    ADD COLUMN left_trigger VARCHAR(24) NULL,
    ADD COLUMN left_by BIGINT UNSIGNED NULL,
    ADD COLUMN archive_generation INT NOT NULL DEFAULT 0;

-- ② 既存の退会済み行を LEGACY でバックフィルする（§5.2.2.1）
UPDATE memberships SET left_trigger = 'LEGACY' WHERE left_at IS NOT NULL;

-- ③ 対称 CHECK 制約を追加する（left_at ⟺ left_trigger）
ALTER TABLE memberships
    ADD CONSTRAINT chk_memberships_left_trigger CHECK (
        (left_at IS NULL AND left_trigger IS NULL)
        OR (left_at IS NOT NULL AND left_trigger IS NOT NULL)
    );

-- ④ アーカイブ 3 列の一貫性 CHECK（archived_by は含めない。§5.2 の注記・§5.2.3.2 #1'）
ALTER TABLE memberships
    ADD CONSTRAINT chk_memberships_archive_consistency CHECK (
        (archived_at IS NULL AND archive_reason IS NULL AND archive_expires_at IS NULL)
        OR (archived_at IS NOT NULL AND archive_reason IS NOT NULL AND archive_expires_at IS NOT NULL)
    );

-- ⑤ leave_reason の ENUM 拡張（現行定義を丸ごと再宣言。V60.001:31 が verbatim のコピー元）
ALTER TABLE memberships
    MODIFY COLUMN leave_reason
        ENUM('SELF','REMOVED','GDPR','TRANSFER','OTHER','DECEASED','RELOCATED') NULL;

-- ⑥ アーカイブ抽出用インデックス（§9.6 の自動退会バッチが毎日フルスキャンしないため）
CREATE INDEX idx_memberships_archive_expiry
    ON memberships (archive_expires_at, left_at);
