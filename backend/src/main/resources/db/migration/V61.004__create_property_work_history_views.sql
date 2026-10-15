-- F09.13 物件履歴台帳: 閲覧履歴（監査用）
-- 設計書 §3 property_work_history_views テーブル定義に対応
-- 90日経過後に日次バッチで物理削除（F10.3 audit_logs と整合）
CREATE TABLE property_work_history_views (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    package_id  BIGINT UNSIGNED NOT NULL,
    user_id     BIGINT UNSIGNED NOT NULL,
    action      VARCHAR(20)     NOT NULL,
    viewed_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  VARCHAR(45)     NULL,
    user_agent  VARCHAR(255)    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pwhv_package FOREIGN KEY (package_id) REFERENCES property_work_packages (id) ON DELETE CASCADE,
    CONSTRAINT fk_pwhv_user    FOREIGN KEY (user_id)    REFERENCES users (id)                  ON DELETE CASCADE,
    CONSTRAINT chk_pwhv_action CHECK (action IN ('VIEW','EXPORT','DOWNLOAD_ATTACHMENT')),
    INDEX idx_pwhv_package_time (package_id, viewed_at),
    INDEX idx_pwhv_user_time    (user_id, viewed_at),
    INDEX idx_pwhv_retention    (viewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
