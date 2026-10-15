-- F03.11 募集型予約: チーム/組織が任意で追加するサブカテゴリ (Phase 1)
CREATE TABLE recruitment_subcategories (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    category_id             BIGINT UNSIGNED NOT NULL,
    parent_subcategory_id   BIGINT UNSIGNED,
    scope_type              VARCHAR(20)     NOT NULL,
    scope_id                BIGINT UNSIGNED NOT NULL,
    name                    VARCHAR(100)    NOT NULL,
    display_order           INT UNSIGNED    NOT NULL DEFAULT 0,
    created_by              BIGINT UNSIGNED NOT NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at              DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_rs_category
        FOREIGN KEY (category_id) REFERENCES recruitment_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_rs_parent
        FOREIGN KEY (parent_subcategory_id) REFERENCES recruitment_subcategories (id) ON DELETE SET NULL,
    CONSTRAINT fk_rs_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_rs_scope (scope_type, scope_id, category_id),
    -- 論理削除済みは UNIQUE 対象外。MySQL 8.0 関数インデックスで部分インデックス代替
    UNIQUE INDEX uk_rs_active_name ((CASE WHEN deleted_at IS NULL THEN scope_type END), scope_id, category_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
