-- 練習試合・親善試合は、組織に属さずチームへ直接帰属できる。
-- V181 wave2 適用済み環境も NULL 許容へ収束させるため、この後続 migration は残す。
ALTER TABLE matches
    MODIFY COLUMN organization_id BIGINT UNSIGNED NULL COMMENT '組織スコープ（単独チーム試合は NULL・FK なし）';
