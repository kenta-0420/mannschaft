-- F05.2 Phase 11 第三陣 3-B
-- ADMIN による強制スキップ時の理由を保存するカラムを追加する。
-- self-skip (受信者本人による) も同じカラムを使う（reason=NULL なら旧来の self-skip 互換）。

ALTER TABLE circulation_recipients
    ADD COLUMN skip_reason VARCHAR(255) NULL COMMENT 'スキップ理由（ADMIN強制スキップ時のみ必須）',
    ADD COLUMN skipped_by BIGINT UNSIGNED NULL COMMENT 'スキップ操作実行者の user_id（NULL なら受信者本人のセルフスキップ）',
    ADD COLUMN skipped_at DATETIME NULL COMMENT 'スキップ実行日時';
