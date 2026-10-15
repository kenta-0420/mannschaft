-- F00.5 メンバーシップ基盤再設計 Phase 2: 部分 UNIQUE + 検索インデックス
--
-- 設計書: docs/features/F00.5_membership_basis.md §5.5
--
-- NOTE: MySQL 8.0 の制限により、ON DELETE SET NULL / CASCADE FK が設定された
--   カラム（user_id, membership_id 等）は GENERATED ALWAYS AS 列の式に使用できない。
--   このため active_key / active_position_key の生成列アプローチは廃止し、
--   一意制約はアプリ層（MembershipService / MemberPositionService）で担保する。
--
-- memberships:
--   - uq_memberships_history: 履歴行も含めた強い UNIQUE（再加入時は joined_at が異なる）
--   - idx_memberships_scope / idx_memberships_user / idx_memberships_role_kind: 検索インデックス
--
-- member_positions:
--   - uq_member_positions_period: 同一ペアで同一 started_at の重複防止
--   - idx_member_positions_membership / idx_member_positions_position: 検索インデックス

-- ========================================================================
-- memberships の UNIQUE + 検索インデックス
-- ========================================================================

-- 履歴行も含めた強い UNIQUE（再加入は joined_at が必ず異なる前提）
ALTER TABLE memberships
    ADD CONSTRAINT uq_memberships_history UNIQUE (user_id, scope_type, scope_id, joined_at);

-- 検索用インデックス
CREATE INDEX idx_memberships_scope ON memberships (scope_type, scope_id, left_at);
CREATE INDEX idx_memberships_user ON memberships (user_id, left_at);
CREATE INDEX idx_memberships_role_kind ON memberships (scope_type, scope_id, role_kind, left_at);

-- ========================================================================
-- member_positions の UNIQUE + 検索インデックス
-- ========================================================================

-- 同一 membership × 同一 position で同一 started_at の重複を防ぐ
ALTER TABLE member_positions
    ADD CONSTRAINT uq_member_positions_period UNIQUE (membership_id, position_id, started_at);

CREATE INDEX idx_member_positions_membership ON member_positions (membership_id, ended_at);
CREATE INDEX idx_member_positions_position ON member_positions (position_id, ended_at);
