-- L1-M: 明示的な再開と同じTXに追記する専用監査。
-- publicationと操作者が削除されても履歴を残すため、外部キーとCASCADEを持たない。
CREATE TABLE module_event_resume_audits (
    id BINARY(16) NOT NULL,
    publication_id BINARY(16) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_module_event_resume_audits_publication_created (publication_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
