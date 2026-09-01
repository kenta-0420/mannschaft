ALTER TABLE recruitment_listings
    ADD COLUMN moderation_hidden_at DATETIME NULL;

CREATE INDEX idx_recruitment_listings_moderation_hidden
    ON recruitment_listings (moderation_hidden_at);
