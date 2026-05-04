-- F00.5 メンバーシップ基盤再設計 Phase 2: positions テーブル作成
--
-- 設計書: docs/features/F00.5_membership_basis.md §5.3
--
-- チーム/組織ごとの役職カタログ。役職は権限を持たない（権限は user_roles + permissions が制御）。
--
-- 制約・インデックス:
--   - uq_positions_scope_name: scope 内で name が一意（スコープ間では重複可）
--   - idx_positions_scope: 役職一覧の検索高速化
--
-- 加えて、V60.002 で先行作成した member_positions に対し、本テーブル作成後に
-- positions への FK を ALTER TABLE で追加する。

CREATE TABLE positions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type ENUM('ORGANIZATION','TEAM') NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_positions_scope_name UNIQUE (scope_type, scope_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_positions_scope ON positions (scope_type, scope_id, deleted_at, sort_order);

-- V60.002 で未張りだった member_positions.position_id への FK をここで追加する。
-- ON DELETE RESTRICT: 役職マスタは履歴参照中のため物理削除不可。
-- 論理削除（positions.deleted_at）で対応する。
ALTER TABLE member_positions
    ADD CONSTRAINT fk_member_positions_position
        FOREIGN KEY (position_id) REFERENCES positions (id) ON DELETE RESTRICT;
