-- F03.8: is_public + min_registration_role を visibility に統合
ALTER TABLE events
    ADD COLUMN visibility VARCHAR(30) NOT NULL DEFAULT 'MEMBERS_ONLY'
        COMMENT '公開範囲: PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_ONLY'
        AFTER status;

UPDATE events SET visibility = 'PUBLIC'               WHERE is_public = TRUE;
UPDATE events SET visibility = 'SUPPORTERS_AND_ABOVE' WHERE is_public = FALSE AND min_registration_role = 'SUPPORTER_PLUS';

ALTER TABLE events
    DROP COLUMN is_public,
    DROP COLUMN min_registration_role;

CREATE INDEX idx_ev_visibility ON events (visibility, status, created_at DESC);
