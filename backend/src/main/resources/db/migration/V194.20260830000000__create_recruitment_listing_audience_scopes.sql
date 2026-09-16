-- F22.1 Phase 4: PERSONAL + SELECTED_SCOPES の公開先を、可変 VisibilityTemplate と
-- 分離した listing 専用スナップショットとして固定する。listing_id は同一ドメイン FK のみ張る。
CREATE TABLE recruitment_listing_audience_scopes (
    id          BINARY(16)      NOT NULL COMMENT 'PK UUIDv7',
    listing_id  BIGINT UNSIGNED NOT NULL COMMENT 'FK -> recruitment_listings.id (CASCADE)',
    scope_type  VARCHAR(20)     NOT NULL COMMENT 'TEAM / ORGANIZATION',
    scope_id    BIGINT UNSIGNED NOT NULL COMMENT '対象 scope ID。クロスドメインのため FK は張らない',
    created_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_rlas_listing
        FOREIGN KEY (listing_id) REFERENCES recruitment_listings (id) ON DELETE CASCADE,
    CONSTRAINT ck_rlas_scope_type CHECK (scope_type IN ('TEAM', 'ORGANIZATION')),
    CONSTRAINT uk_rlas_listing_scope UNIQUE (listing_id, scope_type, scope_id),
    INDEX idx_rlas_listing (listing_id),
    INDEX idx_rlas_scope (scope_type, scope_id, listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='F22.1 Phase 4 個人札の固定公開先スコープ';
