-- F16.1 Phase 1: 組織スコープのモジュール有効化状態管理テーブル
-- 任務説明: organization_enabled_modules テーブルを新設し、
--           team_enabled_modules と同様に組織単位でのモジュールON/OFF管理を実現する。
-- 注意: organization_id / module_id はクロスドメインFKのため、
--       アーキテクチャ原則1に従いFKはfk_oem_orgのみ同一ドメイン内として許容する。
--       module_definitions も template ドメインのため同一ドメイン扱いとして許容する。
CREATE TABLE organization_enabled_modules (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    organization_id BIGINT UNSIGNED NOT NULL,
    module_id       BIGINT UNSIGNED NOT NULL,
    is_enabled      TINYINT(1) NOT NULL DEFAULT 1,
    enabled_at      DATETIME NULL,
    disabled_at     DATETIME NULL,
    enabled_by      BIGINT UNSIGNED NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_org_module (organization_id, module_id),
    INDEX idx_org_enabled (organization_id, is_enabled),
    CONSTRAINT fk_oem_org    FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_oem_module FOREIGN KEY (module_id) REFERENCES module_definitions(id),
    CONSTRAINT fk_oem_user   FOREIGN KEY (enabled_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
