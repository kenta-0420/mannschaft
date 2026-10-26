-- F01.5 サポーター設定テーブル
-- チーム・組織ごとの自動承認ON/OFF設定を管理する。
-- レコードが存在しない場合はデフォルト（auto_approve=true）として扱う。
CREATE TABLE supporter_settings (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    scope_type   VARCHAR(20) NOT NULL COMMENT 'TEAM または ORGANIZATION',
    scope_id     BIGINT UNSIGNED NOT NULL COMMENT 'チームID または 組織ID',
    auto_approve TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '自動承認: 1=ON, 0=OFF',
    PRIMARY KEY (id),
    UNIQUE KEY uq_supporter_settings (scope_type, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
