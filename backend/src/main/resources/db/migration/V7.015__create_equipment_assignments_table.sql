-- F07.3 備品管理: 貸出・返却履歴テーブル
CREATE TABLE equipment_assignments (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    equipment_item_id       BIGINT UNSIGNED NOT NULL,
    assigned_to_user_id     BIGINT UNSIGNED NOT NULL,
    assigned_by_user_id     BIGINT UNSIGNED NULL,
    quantity                INT UNSIGNED    NOT NULL DEFAULT 1,
    assigned_at             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expected_return_at      DATE            NULL,
    returned_at             DATETIME        NULL,
    returned_by_user_id     BIGINT UNSIGNED NULL,
    note                    VARCHAR(300)    NULL,
    last_overdue_notified_at DATETIME       NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_ea_equipment    FOREIGN KEY (equipment_item_id)   REFERENCES equipment_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ea_assigned_to  FOREIGN KEY (assigned_to_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ea_assigned_by  FOREIGN KEY (assigned_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_ea_returned_by  FOREIGN KEY (returned_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_ea_equipment  (equipment_item_id),
    INDEX idx_ea_user       (assigned_to_user_id, returned_at),
    INDEX idx_ea_overdue    (expected_return_at, returned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
