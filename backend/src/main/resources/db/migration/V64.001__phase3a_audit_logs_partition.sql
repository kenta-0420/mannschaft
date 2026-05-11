-- Phase 3-A: audit_logs 月次レンジパーティション導入
--
-- MySQL のレンジパーティションはパーティションキーが全 UNIQUE インデックス（PK 含む）に
-- 含まれている必要がある。現在の PK (id) を (id, created_at) に変更してパーティションを追加する。
--
-- 対象インデックス（V1.011 + V11.163 + V63.001 で追加済み）
--   PK:                      (id)
--   idx_audit_logs_user_id   (user_id)
--   idx_audit_logs_event_type(event_type)
--   idx_audit_logs_created_at(created_at)
--   idx_al_target_user_id    (target_user_id)
--   idx_al_team_id           (team_id)
--   idx_al_organization_id   (organization_id)
--   idx_al_user_event        (user_id, event_type)
--   idx_al_session_hash      (session_hash)
--   idx_al_team_created      (team_id, created_at)
--   idx_al_org_created       (organization_id, created_at)
--   idx_al_user_created      (user_id, created_at DESC)  ← V63.001 で追加

-- 1. 既存インデックスを全て DROP して PK を (id, created_at) に変更
--    ※ AUTO_INCREMENT は (id, created_at) の先頭列 id で維持される
ALTER TABLE audit_logs
    DROP PRIMARY KEY,
    DROP INDEX idx_audit_logs_user_id,
    DROP INDEX idx_audit_logs_event_type,
    DROP INDEX idx_audit_logs_created_at,
    DROP INDEX idx_al_target_user_id,
    DROP INDEX idx_al_team_id,
    DROP INDEX idx_al_organization_id,
    DROP INDEX idx_al_user_event,
    DROP INDEX idx_al_session_hash,
    DROP INDEX idx_al_team_created,
    DROP INDEX idx_al_org_created,
    DROP INDEX idx_al_user_created,
    ADD PRIMARY KEY (id, created_at),
    ADD INDEX idx_audit_logs_user_id      (user_id),
    ADD INDEX idx_audit_logs_event_type   (event_type),
    ADD INDEX idx_audit_logs_created_at   (created_at),
    ADD INDEX idx_al_target_user_id       (target_user_id),
    ADD INDEX idx_al_team_id              (team_id),
    ADD INDEX idx_al_organization_id      (organization_id),
    ADD INDEX idx_al_user_event           (user_id, event_type),
    ADD INDEX idx_al_session_hash         (session_hash),
    ADD INDEX idx_al_team_created         (team_id, created_at),
    ADD INDEX idx_al_org_created          (organization_id, created_at),
    ADD INDEX idx_al_user_created         (user_id, created_at DESC);

