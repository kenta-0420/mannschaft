ALTER TABLE recruitment_listings
    ADD COLUMN visibility_template_id BIGINT NULL,
    ADD CONSTRAINT fk_recruitment_listings_vt FOREIGN KEY (visibility_template_id) REFERENCES visibility_templates(id) ON DELETE SET NULL;

ALTER TABLE schedules
    ADD COLUMN visibility_template_id BIGINT NULL,
    ADD CONSTRAINT fk_schedules_vt FOREIGN KEY (visibility_template_id) REFERENCES visibility_templates(id) ON DELETE SET NULL;

ALTER TABLE blog_posts
    ADD COLUMN visibility_template_id BIGINT NULL,
    ADD CONSTRAINT fk_blog_posts_vt FOREIGN KEY (visibility_template_id) REFERENCES visibility_templates(id) ON DELETE SET NULL;
