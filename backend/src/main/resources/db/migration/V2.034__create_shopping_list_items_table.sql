-- F01.4: お買い物リストの個別アイテム
CREATE TABLE shopping_list_items (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    list_id     BIGINT UNSIGNED NOT NULL,
    name        VARCHAR(200)    NOT NULL COMMENT 'アイテム名',
    quantity    VARCHAR(50)     NULL     COMMENT '数量（自由記述）',
    note        VARCHAR(500)    NULL     COMMENT 'メモ',
    assigned_to BIGINT UNSIGNED NULL     COMMENT '担当メンバー',
    is_checked  BOOLEAN         NOT NULL DEFAULT FALSE,
    checked_by  BIGINT UNSIGNED NULL,
    checked_at  DATETIME        NULL,
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_by  BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sli_list     (list_id, is_checked, sort_order),
    INDEX idx_sli_assigned (assigned_to, is_checked),
    CONSTRAINT fk_sli_list      FOREIGN KEY (list_id)     REFERENCES shopping_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_sli_assigned  FOREIGN KEY (assigned_to) REFERENCES users (id),
    CONSTRAINT fk_sli_checked   FOREIGN KEY (checked_by)  REFERENCES users (id),
    CONSTRAINT fk_sli_created   FOREIGN KEY (created_by)  REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
