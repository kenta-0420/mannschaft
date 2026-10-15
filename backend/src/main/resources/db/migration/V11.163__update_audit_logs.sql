-- audit_logs テーブルを仕様書（F10.3）に合わせて更新する
-- 追加カラム: target_user_id / team_id / organization_id / session_hash
-- カラム名変更: details → metadata
-- イベント種別カラム拡張: VARCHAR(50) → VARCHAR(100)
-- FK制約・インデックス追加

ALTER TABLE audit_logs
    MODIFY COLUMN event_type VARCHAR(100) NOT NULL,
    CHANGE COLUMN details metadata JSON NULL,
    ADD COLUMN target_user_id   BIGINT UNSIGNED NULL AFTER user_id,
    ADD COLUMN team_id          BIGINT UNSIGNED NULL AFTER target_user_id,
    ADD COLUMN organization_id  BIGINT UNSIGNED NULL AFTER team_id,
    ADD COLUMN session_hash     VARCHAR(64)     NULL AFTER user_agent,
    ADD CONSTRAINT fk_al_user        FOREIGN KEY (user_id)          REFERENCES users(id)         ON DELETE SET NULL,
    ADD CONSTRAINT fk_al_target_user FOREIGN KEY (target_user_id)   REFERENCES users(id)         ON DELETE SET NULL,
    ADD CONSTRAINT fk_al_team        FOREIGN KEY (team_id)          REFERENCES teams(id)         ON DELETE SET NULL,
    ADD CONSTRAINT fk_al_org         FOREIGN KEY (organization_id)  REFERENCES organizations(id) ON DELETE SET NULL,
    ADD INDEX idx_al_target_user_id  (target_user_id),
    ADD INDEX idx_al_team_id         (team_id),
    ADD INDEX idx_al_organization_id (organization_id),
    ADD INDEX idx_al_user_event      (user_id, event_type),
    ADD INDEX idx_al_session_hash    (session_hash),
    ADD INDEX idx_al_team_created    (team_id, created_at),
    ADD INDEX idx_al_org_created     (organization_id, created_at);
