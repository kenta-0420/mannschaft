-- =============================================
-- F07.1 サービス履歴: service_record_reactions テーブル
-- =============================================
CREATE TABLE service_record_reactions (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    service_record_id BIGINT UNSIGNED NOT NULL,
    user_id           BIGINT UNSIGNED NOT NULL,
    reaction_type     ENUM('LIKE', 'THANKS', 'GREAT') NOT NULL DEFAULT 'LIKE',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_srr_record FOREIGN KEY (service_record_id) REFERENCES service_records(id) ON DELETE CASCADE,
    CONSTRAINT fk_srr_user   FOREIGN KEY (user_id)           REFERENCES users(id)           ON DELETE CASCADE,
    UNIQUE KEY uq_srr_record_user (service_record_id, user_id),
    INDEX idx_srr_record (service_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
