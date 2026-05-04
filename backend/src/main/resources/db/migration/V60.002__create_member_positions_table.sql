-- F00.5 メンバーシップ基盤再設計 Phase 2: member_positions テーブル作成
--
-- 設計書: docs/features/F00.5_membership_basis.md §5.2
--
-- memberships と positions の N:N 中間表。期間付き役職兼任（兼任可）。
--
-- 【重要】Flyway バージョン番号は設計書 §12.3 のタスク順序に従い:
--   V60.001 = memberships, V60.002 = member_positions, V60.003 = positions
-- としている。本マイグレーションでは positions テーブルが未存在のため
-- positions への FK は本ファイルでは張らず、V60.003 にて positions テーブル
-- 作成後に ALTER TABLE で FK 追加を行う。
--
-- 本ファイルで張る FK:
--   - fk_member_positions_membership: ON DELETE CASCADE
--     メンバーシップが GDPR 物理削除されると役職履歴も自動削除（個人情報のため）
--   - fk_member_positions_assigned_by: ON DELETE SET NULL
--     役職を付与した管理者の物理削除に追随
--
-- V60.003 で追加される FK:
--   - fk_member_positions_position: ON DELETE RESTRICT
--     役職マスタは履歴参照中のため物理削除不可。論理削除（positions.deleted_at）で対応
--
-- CHECK 制約: chk_member_positions_period（期間の逆転防止）

CREATE TABLE member_positions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    membership_id BIGINT UNSIGNED NOT NULL,
    position_id BIGINT UNSIGNED NOT NULL,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at DATETIME NULL,
    assigned_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_member_positions_membership FOREIGN KEY (membership_id) REFERENCES memberships (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_positions_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_member_positions_period CHECK (
        ended_at IS NULL OR ended_at >= started_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
