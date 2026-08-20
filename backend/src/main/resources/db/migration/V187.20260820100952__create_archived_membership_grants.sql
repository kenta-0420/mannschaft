-- F14.3 住民ライフイベント（逝去・転出）アーカイブ Phase 1: 復元用テーブル新設
--
-- 設計書: docs/features/F14.3_resident_life_events.md §5.3 / §14 M-2
--
-- §9.3 でアーカイブ時に user_roles / user_permission_groups 行を物理削除する。
-- 復元（§9.4）のために「何を持っていたか」を退避する表。
--
-- 所有ドメインは role（§5.3.1）。membership_id への FK は張らない
-- （クロスドメイン FK 禁止。CLAUDE.md アーキテクチャ思想 1）。
-- 新規テーブルのため UUIDv7 主キー（アーキテクチャ思想 6）。

CREATE TABLE archived_membership_grants (
    id BINARY(16) NOT NULL,
    membership_id BIGINT UNSIGNED NOT NULL,
    archive_generation INT NOT NULL,
    grant_type VARCHAR(20) NOT NULL,
    grant_ref_id BIGINT UNSIGNED NOT NULL,
    granted_by BIGINT UNSIGNED NULL,
    revoked_at DATETIME(3) NOT NULL,
    restored_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_archived_grants_membership
    ON archived_membership_grants (membership_id, archive_generation, restored_at);
