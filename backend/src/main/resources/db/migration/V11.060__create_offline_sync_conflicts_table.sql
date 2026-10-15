CREATE TABLE offline_sync_conflicts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id BIGINT UNSIGNED NOT NULL,
    client_data JSON NOT NULL,
    server_data JSON NOT NULL,
    client_version BIGINT NOT NULL,
    server_version BIGINT NOT NULL,
    resolution VARCHAR(20) DEFAULT NULL,
    resolved_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_offline_sync_conflicts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_osc_user_resolution (user_id, resolution, created_at DESC),
    INDEX idx_osc_resource (resource_type, resource_id),
    INDEX idx_osc_resolved_at (resolved_at),
    CONSTRAINT chk_osc_resolution CHECK (resolution IN ('CLIENT_WIN', 'SERVER_WIN', 'MANUAL_MERGE', 'DISCARDED') OR resolution IS NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
