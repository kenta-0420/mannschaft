-- モジュール推奨関係テーブル
CREATE TABLE module_recommendations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    module_id BIGINT UNSIGNED NOT NULL,
    recommended_module_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(200) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_module_rec UNIQUE (module_id, recommended_module_id),
    CONSTRAINT fk_module_rec_module FOREIGN KEY (module_id) REFERENCES module_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_module_rec_recommended FOREIGN KEY (recommended_module_id) REFERENCES module_definitions(id) ON DELETE CASCADE
);