-- 2. 月次レンジパーティション追加（2024年1月〜2029年12月 + p_future）
--    TO_DAYS() は MySQL の組み込み関数。created_at が DATE/DATETIME 型であることが前提。
ALTER TABLE audit_logs
    PARTITION BY RANGE (TO_DAYS(created_at)) (
        PARTITION p_2024_01 VALUES LESS THAN (TO_DAYS('2024-02-01')),
        PARTITION p_2024_02 VALUES LESS THAN (TO_DAYS('2024-03-01')),
        PARTITION p_2024_03 VALUES LESS THAN (TO_DAYS('2024-04-01')),
        PARTITION p_2024_04 VALUES LESS THAN (TO_DAYS('2024-05-01')),
        PARTITION p_2024_05 VALUES LESS THAN (TO_DAYS('2024-06-01')),
        PARTITION p_2024_06 VALUES LESS THAN (TO_DAYS('2024-07-01')),
        PARTITION p_2024_07 VALUES LESS THAN (TO_DAYS('2024-08-01')),
        PARTITION p_2024_08 VALUES LESS THAN (TO_DAYS('2024-09-01')),
        PARTITION p_2024_09 VALUES LESS THAN (TO_DAYS('2024-10-01')),
        PARTITION p_2024_10 VALUES LESS THAN (TO_DAYS('2024-11-01')),
        PARTITION p_2024_11 VALUES LESS THAN (TO_DAYS('2024-12-01')),
        PARTITION p_2024_12 VALUES LESS THAN (TO_DAYS('2025-01-01')),
        PARTITION p_2025_01 VALUES LESS THAN (TO_DAYS('2025-02-01')),
        PARTITION p_2025_02 VALUES LESS THAN (TO_DAYS('2025-03-01')),
        PARTITION p_2025_03 VALUES LESS THAN (TO_DAYS('2025-04-01')),
        PARTITION p_2025_04 VALUES LESS THAN (TO_DAYS('2025-05-01')),
        PARTITION p_2025_05 VALUES LESS THAN (TO_DAYS('2025-06-01')),
        PARTITION p_2025_06 VALUES LESS THAN (TO_DAYS('2025-07-01')),
        PARTITION p_2025_07 VALUES LESS THAN (TO_DAYS('2025-08-01')),
        PARTITION p_2025_08 VALUES LESS THAN (TO_DAYS('2025-09-01')),
        PARTITION p_2025_09 VALUES LESS THAN (TO_DAYS('2025-10-01')),
        PARTITION p_2025_10 VALUES LESS THAN (TO_DAYS('2025-11-01')),
        PARTITION p_2025_11 VALUES LESS THAN (TO_DAYS('2025-12-01')),
        PARTITION p_2025_12 VALUES LESS THAN (TO_DAYS('2026-01-01')),
        PARTITION p_2026_01 VALUES LESS THAN (TO_DAYS('2026-02-01')),
        PARTITION p_2026_02 VALUES LESS THAN (TO_DAYS('2026-03-01')),
        PARTITION p_2026_03 VALUES LESS THAN (TO_DAYS('2026-04-01')),
        PARTITION p_2026_04 VALUES LESS THAN (TO_DAYS('2026-05-01')),
        PARTITION p_2026_05 VALUES LESS THAN (TO_DAYS('2026-06-01')),
        PARTITION p_2026_06 VALUES LESS THAN (TO_DAYS('2026-07-01')),
        PARTITION p_2026_07 VALUES LESS THAN (TO_DAYS('2026-08-01')),
        PARTITION p_2026_08 VALUES LESS THAN (TO_DAYS('2026-09-01')),
        PARTITION p_2026_09 VALUES LESS THAN (TO_DAYS('2026-10-01')),
        PARTITION p_2026_10 VALUES LESS THAN (TO_DAYS('2026-11-01')),
        PARTITION p_2026_11 VALUES LESS THAN (TO_DAYS('2026-12-01')),
        PARTITION p_2026_12 VALUES LESS THAN (TO_DAYS('2027-01-01')),
        PARTITION p_2027_01 VALUES LESS THAN (TO_DAYS('2027-02-01')),
        PARTITION p_2027_02 VALUES LESS THAN (TO_DAYS('2027-03-01')),
        PARTITION p_2027_03 VALUES LESS THAN (TO_DAYS('2027-04-01')),
        PARTITION p_2027_04 VALUES LESS THAN (TO_DAYS('2027-05-01')),
        PARTITION p_2027_05 VALUES LESS THAN (TO_DAYS('2027-06-01')),
        PARTITION p_2027_06 VALUES LESS THAN (TO_DAYS('2027-07-01')),
        PARTITION p_2027_07 VALUES LESS THAN (TO_DAYS('2027-08-01')),
        PARTITION p_2027_08 VALUES LESS THAN (TO_DAYS('2027-09-01')),
        PARTITION p_2027_09 VALUES LESS THAN (TO_DAYS('2027-10-01')),
        PARTITION p_2027_10 VALUES LESS THAN (TO_DAYS('2027-11-01')),
        PARTITION p_2027_11 VALUES LESS THAN (TO_DAYS('2027-12-01')),
        PARTITION p_2027_12 VALUES LESS THAN (TO_DAYS('2028-01-01')),
        PARTITION p_2028_01 VALUES LESS THAN (TO_DAYS('2028-02-01')),
        PARTITION p_2028_02 VALUES LESS THAN (TO_DAYS('2028-03-01')),
        PARTITION p_2028_03 VALUES LESS THAN (TO_DAYS('2028-04-01')),
        PARTITION p_2028_04 VALUES LESS THAN (TO_DAYS('2028-05-01')),
        PARTITION p_2028_05 VALUES LESS THAN (TO_DAYS('2028-06-01')),
        PARTITION p_2028_06 VALUES LESS THAN (TO_DAYS('2028-07-01')),
        PARTITION p_2028_07 VALUES LESS THAN (TO_DAYS('2028-08-01')),
        PARTITION p_2028_08 VALUES LESS THAN (TO_DAYS('2028-09-01')),
        PARTITION p_2028_09 VALUES LESS THAN (TO_DAYS('2028-10-01')),
        PARTITION p_2028_10 VALUES LESS THAN (TO_DAYS('2028-11-01')),
        PARTITION p_2028_11 VALUES LESS THAN (TO_DAYS('2028-12-01')),
        PARTITION p_2028_12 VALUES LESS THAN (TO_DAYS('2029-01-01')),
        PARTITION p_2029_01 VALUES LESS THAN (TO_DAYS('2029-02-01')),
        PARTITION p_2029_02 VALUES LESS THAN (TO_DAYS('2029-03-01')),
        PARTITION p_2029_03 VALUES LESS THAN (TO_DAYS('2029-04-01')),
        PARTITION p_2029_04 VALUES LESS THAN (TO_DAYS('2029-05-01')),
        PARTITION p_2029_05 VALUES LESS THAN (TO_DAYS('2029-06-01')),
        PARTITION p_2029_06 VALUES LESS THAN (TO_DAYS('2029-07-01')),
        PARTITION p_2029_07 VALUES LESS THAN (TO_DAYS('2029-08-01')),
        PARTITION p_2029_08 VALUES LESS THAN (TO_DAYS('2029-09-01')),
        PARTITION p_2029_09 VALUES LESS THAN (TO_DAYS('2029-10-01')),
        PARTITION p_2029_10 VALUES LESS THAN (TO_DAYS('2029-11-01')),
        PARTITION p_2029_11 VALUES LESS THAN (TO_DAYS('2029-12-01')),
        PARTITION p_2029_12 VALUES LESS THAN (TO_DAYS('2030-01-01')),
        PARTITION p_future  VALUES LESS THAN MAXVALUE
    );
