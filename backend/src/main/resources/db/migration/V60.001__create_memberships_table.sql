-- F00.5 メンバーシップ基盤再設計 Phase 2: memberships テーブル作成
--
-- 設計書: docs/features/F00.5_membership_basis.md §5.1
--
-- 「メンバーシップそのもの（誰がどのスコープに、いつ入会・退会したか）」を
-- user_roles から分離し、専用の正規化された 1 表で表現する。
--
-- 多態 1 表（scope_type = ORGANIZATION | TEAM）。scope_id への FK は MySQL 8.0 が
-- 条件付き FK をサポートしないため張らない（整合性はアプリ層 + 監査バッチで担保）。
--
-- 3 つの CHECK 制約:
--   - chk_memberships_left_reason: left_at と leave_reason の同期
--   - chk_memberships_period: left_at >= joined_at（期間の逆転防止）
--
-- NOTE: chk_memberships_gdpr_masked（gdpr_masked_at IS NULL OR user_id IS NULL）は
--   MySQL 8.0 の制限により削除。ON DELETE SET NULL FK のカラムは CHECK 制約に使えない。
--   GDPR マスキング時の user_id=NULL 保証はアプリ層（MembershipService）で担保する。
--
-- 2 つの FK SET NULL:
--   - fk_memberships_user: users.id 削除時に user_id を NULL 化（履歴は壊さない）
--   - fk_memberships_invited_by: 招待者の users.id 削除時に invited_by を NULL 化

CREATE TABLE memberships (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NULL,
    scope_type ENUM('ORGANIZATION','TEAM') NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    role_kind ENUM('MEMBER','SUPPORTER') NOT NULL DEFAULT 'MEMBER',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at DATETIME NULL,
    leave_reason ENUM('SELF','REMOVED','GDPR','TRANSFER','OTHER') NULL,
    invited_by BIGINT UNSIGNED NULL,
    gdpr_masked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_memberships_invited_by FOREIGN KEY (invited_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_memberships_left_reason CHECK (
        (left_at IS NULL AND leave_reason IS NULL)
        OR (left_at IS NOT NULL AND leave_reason IS NOT NULL)
    ),
    CONSTRAINT chk_memberships_period CHECK (
        left_at IS NULL OR left_at >= joined_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
