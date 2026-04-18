-- F01.5 サポーター申請テーブル
-- チーム・組織へのサポーター申請（PENDING/APPROVED/REJECTED）を管理する。
-- 自動承認OFFの場合に申請レコードが作成される。管理者が承認するとAPPROVED + user_rolesにSUPPORTER追加。
CREATE TABLE supporter_applications (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type     VARCHAR(20)  NOT NULL COMMENT 'TEAM または ORGANIZATION',
    scope_id       BIGINT UNSIGNED NOT NULL COMMENT 'チームID または 組織ID',
    user_id        BIGINT UNSIGNED NOT NULL COMMENT '申請者ユーザーID',
    message        TEXT         NULL     COMMENT '申請メッセージ（任意）',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_supporter_app (scope_type, scope_id, user_id),
    INDEX idx_supporter_app_scope (scope_type, scope_id, status),
    INDEX idx_supporter_app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
