-- FK for social_profile_id → user_social_profiles (Phase 7+ table)
-- ALTER TABLE blog_posts ADD CONSTRAINT fk_bp_social_profile FOREIGN KEY (social_profile_id) REFERENCES user_social_profiles(id) ON DELETE SET NULL;

-- FK for timeline_post_id → timeline_posts (Phase 6 timeline feature)
-- ALTER TABLE blog_posts ADD CONSTRAINT fk_bp_timeline_post FOREIGN KEY (timeline_post_id) REFERENCES timeline_posts(id) ON DELETE SET NULL;

-- These FKs reference tables from other phases. Added as commented TODOs to be enabled when those tables exist.
