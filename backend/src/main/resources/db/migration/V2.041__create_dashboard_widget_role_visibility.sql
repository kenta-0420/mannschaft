-- F02.2.1: ダッシュボードウィジェットのロール別可視性制御テーブル
--
-- スコープ（チーム／組織）×ウィジェットごとの最低必要ロール（min_role）を管理する。
-- レコードがないウィジェットはアプリ層のデフォルト値（WidgetDefaultMinRoleMap）が適用される。
--
-- 設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §3, §9
-- 関連:
--   - V2.038 系以前の dashboard_widget_settings（個人別表示設定）とは別軸の機能
--   - permissions/users への FK（updated_by は監査用）
CREATE TABLE dashboard_widget_role_visibility (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type  VARCHAR(20)     NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id    BIGINT UNSIGNED NOT NULL COMMENT 'スコープID（teams.id または organizations.id）',
    widget_key  VARCHAR(50)     NOT NULL COMMENT 'WidgetKey enum 値（UPPER_SNAKE_CASE）',
    min_role    VARCHAR(20)     NOT NULL COMMENT 'PUBLIC / SUPPORTER / MEMBER',
    updated_by  BIGINT UNSIGNED NOT NULL COMMENT '最終更新者ユーザーID（監査用）',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_dwrv_scope_widget (scope_type, scope_id, widget_key),
    INDEX idx_dwrv_scope (scope_type, scope_id),
    CONSTRAINT fk_dwrv_updated_by FOREIGN KEY (updated_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F02.2.1 ダッシュボードウィジェットのロール別可視性設定';
