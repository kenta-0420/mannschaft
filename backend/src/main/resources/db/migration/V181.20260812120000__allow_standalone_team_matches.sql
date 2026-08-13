-- Practice and friendly matches can belong directly to a team without an organization.
ALTER TABLE matches
    MODIFY COLUMN organization_id BIGINT NULL COMMENT 'Organization scope; NULL for standalone team matches';
