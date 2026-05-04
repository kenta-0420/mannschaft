-- F00.5 メンバーシップ基盤再設計 Phase 2: 部分 UNIQUE + 検索インデックス
--
-- 設計書: docs/features/F00.5_membership_basis.md §5.5
--
-- MySQL 8.0 は WHERE 条件付き UNIQUE をネイティブサポートしないため、生成列で実現する。
--
-- memberships:
--   - active_key（生成列）+ uq_memberships_active: 同一ユーザーが同一スコープに
--     アクティブな状態（left_at IS NULL）で 2 行存在しないことを保証
--   - uq_memberships_history: 履歴行も含めた強い UNIQUE（再加入時は joined_at が異なる）
--   - idx_memberships_scope / idx_memberships_user / idx_memberships_role_kind: 検索インデックス
--
-- member_positions:
--   - active_position_key（生成列）+ uq_member_positions_active: 現役役職は
--     (membership, position) ペアごとに最大 1 行
--   - uq_member_positions_period: 同一ペアで同一 started_at の重複防止
--   - idx_member_positions_membership / idx_member_positions_position: 検索インデックス

-- ========================================================================
-- memberships の部分 UNIQUE 用生成列 + UNIQUE 制約
-- ========================================================================

ALTER TABLE memberships
    ADD COLUMN active_key VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN left_at IS NULL
             THEN CONCAT(IFNULL(user_id,''), ':', scope_type, ':', scope_id)
             ELSE NULL
        END
    ) STORED,
    ADD CONSTRAINT uq_memberships_active UNIQUE (active_key);

-- 履歴行も含めた強い UNIQUE（再加入は joined_at が必ず異なる前提）
ALTER TABLE memberships
    ADD CONSTRAINT uq_memberships_history UNIQUE (user_id, scope_type, scope_id, joined_at);

-- 検索用インデックス
CREATE INDEX idx_memberships_scope ON memberships (scope_type, scope_id, left_at);
CREATE INDEX idx_memberships_user ON memberships (user_id, left_at);
CREATE INDEX idx_memberships_role_kind ON memberships (scope_type, scope_id, role_kind, left_at);

-- ========================================================================
-- member_positions の部分 UNIQUE 用生成列 + UNIQUE 制約
-- ========================================================================

-- 同一 membership × 同一 position で同一 started_at の重複を防ぐ
ALTER TABLE member_positions
    ADD CONSTRAINT uq_member_positions_period UNIQUE (membership_id, position_id, started_at);

-- 現役役職は (membership, position) ペアごとに最大 1 行（部分 UNIQUE を生成列で表現）
ALTER TABLE member_positions
    ADD COLUMN active_position_key VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN ended_at IS NULL
             THEN CONCAT(membership_id, ':', position_id)
             ELSE NULL
        END
    ) STORED,
    ADD CONSTRAINT uq_member_positions_active UNIQUE (active_position_key);

CREATE INDEX idx_member_positions_membership ON member_positions (membership_id, ended_at);
CREATE INDEX idx_member_positions_position ON member_positions (position_id, ended_at);
