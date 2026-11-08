-- 強匿名化後の理由再生を防ぐmoduleevent所有状態。監査保持期限とは独立に保持する。
CREATE TABLE module_event_resume_actor_states (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '実行者ID。匿名参照を保持しusersへのFKは作らない',
    purged BOOLEAN NOT NULL COMMENT '強匿名化済み。trueからfalseへ戻さない',
    PRIMARY KEY (id),
    UNIQUE KEY uq_module_event_resume_actor_states_actor_user_id (actor_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='再開理由の再生防止状態';
