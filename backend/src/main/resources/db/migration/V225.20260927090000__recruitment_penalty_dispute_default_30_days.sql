ALTER TABLE recruitment_penalty_settings
    MODIFY dispute_allowed_days INT NOT NULL DEFAULT 30 COMMENT '異議申立可能期間（日）';
